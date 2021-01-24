package com.tverona.scpanywhere.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.webkit.*
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.*
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewAssetLoader
import androidx.work.*
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.database.BookmarkEntry
import com.tverona.scpanywhere.pathhandlers.*
import com.tverona.scpanywhere.repositories.SpeechContent
import com.tverona.scpanywhere.utils.*
import com.tverona.scpanywhere.viewmodels.*
import com.tverona.scpanywhere.worker.SpeechProviderWorker
import com.tverona.scpanywhere.zipresource.ZipResourceFile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

/**
 * Fragment to handle web view
 */
@AndroidEntryPoint
class WebViewFragment : Fragment(), View.OnClickListener {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private enum class FragmentState {
        NONE, CREATEVIEW, CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED
    }

    private var fragmentState: FragmentState = FragmentState.NONE

    // Text to speech provider & state
    @Inject
    lateinit var textToSpeechProvider: TextToSpeechProvider

    @Inject
    lateinit var speechContent: SpeechContent

    private val isSpeaking = MutableLiveData<Boolean>(false)

    // Listener for refresh swipe
    private var swipeRefreshLayout: SwipeRefreshLayout? = null

    // In-memory path handler (used to dynamically inject CSS content
    private var inMemoryPathHandler = InMemoryPathHandler()

    // Web view state
    private var webViewInitialized = false
    private var webViewBundle: Bundle? = null

    // Whether we're in offline mode
    private var isOfflineMode: Boolean? = null

    // View models
    private val scpDataItemViewModel: ScpDataViewModel by activityViewModels()
    private val webDataViewModel: WebDataViewModel by activityViewModels()
    private lateinit var webviewContentViewModel: WebViewContentViewModel

    // Current url & title
    private var currentUrlTitle = MutableLiveData<UrlTitle>()

    // Used to monitor read time
    private lateinit var monitorTimer: MonitorTimer

    private lateinit var sharedPreferences: SharedPreferences

    // Helper utility to auto mark page as read
    private lateinit var autoMarkReadHelper: AutoMarkReadHelper

    // Current webview zoom scale
    private var zoomScale: Float = 1.0f

    // Content options
    private lateinit var contentOptions: ContentOptions

    class UrlTitle(var url: String, var title: String, var subTitle: String?)

    class WebViewContentViewModel : ViewModel() {
        val content = LiveEvent<String?>()
    }

