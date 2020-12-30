package com.tverona.scpanywhere.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.view.*
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.*
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewAssetLoader
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.database.BookmarkEntry
import com.tverona.scpanywhere.pathhandlers.*
import com.tverona.scpanywhere.utils.*
import com.tverona.scpanywhere.viewmodels.*
import com.tverona.scpanywhere.zipresource.ZipResourceFile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private var isSpeaking = false
    private var lastUtteranceId: Int? = null

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
    private val textToSpeechViewModel: TextToSpeechViewModel by activityViewModels()
    private lateinit var textToSpeechContentViewModel: TextToSpeechContentViewModel

    // Current url & title
    private var currentUrlTitle = MutableLiveData<UrlTitle>()

    private lateinit var sharedPreferences: SharedPreferences

    // Reading timer
    private var readTimer = MutableLiveData<ReadTimer>()

    private enum class ReadTimerAction {
        START,
        PULSE,
        STOP,
    }

    private data class ReadTimer(val action: ReadTimerAction, val url: String?)

    class UrlTitle(var url: String, var title: String, var subTitle: String?)

    class TextToSpeechContentViewModel : ViewModel() {
        val textToSpeechContent = LiveEvent<String?>()
    }

    /**
     * Javascript callback, used to get page content when text-to-speech is turned on
     */
    private class JavaScriptInterface(val textToSpeechContentViewModel: TextToSpeechContentViewModel) {
        @JavascriptInterface
        fun handleHtml(html: String?) {
            val doc = Jsoup.parse(html)
            doc.select("div.footer-wikiwalk-nav").remove()
            doc.select("div.page-tags").remove()
            val content = doc.wholeText()
            textToSpeechContentViewModel.textToSpeechContent.postValue(content)
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

        // Start to monitor read time
        monitorReadTime()

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        textToSpeechContentViewModel =
            ViewModelProvider(this).get(TextToSpeechContentViewModel::class.java)

        webViewInitialized = false
        webView = root.findViewById(R.id.webview)
        webView.addJavascriptInterface(
            JavaScriptInterface(textToSpeechContentViewModel),
            "HtmlHandler"
        )

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
    private fun getContentForSpeech() {
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
        stopSpeak(resetUtteranceId = true)
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
        super.onResume()
    }

    override fun onPause() {
        logv("onPause")
        fragmentState = FragmentState.PAUSED
        webViewBundle = Bundle()
        webView.saveState(webViewBundle!!)
        readTimer.value = ReadTimer(ReadTimerAction.STOP, null)
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

            // Enable text to speech button when text to speech provider is initialized
            textToSpeechViewModel.initialized.observe(viewLifecycleOwner) {
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
                } else {
                    readItem.icon = ContextCompat.getDrawable(
                        requireActivity(),
                        R.drawable.baseline_bookmark_border_24
                    )
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
                } else {
                    favoriteItem.icon = ContextCompat.getDrawable(
                        requireActivity(),
                        R.drawable.baseline_favorite_border_24
                    )
                }
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val playItem = menu.findItem(R.id.play)
        if (isSpeaking) {
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.favorite,
            R.id.read -> {
                // Update bookmark entry
                currentUrlTitle.observeOnce(viewLifecycleOwner) { urlTitle ->
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
                        if (item.itemId == R.id.favorite) {
                            bookmarkEntry.favorite = !bookmarkEntry.favorite
                        }
                        if (item.itemId == R.id.read) {
                            bookmarkEntry.read = !bookmarkEntry.read
                        }
                        logv("dao: updating entry: $bookmarkEntry")
                        scpDataItemViewModel.updateBookmarkEntry(bookmarkEntry)
                    }
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
                                webDataViewModel.url.value = nextEntry.url
                            }
                        }
                    }
                }
                true
            }
            R.id.play -> {
                // Start / stop speaking
                if (isSpeaking) {
                    stopSpeak(resetUtteranceId = false)
                } else {
                    startSpeak()
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

            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Start speaking current page content
     */
    private fun startSpeak() {
        if (isSpeaking) {
            return
        }

        isSpeaking = true
        requireActivity().invalidateOptionsMenu()

        getContentForSpeech()
        textToSpeechContentViewModel.textToSpeechContent.observeOnce(viewLifecycleOwner) {
            if (null != it) {
                speak(it)
            }
        }
    }

    /**
     * Stop speaking
     */
    private fun stopSpeak(resetUtteranceId: Boolean) {
        textToSpeechProvider.stop()
        isSpeaking = false

        if (resetUtteranceId) {
            logv("Reset utterance")
            lastUtteranceId = null
        }

        if (fragmentState != FragmentState.DESTROYED) {
            requireActivity().invalidateOptionsMenu()
        }
    }

    /**
     * Speak content provided by [content]
     */
    private fun speak(content: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            textToSpeechProvider.speak(
                content,
                lastUtteranceId,
                object : TextToSpeechProvider.SpeechProgress {
                    override fun onStart(totalUtterances: Int) {
                    }

                    override fun onUtteranceDone(utteranceId: Int) {
                        logv("onUtteranceDone: $utteranceId")
                        lastUtteranceId = utteranceId
                    }

                    override fun onDone() {
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(Dispatchers.Main) {
                                stopSpeak(resetUtteranceId = true)
                            }
                        }
                    }

                    override fun onError(utteranceId: Int) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(Dispatchers.Main) {
                                stopSpeak(resetUtteranceId = false)
                            }
                        }
                    }
                })
        }
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
        doc.title("404 - Page not found")

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
                ZipStoragePathHandler(zipResourceFile)
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
                OnlinePathHandler(requireContext(), urlListByUrl),
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
                clearTitle(getString(R.string.loading))
                stopSpeak(resetUtteranceId = true)
                readTimer.value = ReadTimer(ReadTimerAction.STOP, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val normalizedUrl = RegexUtils.normalizeUrl(requireContext(), url)
                if (normalizedUrl.startsWith(getString(R.string.base_path))) {
                    readTimer.value = ReadTimer(ReadTimerAction.START, url)
                }

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
     * Monitor read time, periodically persisting to disk
     */
    private fun monitorReadTime() {
        // Background coroutine to periodically pulse the read timer
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(60000L)
                readTimer.value = ReadTimer(ReadTimerAction.PULSE, null)
            }
        }

        var lastUrl: String? = null
        val stopWatch = StopWatch()

        // Observe the read timer:
        // On start, save off the url & start the stop watch.
        // On pulse, persist current elapsed timed & reset the stop watch.
        // On stop, persist current elapsed time & stop the stop watch.
        readTimer.observe(viewLifecycleOwner) {
            when (it.action) {
                ReadTimerAction.START -> {
                    stopWatch.start()
                    lastUrl = it.url
                }
                ReadTimerAction.PULSE -> {
                    val elapsedSecs = stopWatch.stop() / 1000
                    stopWatch.start()

                    if (lastUrl != null) {
                        scpDataItemViewModel.addReadTime(lastUrl!!, elapsedSecs)
                    }
                }
                ReadTimerAction.STOP -> {
                    val elapsedSecs = stopWatch.stop() / 1000
                    if (lastUrl != null) {
                        scpDataItemViewModel.addReadTime(lastUrl!!, elapsedSecs)
                    }
                    lastUrl = null
                }
            }
        }
    }

    /**
     * Show toast of page title when title is clicked
     */
    override fun onClick(v: View?) {
        val actionBar = (requireActivity() as AppCompatActivity).supportActionBar
        if (actionBar != null) {
            var fullTitle: String = actionBar.title.toString()
            if (actionBar.subtitle != null) {
                fullTitle = fullTitle + " - " + actionBar.subtitle
            }
            val t = Toast.makeText(
                context,
                fullTitle,
                Toast.LENGTH_SHORT
            )
            t.show()
        }
    }
}