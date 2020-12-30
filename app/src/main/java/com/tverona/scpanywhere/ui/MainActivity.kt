package com.tverona.scpanywhere.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.*
import androidx.preference.PreferenceManager
import com.google.android.material.navigation.NavigationView
import com.squareup.moshi.JsonClass
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.downloader.RateLimitExceededException
import com.tverona.scpanywhere.downloader.StateData
import com.tverona.scpanywhere.utils.*
import com.tverona.scpanywhere.viewmodels.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject

/**
 * Main activity for the app
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity(), NavController.OnDestinationChangedListener,
    NavigationView.OnNavigationItemSelectedListener {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var drawerLayout: DrawerLayout

    @Inject
    lateinit var textToSpeechProvider: TextToSpeechProvider

    // View models
    private val webDataViewModel: WebDataViewModel by viewModels()
    private val scpDataViewModel: ScpDataViewModel by viewModels()
    private val offlineDataViewModel: OfflineDataViewModel by viewModels()
    private val textToSpeechViewModel: TextToSpeechViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        logv("onCreate")
        super.onCreate(savedInstanceState)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPreferenceListener)
        PreferenceManager.setDefaultValues(this, R.xml.root_preferences, false)

        // Set app theme
        val themeValue = sharedPreferences.getString(
            getString(R.string.theme_key),
            getString(R.string.theme_default)
        )
        ThemeUtil(this).initializeTheme(themeValue)

        setContentView(R.layout.activity_main)

        /*
        try {
            // Set up CloseGuard to log any leaked opens
            Class.forName("dalvik.system.CloseGuard")
                .getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                .invoke(null, true)
        } catch (e: ReflectiveOperationException) {
            logw("Cannot set CloseGuard")
        }*/

        // Set up action bar
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Set up navigation
        drawerLayout = findViewById(R.id.drawer_layout)
        val navView: NavigationView = findViewById(R.id.nav_view)
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        navController.addOnDestinationChangedListener(this)

        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_home,
                R.id.item_list,
                R.id.scpitem_list,
                R.id.read_list,
                R.id.favorites_list,
                R.id.search_scp_list,
                R.id.search_tale_list,
                R.id.stats
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
        navView.setNavigationItemSelectedListener(this)

        // Initialize text to speech provider
        initializeTextToSpeechProvider()

        // Observe & react to changes to offline mode
        var skipOnInstanceRestore = (savedInstanceState != null)
        webDataViewModel.offlineMode.observe(this) {
            if (skipOnInstanceRestore) {
                // If restoring instance from saved state, do not clear back stack or load launch page.
                // Web view fragment will handle this when its state is restored.
                skipOnInstanceRestore = false
            } else {
                // When changing offline mode, clear the backstack and re-load the launch page
                logv("Updating offline mode to ${webDataViewModel.offlineMode.value}")
                clearBackStack()
                loadLaunchPage()
            }
        }

        // Set up dynamic menu items
        scpDataViewModel.allBySeries.observe(this) {
            // todo: error checking
            loadMenuAsync(navView, drawerLayout)
        }

        // Observe any downloading / IO operations and present status via toasts
        offlineDataViewModel.operationState.observe(this) {
            when (it.status) {
                StateData.Status.ERROR -> it.data?.let { it1 ->
                    if (it.error != null && it.error is RateLimitExceededException) {
                        sendToast(getString(R.string.rateLimitExceeded))
                    } else {
                        sendToast(it1)
                    }
                }
                StateData.Status.SUCCESS -> it.data?.let { it1 -> sendToast(it1) }
                StateData.Status.UPDATE -> it.data?.let { it1 -> sendToast(it1) }
            }
        }

        // Load initial launch page, if we're not restoring a saved state (which would happen on orientation changes etc)
        if (savedInstanceState != null) {
            loadLaunchPage()
        }
    }

    override fun onDestroy() {
        logv("OnDestroy")
        super.onDestroy()

        // Shut down text to speech provider
        textToSpeechProvider.shutdown()

        // Unregister shared preference listener
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPreferenceListener)
    }

    /**
     * Gets last saved url (may be different between online vs offline modes)
     */
    private fun getLastUrl(): String {
        return if (webDataViewModel.offlineMode.value == true) {
            sharedPreferences.getString(
                getString(R.string.last_url_offline),
                getString(R.string.start_url_offline)
            )!!
        } else {
            sharedPreferences.getString(
                getString(R.string.last_url_online),
                getString(R.string.start_url_online)
            )!!
        }
    }

    /**
     * Loads launch page
     */
    private fun loadLaunchPage() {
        val lastUrl = getLastUrl()
        logv("Loading launch page. Last url: ${getLastUrl()}")
        webDataViewModel.url.value = lastUrl
    }

    /**
     * Sends a toast
     */
    private fun sendToast(message: String) {
        val t = Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        )
        t.show()
    }

    /**
     * Datat class for a menu item
     */
    @JsonClass(generateAdapter = true)
    data class JsonMenu(
        val name: String?,
        val url: String?,
        var source: String? = null,
        var heading: String? = null,
        val items: List<JsonMenu>? = null
    )

    /**
     * Loads a sub-menu item
     */
    private fun loadSubMenu(
        drawerLayout: DrawerLayout,
        menuItem: MenuItem,
        items: List<JsonMenu>
    ) {
        val listItem = arrayListOf<ListItem>()

        items.forEach { item ->
            if (null != item.name && null != item.url) {
                listItem.add(ListItem(item.name, item.url))
                logv("menu: added sub item: ${item.name}, ${item.url}")
            }
        }

        menuItem.setOnMenuItemClickListener {
            val args = Bundle()
            args.putString("title", menuItem.title.toString())
            args.putParcelableArrayList(ListItemFragment.listArgument, listItem)
            findNavController(R.id.nav_host_fragment).navigate(R.id.item_list, args)
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    /**
     * Handles dynamic menu data sources
     */
    private fun getMenuDataSource(source: String): List<JsonMenu> {
        if (source == "\$scp_series") {
            val list = scpDataViewModel.allBySeries.value?.keys?.map { seriesName ->
                val seriesNamePatternMatch = RegexUtils.seriesNamePattern.find(seriesName)
                var seriesValue = 1
                if (null != seriesNamePatternMatch && seriesNamePatternMatch.groups.size > 1) {
                    seriesValue = seriesNamePatternMatch.groups[1]?.value?.toIntOrNull() ?: 1
                }
                JsonMenu(
                    name = "Series ${seriesValue.toRomanNumeral() ?: ""}",
                    url = getString(R.string.base_path) + "/$seriesName"
                )
            }
                ?.sortedBy { it.name }

            return list ?: listOf<JsonMenu>()
        } else if (source == "\$tales_series") {
            val talesNum = scpDataViewModel.talesNum.value ?: 0
            val talesEntries = mutableListOf<JsonMenu>()
            if (talesNum > 0) {
                for (i in 1..talesNum) {
                    talesEntries.add(
                        JsonMenu(
                            name = "Series ${i.toRomanNumeral()} Tales",
                            url = getString(R.string.base_path) + "/scp-series-$talesNum-tales-edition"
                        )
                    )
                }
            }
            return talesEntries
        }

        return listOf<JsonMenu>()
    }

    /**
     * Generates dynamic navigation menu
     */
    private fun loadMenu(
        listMenu: List<JsonMenu>,
        navView: NavigationView,
        drawerLayout: DrawerLayout
    ) {
        navView.menu.removeGroup(MENU_DYNAMIC_GROUP_ID)
        val curMenu = navView.menu
        logv("menu: listMenu items: ${listMenu.size}")
        listMenu.forEach { elem ->
            val menuItems: List<JsonMenu>
            if (elem.source != null) {
                menuItems = getMenuDataSource(elem.source!!)
            } else if (null != elem.items) {
                menuItems = elem.items
            } else {
                menuItems = listOf()
            }

            menuItems.forEach { item ->
                if (item.name != null && item.url != null) {
                    logv("menu: ${item.name} ${item.url}")
                    val menuItem =
                        curMenu.add(MENU_DYNAMIC_GROUP_ID, Menu.NONE, Menu.NONE, item.name)
                    val seriesPattern = RegexUtils.seriesUrlPattern
                    val seriesPatternMatch = seriesPattern.find(item.url)
                    if (null != seriesPatternMatch && seriesPatternMatch.groups.size > 1) {
                        val seriesKey = seriesPatternMatch.groups[1]?.value
                        var seriesValue = 1
                        if (seriesPatternMatch.groups.size > 2) {
                            seriesValue =
                                seriesPatternMatch.groups[2]?.value?.toIntOrNull() ?: 1
                        }
                        logv("menu: match series: $seriesKey, $seriesValue")

                        menuItem.setOnMenuItemClickListener {
                            val args = Bundle()
                            args.putString("title", item.name)
                            args.putString(ScpListItemFragment.seriesKey, seriesKey)
                            findNavController(R.id.nav_host_fragment).navigate(
                                R.id.scpitem_list,
                                args
                            )
                            drawerLayout.closeDrawer(GravityCompat.START)
                            true
                        }
                    } else {
                        menuItem.setOnMenuItemClickListener {
                            logv("Setting url: ${item.url}")
                            webDataViewModel.url.value = item.url
                            drawerLayout.closeDrawer(GravityCompat.START)
                            true
                        }
                    }
                }
                if (item.heading != null) {
                    val menuItem = navView.menu.add(
                        MENU_DYNAMIC_GROUP_ID,
                        Menu.NONE,
                        Menu.NONE,
                        item.heading
                    )

                    val items = mutableListOf<JsonMenu>()
                    if (item.source != null) {
                        val sourceItems = getMenuDataSource(item.source!!)
                        items.addAll(sourceItems)
                    }
                    if (item.items != null) {
                        items.addAll(item.items)
                    }
                    loadSubMenu(drawerLayout, menuItem, items)
                }
            }
        }
    }

    private fun getOnlineMenuInputStream(): InputStream? {
        return resources.openRawResource(R.raw.site_menu)
    }

    private suspend fun getOfflineMenuInputStream(): InputStream? {
        offlineDataViewModel.zip.await()
        val offlineMenu = offlineDataViewModel.zip.value?.getInputStream(
            applicationContext.resources.getString(
                R.string.menu_jsonfile
            )
        )

        // If offline menu does not exist, fall back to resource menu
        if (null == offlineMenu) {
            return getOnlineMenuInputStream()
        } else {
            return offlineMenu
        }
    }

    /**
     * Asyn menu generation
     */
    private fun loadMenuAsync(
        navView: NavigationView,
        drawerLayout: DrawerLayout
    ) {
        navView.menu.removeGroup(MENU_DYNAMIC_GROUP_ID)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                //  Get either offline or online data
                val inputStream: InputStream?
                if (webDataViewModel.offlineMode.value == true) {
                    inputStream = getOfflineMenuInputStream()
                } else {
                    inputStream = getOnlineMenuInputStream()
                }

                if (null != inputStream) {
                    val listMenu = JsonList.loadJsonList<JsonMenu>(inputStream)
                    withContext(Dispatchers.Main) {
                        try {
                            // Load the menu
                            loadMenu(listMenu, navView, drawerLayout)
                        } catch (e: Exception) {
                            loge("Error loading menu", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * Determines if webview is currently the visible fragment
     */
    private fun getVisibleWebViewFragment(): WebViewFragment? {
        val navHostFragment: NavHostFragment? =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment?
        val fragment =
            navHostFragment?.childFragmentManager?.fragments?.firstOrNull { it.isVisible }
        return fragment as? WebViewFragment
    }

    /**
     * Navigates to selected item
     */
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.random -> {
                scpDataViewModel.allScpEntries.observeOnce(this) {
                    val entry = it.random().data as UrlEntryClickable
                    webDataViewModel.url.value = entry.urlEntry.url

                    val webViewFragment = getVisibleWebViewFragment()
                    if (webViewFragment == null) {
                        navController.navigate(R.id.nav_home)
                    }
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
                true
            }
            else -> {
                drawerLayout.closeDrawer(GravityCompat.START)
                NavigationUI.onNavDestinationSelected(item, navController)
            }
        }
    }

    /**
     * Inflate the menu; this adds items to the action bar
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    /**
     * Handle back presses
     */
    override fun onBackPressed() {
        val webViewFragment = getVisibleWebViewFragment()
        if (webViewFragment != null) {
            if (!webViewFragment.onBackPressed()) {
                super.onBackPressed()
            }
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Handle navigating up the navigation hierarchy
     */
    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: Bundle?
    ) {
        // Reset subtitle
        supportActionBar?.subtitle = null
    }

    /**
     * Initialize text to speech provider
     */
    private fun initializeTextToSpeechProvider(enginePackageName: String? = null) {
        var engine = enginePackageName
        if (null == engine) {
            engine = sharedPreferences.getString(
                getString(R.string.speech_engine_key),
                if (textToSpeechProvider.initialized) textToSpeechProvider.defaultEngine else null
            )
        }

        lifecycleScope.launch {
            textToSpeechProvider.initialize(engine, object : TextToSpeechProvider.OnInitStatus {
                override fun onSuccess() {
                    setTextToSpeechVoice()
                    setTextToSpeechPitch()
                    setTextToSpeechRate()
                    textToSpeechViewModel.initialized.postValue(true)
                }

                override fun onError(errorMessage: String) {
                    textToSpeechViewModel.initialized.postValue(false)
                }
            })
        }
    }

    /**
     * Sets text to speech voice
     */
    private fun setTextToSpeechVoice() {
        val voiceName = sharedPreferences.getString(
            getString(R.string.voice_key),
            textToSpeechProvider.voice?.name ?: textToSpeechProvider.defaultVoice?.name
        )

        if (voiceName.equals(textToSpeechProvider.voice?.name)) {
            return
        }

        val voice = textToSpeechProvider.voices.toList().filterNotNull()
            .distinctBy { it.locale.displayName }.filter {
            it.name.equals(
                voiceName
            )
        }.firstOrNull()

        logv("Set speech voice to $voiceName")
        textToSpeechProvider.voice = voice ?: textToSpeechProvider.defaultVoice
    }

    /**
     * Sets text to speech pitch
     */
    private fun setTextToSpeechPitch() {
        val pitch =
            sharedPreferences.getString(getString(R.string.pitch_key), "1.0")?.toFloatOrNull()
                ?: 1.0f

        logv("Set speech pitch to $pitch")
        textToSpeechProvider.setPitch(pitch)
    }

    /**
     * Sets text to speech rate
     */
    private fun setTextToSpeechRate() {
        val speechRate =
            sharedPreferences.getString(getString(R.string.speech_rate_key), "1.0")?.toFloatOrNull()
                ?: 1.0f
        logv("Set speech rate to $speechRate")
        textToSpeechProvider.setSpeechRate(speechRate)
    }

    private val sharedPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                getString(R.string.speech_engine_key) -> {
                    val enginePackageName = sharedPreferences.getString(
                        key,
                        textToSpeechProvider.defaultEngine
                    )

                    if (textToSpeechProvider.currentEngineName == null || !enginePackageName.equals(textToSpeechProvider.currentEngineName)) {
                            logv("Changing speech engine package to $enginePackageName")
                            initializeTextToSpeechProvider(enginePackageName)
                        }
                }
                getString(R.string.voice_key) -> {
                    setTextToSpeechVoice()
                }
                getString(R.string.pitch_key) -> {
                    setTextToSpeechPitch()
                }
                getString(R.string.speech_rate_key) -> {
                    setTextToSpeechRate()
                }
            }
        }

    private fun clearBackStack() {
        while (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        }

        // Another way to clear backstack:
        // while (navController.backStack.size > 2) {
        //    navController.popBackStack()
    }

    companion object {
        const val MENU_DYNAMIC_GROUP_ID = 0x100
    }
}