    /**
     * Javascript callback, used to get page content when text-to-speech is turned on
     */
    private class JavaScriptInterface(val webviewContentViewModel: WebViewContentViewModel) {
        @JavascriptInterface
        fun handleHtml(html: String?) {
            val doc = Jsoup.parse(html)
            doc.select("div.footer-wikiwalk-nav").remove()
            doc.select("div.page-tags").remove()
            val content = doc.wholeText()
            webviewContentViewModel.content.postValue(content)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        logv("OnCreateView: this: $this, $savedInstanceState")

        fragmentState = FragmentState.CREATEVIEW
        val root = inflater.inflate(R.layout.fragment_webview, container, false)
        val offlineDataViewModel: OfflineDataViewModel by activityViewModels()
        val cssOverrideModel: CssOverrideViewModel by activityViewModels()

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        monitorTimer = MonitorTimer(viewLifecycleOwner)

        // Get content options
        contentOptions = ContentOptions(
            expandBlocks = sharedPreferences.getBoolean(
                getString(R.string.expand_blocks_key),
                false
            ), expandTabs = sharedPreferences.getBoolean(
                getString(R.string.expand_tabs_key),
                false
            )
        )

        webviewContentViewModel =
            ViewModelProvider(this).get(WebViewContentViewModel::class.java)

        autoMarkReadHelper = AutoMarkReadHelper(requireContext()) {
            if (fragmentState != FragmentState.DESTROYED) {
                updateBookmarkEntry(it, isReadEntry = true, toggle = false)
            }
        }

        webViewInitialized = false
        webView = root.findViewById(R.id.webview)
        webView.addJavascriptInterface(
            JavaScriptInterface(webviewContentViewModel),
            "HtmlHandler"
        )

        // Enable pinch-to-zoom
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        if (null == isOfflineMode) {
            isOfflineMode = webDataViewModel.offlineMode.value
        } else if (isOfflineMode != webDataViewModel.offlineMode.value) {
            // Clear cached state, if any
            logv("Updating offline mode to ${webDataViewModel.offlineMode.value}, web view bundle: ${webViewBundle == null}")
            webViewBundle = null
            isOfflineMode = webDataViewModel.offlineMode.value
        }

        swipeRefreshLayout = root.findViewById(R.id.swiperefresh)
        swipeRefreshLayout?.setOnRefreshListener {
            if (webViewInitialized) {
                webView.reload()
            }
        }

        progressBar = root.findViewById(R.id.progressBar) as ProgressBar
        progressBar.visibility = View.VISIBLE

        setToolbarTitleClickListener()
        clearTitle(getString(R.string.loading))

        // Observe offline resource file & scp lists initialization
        val skipOnInstanceRestore = hasHistoryState(savedInstanceState)
        offlineDataViewModel.zip.combineWith(scpDataItemViewModel.scpEntriesByUrl) { zipResourceFile, scpListByUrl ->
            Pair(zipResourceFile, scpListByUrl)
        }.observe(viewLifecycleOwner) {
            val (zipResourceFile, scpListByUrl) = it
            if (null != zipResourceFile && null != scpListByUrl) {
                // Load web view
                logv("Loading web view $skipOnInstanceRestore")
                loadWebView(skipOnInstanceRestore, zipResourceFile, scpListByUrl)
            }
        }

        // Observe for dynamic css content & provide it to in-memory path handler
        cssOverrideModel.cssMergedContent.observe(viewLifecycleOwner) { cssContent ->
            this.inMemoryPathHandler.addEntry(
                InMemoryResourceEntry(
                    "override.css",
                    "text/css", cssContent
                )
            )
        }

        // Observe speech provider worker
        WorkManager.getInstance(requireContext()).getWorkInfosByTagLiveData(SpeechProviderWorker.SPEECHPROVIDER_WORKER_TAG).observe(viewLifecycleOwner) { workInfoList ->
            if (null != workInfoList) {
                for (workInfo in workInfoList) {
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> {
                            logv("Speech provier worker enqueued")
                            isSpeaking.value = true
                        }

                        WorkInfo.State.RUNNING -> {
                            if (isSpeaking.value == false) {
                                logv("Speech provier worker running")
                                isSpeaking.value = true
                            }
                        }

                        WorkInfo.State.FAILED,
                        WorkInfo.State.CANCELLED,
                        WorkInfo.State.SUCCEEDED -> {
                            logv("Speech provier worker stopped, state: ${workInfo.state}")
                            isSpeaking.value = false
                            stopSpeak()
                        }
                    }
                }
            }
            WorkManager.getInstance(requireContext()).pruneWork()
        }

        setHasOptionsMenu(true)
        return root
    }

    /**
     * Whether we have any history in the saved stated
     */
    private fun hasHistoryState(savedInstanceState: Bundle?): Boolean {
        val bundle = savedInstanceState?.get("webViewState") as Bundle?
        if (null != bundle) {
            return bundle.size() > 0
        }

        return false
    }

    /**
     * Call into javascript injection to get content for text-to-speech
     */
    private fun getWebViewContent() {
        webView.evaluateJavascript(
            "javascript:window.HtmlHandler.handleHtml" +
                    "('<html>'+document.getElementsByTagName('body')[0].outerHTML+'</html>');"
        ) {
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (null != webViewBundle) {
            logv("Fragment state is $fragmentState, using saved bundle")
            outState.putBundle("webViewState", webViewBundle)
        }
    }

    override fun onDestroyView() {
        logv("onDestroyView")
        super.onDestroyView()
        fragmentState = FragmentState.DESTROYED
    }

    private fun setToolbarTitleClickListener() {
        val toolbar: Toolbar = requireActivity().findViewById(R.id.toolbar)

        // Initialize both title & subtitle objects
        toolbar.title = " "
        toolbar.subtitle = " "

        // Set on click listeners
        ViewTools.findActionBarTitle(toolbar)?.setOnClickListener(this)
        ViewTools.findActionBarSubTitle(toolbar)?.setOnClickListener(this)
    }

    fun onBackPressed(): Boolean {
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        logv("onCreate")
        fragmentState = FragmentState.CREATED
        webViewBundle = savedInstanceState?.getBundle("webViewState")
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        logv("onStart")
        fragmentState = FragmentState.STARTED
        super.onStart()
    }

    override fun onResume() {
        logv("onResume")
        fragmentState = FragmentState.RESUMED

        currentUrlTitle.observeOnce(viewLifecycleOwner) { urlTitle ->
            startReadTimer(urlTitle.url, urlTitle.title)
        }
        super.onResume()
    }

    override fun onPause() {
        logv("onPause")
        fragmentState = FragmentState.PAUSED
        webViewBundle = Bundle()
        webView.saveState(webViewBundle!!)
        monitorTimer.stop()
        super.onPause()
    }

    override fun onStop() {
        logv("onStop $this")
        fragmentState = FragmentState.STOPPED
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.webview_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)

        val readItem = menu.findItem(R.id.read)
        val favoriteItem = menu.findItem(R.id.favorite)
        val nextItem = menu.findItem(R.id.next)
        val playItem = menu.findItem(R.id.play)

        readItem.isEnabled = false
        favoriteItem.isEnabled = false
        nextItem.isVisible = false
        playItem.isEnabled = false

        // Listen for url & title (populated after page is loaded) and then enable various menu options
        currentUrlTitle.observe(viewLifecycleOwner) { urlTitle ->
            // Set url title on auto mark read helper
            autoMarkReadHelper.onUrlTitle(urlTitle)

            // Enable text to speech button when text to speech provider is initialized
            textToSpeechProvider.isInitialized.observe(viewLifecycleOwner) {
                playItem.isEnabled = it
            }

            // Enable favorite & read buttons if the url is within our domain
            val url = RegexUtils.normalizeUrl(requireContext(), urlTitle.url)
            if (url.startsWith(getString(R.string.base_path))) {
                readItem.isEnabled = true
                favoriteItem.isEnabled = true
            } else {
                readItem.isEnabled = false
                favoriteItem.isEnabled = false
            }

            // Observe changes in scp list and enable / disable next button
            scpDataItemViewModel.scpEntriesByUrl.observe(viewLifecycleOwner) {
                nextItem.isVisible = false
                if (it.containsKey(url)) {
                    val nextEntry = it[url]!!.next
                    if (null != nextEntry) {
                        nextItem.isVisible = true
                    }
                }
            }

            // Observe changes in read list and enable / disable read button
            scpDataItemViewModel.readByUrl.observe(viewLifecycleOwner) {
                if (it.containsKey(url)) {
                    readItem.icon = ContextCompat.getDrawable(
                        requireActivity(),
                        R.drawable.baseline_bookmark_24
                    )
                    readItem.title = getString(R.string.mark_unread)
                } else {
                    readItem.icon = ContextCompat.getDrawable(
                        requireActivity(),
                        R.drawable.baseline_bookmark_border_24
                    )
                    readItem.title = getString(R.string.mark_read)
                }
            }

            // Observe changes in favorites list and enable / disable favorite button
            scpDataItemViewModel.favoriteByUrl.observe(viewLifecycleOwner) {
                if (it.containsKey(url)) {
                    val entry = it[url]
                    favoriteItem.icon = ContextCompat.getDrawable(
                        requireActivity(),
                        R.drawable.baseline_favorite_24
                    )
                    favoriteItem.title = getString(R.string.mark_unfavorite)
                } else {
                    favoriteItem.icon = ContextCompat.getDrawable(
                        requireActivity(),
                        R.drawable.baseline_favorite_border_24
                    )
                    favoriteItem.title = getString(R.string.mark_favorite)
                }
            }
        }

        if (isStaleWebView()) {
            val upgradeWebview = menu.findItem(R.id.upgrade_stale_webview)
            upgradeWebview.setVisible(true)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        isSpeaking.observe(viewLifecycleOwner) {
            val playItem = menu.findItem(R.id.play)
            if (it) {
                playItem.icon = ContextCompat.getDrawable(
                    requireActivity(),
                    R.drawable.baseline_pause_24
                )
                playItem.title = getString(R.string.pause)
            } else {
                playItem.icon = ContextCompat.getDrawable(
                    requireActivity(),
                    R.drawable.baseline_play_arrow_24
                )
                playItem.title = getString(R.string.play)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.favorite,
            R.id.read -> {
                // Update bookmark entry
                currentUrlTitle.observeOnce(viewLifecycleOwner) { urlTitle ->
                    updateBookmarkEntry(urlTitle, item.itemId == R.id.read, toggle = true)
                }
                true
            }
            R.id.next -> {
                // Move to next page
                currentUrlTitle.observeOnce(viewLifecycleOwner) { urlTitle ->
                    val url = RegexUtils.normalizeUrl(requireContext(), urlTitle.url)
                    scpDataItemViewModel.scpEntriesByUrl.observeOnce(viewLifecycleOwner) {
                        if (it.containsKey(url)) {
                            val nextEntry = it[url]!!.next
                            if (null != nextEntry) {
                                autoMarkReadHelper.onNextItem()
                                webDataViewModel.url.value = nextEntry.url
                            }
                        }
                    }
                }
                true
            }
            R.id.play -> {
                // Start / stop speaking
                isSpeaking.observeOnce(viewLifecycleOwner) {
                    if (it) {
                        stopSpeak()
                    } else {
                        startSpeak()
                    }
                }
                true
            }
            R.id.share -> {
                // Share page
                currentUrlTitle.observeOnce(viewLifecycleOwner) { urlTitle ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        val title = urlTitle.title
                        val url = RegexUtils.normalizeUrl(requireContext(), urlTitle.url)
                        putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.share_url)}: $title")
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_url)))
                }
                true
            }
            R.id.upgrade_stale_webview -> {
                // Alert on stale system webview
                alertOnStaleWebView()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Update bookmark entry for [urlTitle] with either read (if [isReadEntry] is true) or favorite. Value will be flipped in [toggle] or else set to true.
     */
    private fun updateBookmarkEntry(urlTitle: UrlTitle, isReadEntry: Boolean, toggle: Boolean) {
        // Update bookmark entry
        val url = RegexUtils.normalizeUrl(requireContext(), urlTitle.url)
        scpDataItemViewModel.getBookmarkByUrl(url).observeOnce(viewLifecycleOwner) {
            var title = urlTitle.title
            if (urlTitle.subTitle != null) {
                title += " - ${urlTitle.subTitle}"
            }
            var bookmarkEntry = it
            if (null == bookmarkEntry) {
                bookmarkEntry = BookmarkEntry(
                    url = url,
                    title = title,
                    favorite = false,
                    read = false
                )
            }
            if (isReadEntry) {
                bookmarkEntry.read = if (toggle) !bookmarkEntry.read else true
            } else {
                bookmarkEntry.favorite = if (toggle) !bookmarkEntry.favorite else true
            }
            logv("dao: updating entry: $bookmarkEntry")
            scpDataItemViewModel.updateBookmarkEntry(bookmarkEntry)
        }
    }

    /**
     * Start speaking current page content
     */
    private fun startSpeak() {
        getWebViewContent()
        webviewContentViewModel.content.observeOnce(viewLifecycleOwner) { content ->
            currentUrlTitle.observeOnce(viewLifecycleOwner) { urlTitle ->

                if (null != content) {
                    speechContent.content = content

                    val speechWorkerData = workDataOf(
                        SpeechProviderWorker.KEY_URL to urlTitle.url,
                        SpeechProviderWorker.KEY_TITLE to urlTitle.title
                    )

                    val request =
                        OneTimeWorkRequestBuilder<SpeechProviderWorker>()
                            .setInputData(speechWorkerData)
                            .addTag(SpeechProviderWorker.SPEECHPROVIDER_WORKER_TAG)
                            .build()

                    // Enqueue work
                    WorkManager.getInstance(requireContext()).enqueueUniqueWork(
                        SpeechProviderWorker.SPEECHPROVIDER_WORKER_TAG,
                        ExistingWorkPolicy.REPLACE,
                        request
                    )
                }
            }
        }
    }

    /**
     * Stop speaking
     */
    private fun stopSpeak() {
        WorkManager.getInstance(requireContext()).cancelAllWorkByTag(SpeechProviderWorker.SPEECHPROVIDER_WORKER_TAG)
        speechContent.content = null
    }

    /**
     * Persist current url
     */
    private fun saveUrl(url: String) {
        val normalizedUrl = RegexUtils.normalizeUrl(requireContext(), url)
        with(sharedPreferences.edit()) {
            if (webDataViewModel.offlineMode.value == true) {
                putString(getString(R.string.last_url_offline), normalizedUrl)
            } else {
                putString(getString(R.string.last_url_online), normalizedUrl)
            }
            commit()
        }
    }

    /**
     * Clear action bar title
     */
    private fun clearTitle(title: String? = null) {
        if (fragmentState != FragmentState.DESTROYED && fragmentState != FragmentState.PAUSED) {
            (requireActivity() as AppCompatActivity).supportActionBar?.title = title
            (requireActivity() as AppCompatActivity).supportActionBar?.subtitle = null
        }
    }

    /**
     * Update action bar title with current page title / sub title
     */
    private fun updateTitle(view: WebView, url: String) {
        scpDataItemViewModel.scpEntriesByUrl.observeOnce(viewLifecycleOwner) {
            var title = view.title ?: ""
            var subTitle: String? = null
            val origUrl = RegexUtils.normalizeUrl(requireContext(), url)
            val scpEntry = it[origUrl]
            if (null != scpEntry) {
                title = scpEntry.title
            }

            val titleMatch = RegexUtils.scpTitlePattern.find(title)
            if (null != titleMatch && titleMatch.groups.size > 2) {
                title = titleMatch.groups[1]?.value ?: title
                subTitle = titleMatch.groups[2]?.value
            }

            // Update action bar title
            if (null != subTitle) {
                (requireActivity() as AppCompatActivity).supportActionBar?.title =
                    StringFormatter.htmlText(title)
                (requireActivity() as AppCompatActivity).supportActionBar?.subtitle =
                    StringFormatter.htmlText(subTitle)
            } else {
                (requireActivity() as AppCompatActivity).supportActionBar?.title =
                    StringFormatter.htmlText(title)
                (requireActivity() as AppCompatActivity).supportActionBar?.subtitle = null
            }

            // Save current url & title
            currentUrlTitle.value = UrlTitle(url = url, title = title, subTitle = subTitle)
        }
    }

    /**
     * Display a friendlier page not found response
     */
    private fun createNotFoundResponse(url: String, didDownloadFile: Boolean): WebResourceResponse {
        val doc = Document.createShell("http://" + getString((R.string.base_path)))
        doc.body().append(
            """<body>
                <h1 id="error404">The page you are looking for cannot be found</h1>"""
        )
        if (isOfflineMode == true) {
            if (!didDownloadFile) {
                doc.body().append(
                    """<h2>Looks like you have not downloaded any offline data yet.</h2>
                       <h2>Download offline data or switch to online mode.</h2>"""
                )
            } else {
                doc.body().append(
                    """<h2>Try checking for updates or switch to online mode</h2>"""
                )
            }
        }
        doc.body().append(
            """<div>The page ${
                RegexUtils.normalizeUrl(
                    requireContext(),
                    url
                )
            } cannot be found.</div>
                </body>"""
        )
        doc.title(getString(R.string.page_not_found))

        return WebResourceResponse("text/html", "UTF-8", doc.html().byteInputStream())
    }

    /**
     * Loads web view
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun loadWebView(
        skipOnInstanceRestore: Boolean,
        zipResourceFile: ZipResourceFile,
        urlListByUrl: Map<String, UrlEntry>
    ) {
        logi("Loading web view")

        val assetsPathHandler = WebViewAssetLoader.AssetsPathHandler(requireContext())

        // Below we set up our online and offline resource path handlers.
        // /scpanywhere-assets/ refer to built-in assets (like fonts).
        // /scpanywhere_inject/ refers to the our dynamic css injection (handled by an in-memory path handler).
        // THe root path / refers to either the online or offline content.

        // Set up offline resource path handlers
        val offlineResourceLoader = WebViewResourceLoader.Builder()
            .addPathHandler(
                WebViewAssetLoader.DEFAULT_DOMAIN,
                "/scpanywhere_assets/",
                assetsPathHandler
            )
            .addPathHandler(
                WebViewAssetLoader.DEFAULT_DOMAIN,
                "/scpanywhere_inject/",
                inMemoryPathHandler
            )
            .addPathHandler(
                WebViewAssetLoader.DEFAULT_DOMAIN,
                "/",
                ZipStoragePathHandler(zipResourceFile, contentOptions)
            )
            .build()

        // Set up online resource path handles
        val onlineResourceLoader = WebViewResourceLoader.Builder()
            .addPathHandler(
                getString(R.string.base_path),
                "/scpanywhere_assets/",
                assetsPathHandler
            )
            .addPathHandler(
                getString(R.string.base_path),
                "/scpanywhere_inject/",
                inMemoryPathHandler
            )
            .addPathHandler(
                getString(R.string.base_path),
                "/",
                OnlinePathHandler(requireContext(), urlListByUrl, contentOptions),
                handleFullPath = true
            )
            .build()

        progressBar.visibility = View.GONE

        webView.settings.javaScriptEnabled = true
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                logv("got title: $title")
            }
        }

        webView.webViewClient = object : WebViewClient() {

            /**
             * Should intercept request delegates to the web view resource loader (which invokes the path handlers)
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val response: WebResourceResponse?
                if (isOfflineUrl(request.url.toString())) {
                    response = offlineResourceLoader.shouldInterceptRequest(request.url)
                } else {
                    response = onlineResourceLoader.shouldInterceptRequest(request.url)
                }

                if (response != null && response.data == null) {
                    return createNotFoundResponse(
                        request.url.toString(),
                        zipResourceFile.getNumEntries() > 0
                    )
                }

                return response
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                logv("onPageStarted")

                // Handle auto-mark as read on next item
                if (null != url) {
                    handleAutoMarkReadOnNextItem(url)
                }

                clearTitle(getString(R.string.loading))
                autoMarkReadHelper.onNewPage()
                monitorTimer.stop()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                startReadTimer(url, view.title)

                // onPageFinished callback can be called after view has already been destroyed.
                // Check for this here.
                if (fragmentState == FragmentState.DESTROYED) {
                    return
                }

                logv("Loaded $url")

                swipeRefreshLayout?.isRefreshing = false

                // Save the last visited URL
                saveUrl(url)

                // Update title
                updateTitle(view, url)

                // Handle auto mark read when content fits in view
                handleAutoMarkReadContentFitsInView()

                // Handle auto mark read for estimated read time
                handleAutoMarkReadEstimatedReadTime()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                loge("Error loading page ${request?.url}: ${error?.description}, ${error?.errorCode} ")
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                loge("onRenderProcessGone: $detail")
                return super.onRenderProcessGone(view, detail)
            }

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                // Track current zoom scale so we can properly calculate when scrolling to bottom of page
                zoomScale = newScale
                super.onScaleChanged(view, oldScale, newScale)
            }
        }

        webView.setOnScrollChangeListener { view: View, scrollX: Int, scrollY: Int, oldScrollx: Int, oldScrollY: Int ->
            // Handle auto mark read when scroll to bottom is enabled
            if (autoMarkReadHelper.isScrollToBottomEnabled()) {
                val webView = view as WebView
                val contentHeight = webView.contentHeight * webView.scale
                val webViewHeight = webView.height
                val total =
                    Math.max(
                        contentHeight - webViewHeight,
                        0f
                    )

                if (scrollY >= total - 5) {
                    // Scroll reached bottom
                    autoMarkReadHelper.onScrolledToBottom()
                }
            }
        }

        webViewInitialized = true

        if (webViewBundle != null && webViewBundle!!.size() > 0) {
            logv("Loaded state")
            webView.clearHistory()
            webView.restoreState(webViewBundle!!)
        } else {
            webViewBundle = null
        }

        // Skip loading url when restoring from state, since we're already loading the web view history
        var skipLoadingUrl = skipOnInstanceRestore
        webDataViewModel.url.observe(viewLifecycleOwner) { url ->
            if (null != url && webViewInitialized) {
                if (!skipLoadingUrl) {
                    var fullUrl: String
                    if (webDataViewModel.offlineMode.value == true) {
                        fullUrl = resources.getString(R.string.local_url_prefix) + url
                        if (!fullUrl.endsWith("/index.html")) {
                            fullUrl += "/index.html"
                        }
                    } else {
                        fullUrl = "http://" + url
                    }

                    logv("Loading url $fullUrl, offline mode: ${webDataViewModel.offlineMode.value}")

                    webView.loadUrl(fullUrl)
                }

                skipLoadingUrl = false
                webDataViewModel.url.value = null
            }
        }
    }

    private fun isOfflineUrl(url: String): Boolean {
        return url.startsWith(getString(R.string.local_url_prefix))
    }

    /**
     * Handle auto mark as read when content fits in view
     */
    private fun handleAutoMarkReadContentFitsInView() {
        webView.evaluateJavascript("""window.innerHeight + window.scrollY > document.body.scrollHeight""") {
            if (it.toBoolean()) {
                autoMarkReadHelper.onScrolledToBottom()
            }
        }
    }

    /**
     * Handle auto mark as read time for estimated read time
     */
    private fun handleAutoMarkReadEstimatedReadTime() {
        if (autoMarkReadHelper.isEstimatedReadTimeEnabled()) {
            getWebViewContent()
            webviewContentViewModel.content.observeOnce(viewLifecycleOwner) {
                if (null != it) {
                    val wordCount =
                        Regex("""(\s+|(\r\n|\r|\n))""").findAll(it.trim()).count() + 1

                    // Estimated read time is based on 200 words / min (average read time for online content)
                    val readEstimateSecs = (wordCount / 200f) * 60
                    logv("Words: $wordCount, read estimate: ${readEstimateSecs .toInt()} secs")
                    autoMarkReadHelper.setEstimatedReadTime(readEstimateSecs.toLong())
                }
            }
        }
    }

    /**
     * Handle auto mark as read on next / prev item
     */
    private fun handleAutoMarkReadOnNextItem(url: String) {
        if (autoMarkReadHelper.isOnNextItemEnabled() && currentUrlTitle.value != null && null != currentUrlTitle.value?.url) {
            val normalizedPreviousUrl = RegexUtils.normalizeUrl(
                requireContext(),
                currentUrlTitle.value?.url!!
            )
            val normalizedCurrentUrl = RegexUtils.normalizeUrl(requireContext(), url)

            scpDataItemViewModel.scpEntriesByUrl.observeOnce(viewLifecycleOwner) {
                if (it.containsKey(normalizedPreviousUrl)) {
                    val nextEntry = it[normalizedPreviousUrl]?.next
                    if (null != nextEntry && nextEntry.url.equals(
                            normalizedCurrentUrl,
                            ignoreCase = true
                        )
                    ) {
                        autoMarkReadHelper.onNextItem()
                    } else {
                        val prevEntry = it[normalizedPreviousUrl]?.prev
                        if (null != prevEntry && prevEntry.url.equals(
                                normalizedCurrentUrl,
                                ignoreCase = true
                            )
                        ) {
                            autoMarkReadHelper.onNextItem()
                        }
                    }
                }
            }
        }
    }

    /**
     * Start read timer
     */
    private fun startReadTimer(inputUrl: String, title: String?) {
        if (true == title?.equals(getString(R.string.page_not_found))) {
            return
        }

        val normalizedUrl = RegexUtils.normalizeUrl(requireContext(), inputUrl)
        if (normalizedUrl.startsWith(getString(R.string.base_path))) {
            if (autoMarkReadHelper.isReadTimeEnabled() && fragmentState != FragmentState.DESTROYED) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    val stats = scpDataItemViewModel.getReadTimeByUrl(normalizedUrl).await()
                    if (null != stats) {
                        autoMarkReadHelper.onReadTimeElapsed(stats.readTimeSecs)
                    }
                }
            }

            monitorTimer.start(normalizedUrl, onElapsed = { url, elapsedSecs ->
                scpDataItemViewModel.viewModelScope.launch(Dispatchers.IO) {
                    val totalSecs = scpDataItemViewModel.addReadTime(url, elapsedSecs)
                    logv("Total secs (read time) for $url: $totalSecs; elapsed: $elapsedSecs")

                    withContext(Dispatchers.Main) {
                        if (fragmentState != FragmentState.DESTROYED) {
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                autoMarkReadHelper.onReadTimeElapsed(totalSecs)
                            }
                        }
                    }
                }
            })
        }
    }

    /**
     * Check if System WebView is stale (only applies to API 23 and below).
     */
    fun isStaleWebView(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            // Android N (API 24) and above already has up-to-date system webview
            return false
        }

        // If user doesn't want to be bothered by this, ignore
        val ignoreStaleWebView = sharedPreferences.getBoolean(
            getString(R.string.upgrade_stale_webview_checked_key),
            false
        )
        if (ignoreStaleWebView) {
            return false
        }

        // With Android API 23 and below, the system webview is updated as
        // a separate APK in the PlayStore. Old webview versions do not render some
        // SCP pages correctly. Check installed version and warn if an update is needed.
        try {
            @SuppressLint("PrivateApi")
            val webViewFactory = Class.forName("android.webkit.WebViewFactory")
            val method = webViewFactory.getMethod("getLoadedPackageInfo")
            val packageInfo = method.invoke(null) as PackageInfo?

            if (packageInfo != null) {
                if (!packageInfo.packageName.equals(googleWebViewPackageName)) {
                    logv("Stale webview package info: packageName: ${packageInfo?.packageName}, versionName: ${packageInfo?.versionName}")
                    return true
                }
            }
        } catch (e: Exception) {
            loge("Cannot access web view package info", e)
        }

        // We're up to date; remember this so we don't have to check again.
        with(sharedPreferences.edit()) {
            putBoolean(getString(R.string.upgrade_stale_webview_checked_key), true)
            apply()
        }

        return false
    }

    /**
     * Alert on stale System WebView version
     */
    fun alertOnStaleWebView() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.upgrade_stale_webview_title))
            .setMessage(getString(R.string.upgrade_stale_webview_message))
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int ->
                try {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=$googleWebViewPackageName")
                            )
                        )
                    } catch (activityNotFoundException: ActivityNotFoundException) {
                        // Google Play Store is not installed, fall back to URL
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=$googleWebViewPackageName")
                            )
                        )
                    }
                } catch (e: Exception) {
                    loge("Error trying to open Google Play store")
                }
            }
            .setNeutralButton(R.string.upgrade_stale_webview_ignore) { _: DialogInterface, _: Int ->
                with(sharedPreferences.edit()) {
                    putBoolean(getString(R.string.upgrade_stale_webview_checked_key), true)
                    apply()
                }
                requireActivity().invalidateOptionsMenu()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show()
    }

    /**
     * Show snackbar of page title when title is clicked
     */
    override fun onClick(v: View?) {
        val actionBar = (requireActivity() as AppCompatActivity).supportActionBar
        if (actionBar != null) {
            var fullTitle: String = actionBar.title.toString()
            if (actionBar.subtitle != null) {
                fullTitle = fullTitle + " - " + actionBar.subtitle
            }
            if (v != null) {
                showSnackbar(v, fullTitle)
            }
        }
    }

    companion object {
        const val googleWebViewPackageName = "com.google.android.webview"
    }
}