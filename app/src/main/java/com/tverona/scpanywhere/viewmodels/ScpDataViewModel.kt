package com.tverona.scpanywhere.viewmodels

import android.content.Context
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableInt
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.*
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.tverona.scpanywhere.BR
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.database.BookmarkEntry
import com.tverona.scpanywhere.database.StatEntry
import com.tverona.scpanywhere.recycleradapter.RecyclerItem
import com.tverona.scpanywhere.recycleradapter.RecyclerItemFilter
import com.tverona.scpanywhere.repositories.*
import com.tverona.scpanywhere.utils.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/**
 * View model that represents scp data - scp lists, bookmarks (read / favorite) & stats
 * Backed by offline & online repositories.
 */
class ScpDataViewModel @ViewModelInject constructor(
    private val offlineDataRepository: OfflineDataRepository,
    private val onlineDataRepository: OnlineDataRepositoryImpl,
    private val bookmarksRepository: BookmarksRepository,
    private val offlineModeRepository: OfflineModeRepository,
    private val statsRepository: StatsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _allScpEntries = MutableLiveData<List<RecyclerItem>>()
    val allScpEntries: LiveData<List<RecyclerItem>> = _allScpEntries

    private val _allTaleEntries = MutableLiveData<List<RecyclerItem>>()
    val allTaleEntries: LiveData<List<RecyclerItem>> = _allTaleEntries

    private val _allBySeries = MutableLiveData<Map<String, List<RecyclerItem>>>()
    val allBySeries: LiveData<Map<String, List<RecyclerItem>>> = _allBySeries

    private val _allRead = MutableLiveData<List<RecyclerItem>>()
    val allRead: LiveData<List<RecyclerItem>> = _allRead

    private val _readByUrl = MutableLiveData<Map<String, UrlEntry>>()
    val readByUrl: LiveData<Map<String, UrlEntry>> = _readByUrl

    private val _allFavorites = MutableLiveData<List<RecyclerItem>>()
    val allFavorites: LiveData<List<RecyclerItem>> = _allFavorites

    private val _favoriteByUrl = MutableLiveData<Map<String, UrlEntry>>()
    val favoriteByUrl: LiveData<Map<String, UrlEntry>> = _favoriteByUrl

    private val _taleEntriesByUrl = MutableLiveData<Map<String, UrlEntry>>()
    val taleEntriesByUrl: LiveData<Map<String, UrlEntry>> = _taleEntriesByUrl

    private val _scpEntriesByUrl = MutableLiveData<Map<String, UrlEntry>>()
    val scpEntriesByUrl: LiveData<Map<String, UrlEntry>> = _scpEntriesByUrl

    private val _onEntryClick = MutableLiveData<UrlEntry>()
    val onEntryClick: LiveData<UrlEntry> = _onEntryClick

    private val _talesNum = MutableLiveData<Int>()
    val talesNum: LiveData<Int> = _talesNum

    private var scpItemByUrl = HashMap<String, RecyclerItem>()
    private var taleItemByUrl = HashMap<String, RecyclerItem>()

    val seriesKey = MutableLiveData<String>()
    val seriesName = MutableLiveData<String>()

    val totalReadTimeSecs = statsRepository.totalReadTimeSecs

    val connectivityMonitor: ConnectivityMonitor

    override fun onCleared() {
        logv("onCleared")
        super.onCleared()

        try {
            bookmarksRepository.allBookmarks.removeObserver(bookmarkObserver)
            shouldRefreshScpList.removeObserver(refreshScpListObserver)
            offlineDataRepository.zipResourceFile.removeObserver(offlineDataObserver)
            offlineModeRepository.offlineMode.removeObserver(offlineDataObserver)
            ProcessLifecycleOwner.get().lifecycle.removeObserver(connectivityMonitor)
        } catch (e: Exception) {
            loge("Error removing observer", e)
        }
    }

    /**
     * Get offline scp list
     */
    private fun getOfflineScpList(): List<UrlEntry> {
        logv("getting offline scp list")

        try {
            val inputStream =
                offlineDataRepository.zipResourceFile.value?.getInputStream(
                    context.resources.getString(
                        R.string.scp_list_jsonfile
                    )
                )

            if (null != inputStream) {
                val scpList = UrlEntry.linkEntries(JsonList.loadJsonList<UrlEntry>(inputStream))
                // JSoup, used in online mode, unescapes certain characters. Do this here to match the behavior.
                scpList.map {
                    it.title = it.title.replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&#039;", "'")
                }
                return scpList
            }
        } catch (e: Exception) {
            loge("Failed to get offline scp list", e)
        }

        return listOf()
    }

    /**
     * Get offline tales list
     */
    private fun getOfflineTaleList(): List<UrlEntry> {
        logv("getting offline tale list")

        try {
            val inputStream =
                offlineDataRepository.zipResourceFile.value?.getInputStream(
                    context.resources.getString(
                        R.string.tale_list_jsonfile
                    )
                )

            if (null != inputStream) {
                val talesList = JsonList.loadJsonList<UrlEntry>(inputStream).sortedBy { it.title }
                // JSoup, used in online mode, unescapes certain characters. Do this here to match the behavior.
                talesList.map {
                    it.title = it.title.replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&#039;", "'")
                }
                return talesList
            }
        } catch (e: Exception) {
            loge("Failed to get offline tale list", e)
        }

        return listOf()
    }

    @JsonClass(generateAdapter = true)
    data class TalesNum(
        val talesnum: Int?
    )

    /**
     * Get offline number of tale series
     */
    private suspend fun getOfflineTalesNum(): Int {
        logv("getting offline tales num")

        try {
            val inputStream =
                offlineDataRepository.zipResourceFile.value?.getInputStream(
                    context.resources.getString(
                        R.string.tales_jsonfile
                    )
                ) ?: return 0

            val adapter = Moshi.Builder().build().adapter(TalesNum::class.java)

            inputStream.source().buffer().use {
                val talesNum = adapter.fromJson(it)
                return talesNum?.talesnum ?: 0
            }
        } catch (e: Exception) {
            loge("Error processing offline tales num", e)
        }

        return 0
    }

    /**
     * Get online scp list
     */
    private suspend fun getOnlineScpList(): List<UrlEntry> {
        logv("getting online scp list")
        return onlineDataRepository.getScpEntries()
    }

    /**
     * Get online tales list
     */
    private suspend fun getOnlineTaleList(): List<UrlEntry> {
        logv("getting online tale list")
        return onlineDataRepository.getTaleEntries()
    }

    /**
     * Get online number of tale series
     */
    private suspend fun getOnlineTalesNum(): Int {
        return onlineDataRepository.getTalesNum()
    }

    /**
     * Sets up scp data (based on either online or offline modes)
     */
    private suspend fun processScpData(
        urlList: List<UrlEntry>,
        talesList: List<UrlEntry>,
        talesNum: Int
    ) {
        logv("Got ${urlList.size} scp entries")

        val itemsBySeries =
            HashMap<String, MutableList<RecyclerItem>>().withDefault { arrayListOf() }
        scpItemByUrl = HashMap()
        taleItemByUrl = HashMap()
        val scpEntriesByUrl = HashMap<String, UrlEntry>()
        val taleEntriesByUrl = HashMap<String, UrlEntry>()
        val items = ArrayList<RecyclerItem>()

        // Set up scp list
        urlList.forEach {
            it.url = RegexUtils.normalizeUrl(context, it.url)
            val e = createListItemClickable(it).toRecyclerItem()
            items.add(e)

            if (!itemsBySeries.containsKey(it.series)) {
                itemsBySeries[it.series!!] = arrayListOf()
            }
            itemsBySeries[it.series]?.add(e)
            scpItemByUrl[it.url] = e
            scpEntriesByUrl[it.url] = it
        }

        // Set up tales list
        val tales = talesList.map {
            it.url = RegexUtils.normalizeUrl(context, it.url)
            val recyclerItem = createListItemClickable(it).toRecyclerItem()
            taleItemByUrl[it.url] = recyclerItem
            taleEntriesByUrl[it.url] = it
            recyclerItem
        }

        _allScpEntries.postValue(items)
        _allBySeries.postValue(itemsBySeries)
        _scpEntriesByUrl.postValue(scpEntriesByUrl)
        _taleEntriesByUrl.postValue(taleEntriesByUrl)
        _talesNum.postValue(talesNum)
        _allTaleEntries.postValue(tales)

        // Set up bookmarks
        withContext(Dispatchers.Main) {
            bookmarksRepository.allBookmarks.observeForever(bookmarkObserver)
        }
    }

    private val shouldRefreshScpList = MutableLiveData<Boolean>(false)

    /**
     * Check whether we need to refresh data
     */
    private suspend fun checkScpDataNeedsRefresh(): Long? {
        val curTime = System.currentTimeMillis()
        val lastUpdate = onlineDataRepository.getLastUpdate()
        logv(
            "Checking if data needs to be refreshed. last update: ${
                StringFormatter.dateFromMillis(
                    lastUpdate
                )
            }"
        )
        if (curTime - lastUpdate > context.resources.getInteger(R.integer.online_scp_list_refresh_seconds) * 1000) {
            logv("Need to refresh data. Current time: $curTime, last update: $lastUpdate")
            return curTime
        } else {
            logv("No need to refresh data. Current time: $curTime, last update: $lastUpdate")
        }

        return null
    }

    private var refreshLock = Mutex()

    private fun observeRefreshScpData() {
        shouldRefreshScpList.observeForever(refreshScpListObserver)
    }

    val refreshScpListObserver = Observer<Boolean> {
        ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch {
            withContext(Dispatchers.IO) {
                refreshLock.withLock {
                    val time = checkScpDataNeedsRefresh()
                    if (false == offlineModeRepository.offlineMode.value && null != time) {
                        onlineDataRepository.refresh(time)
                        processScpData(
                            onlineDataRepository.getScpEntries(),
                            onlineDataRepository.getTaleEntries(),
                            onlineDataRepository.getTalesNum()
                        )
                    }
                }
            }
        }
    }

    val bookmarkObserver =
        Observer<List<BookmarkEntry>> { bookmarks ->
            if (null != bookmarks) {
                val scpReadEntries = mutableListOf<RecyclerItem>()
                val scpFavoriteEntries = mutableListOf<RecyclerItem>()
                val readEntries = HashMap<String, UrlEntry>()
                val favoriteEntries = HashMap<String, UrlEntry>()
                for (bookmark in bookmarks) {
                    try {
                        var urlEntryRecyclerItem: RecyclerItem?
                        var urlEntryClickable: UrlEntryClickable?
                        if (scpItemByUrl.containsKey(bookmark.url)) {
                            urlEntryRecyclerItem =
                                scpItemByUrl[bookmark.url]!!
                            urlEntryClickable =
                                urlEntryRecyclerItem.data as UrlEntryClickable
                        } else if (taleEntriesByUrl.value?.containsKey(bookmark.url) == true) {
                            urlEntryRecyclerItem =
                                taleItemByUrl[bookmark.url]!!
                            urlEntryClickable =
                                urlEntryRecyclerItem.data as UrlEntryClickable
                        } else {
                            val otherEntry = UrlEntry(
                                url = bookmark.url,
                                title = bookmark.title,
                                rating = null,
                                name = null,
                                series = null
                            )
                            urlEntryClickable =
                                createListItemClickable(otherEntry)
                            urlEntryRecyclerItem =
                                urlEntryClickable.toRecyclerItem()
                        }

                        urlEntryClickable.isRead.set(bookmark.read)
                        urlEntryClickable.isFavorite.set(bookmark.favorite)

                        if (bookmark.read) {
                            scpReadEntries.add(urlEntryRecyclerItem)
                            readEntries[bookmark.url] =
                                urlEntryClickable.urlEntry
                        }
                        if (bookmark.favorite) {
                            scpFavoriteEntries.add(urlEntryRecyclerItem)
                            favoriteEntries[bookmark.url] =
                                urlEntryClickable.urlEntry
                        }
                    } catch (e: Exception) {
                        loge("Exception enumerating bookmark ${bookmark.url}", e);
                    }
                }

                _allRead.postValue(scpReadEntries)
                _allFavorites.postValue(scpFavoriteEntries)
                _readByUrl.postValue(readEntries)
                _favoriteByUrl.postValue(favoriteEntries)
            }
        }

    val offlineDataObserver =
        Observer<Any> {
            logv("scpviewmodel - offline mode is ${offlineModeRepository.offlineMode.value}")
            ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    refreshLock.withLock {
                        val urlList: List<UrlEntry>
                        val taleList: List<UrlEntry>
                        val talesNum: Int
                        if (true == offlineModeRepository.offlineMode.value) {
                            urlList = getOfflineScpList()
                            taleList = getOfflineTaleList()
                            talesNum = getOfflineTalesNum()
                        } else {
                            urlList = getOnlineScpList()
                            taleList = getOnlineTaleList()
                            talesNum = getOnlineTalesNum()
                        }

                        processScpData(urlList, taleList, talesNum)
                    }
                }
            }
        }


    init {
        logv("initializing viewmodel")

        offlineDataRepository.zipResourceFile.observeForever(offlineDataObserver)
        offlineModeRepository.offlineMode.observeForever(offlineDataObserver)

        observeRefreshScpData()
        connectivityMonitor = ConnectivityMonitor(context) { isConnected ->
            logv("Network connectivity is $isConnected")
            if (isConnected) {
                shouldRefreshScpList.postValue(true)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(connectivityMonitor)
    }

    fun updateBookmarkEntry(bookmarkEntry: BookmarkEntry) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            bookmarksRepository.deleteByUrl(bookmarkEntry.url)

            if (bookmarkEntry.favorite || bookmarkEntry.read) {
                bookmarksRepository.insert(bookmarkEntry)
            }
        }
    }

    suspend fun addReadTime(url: String, readTimeSecs: Long): Long = withContext(Dispatchers.IO) {
        return@withContext statsRepository.addReadTime(url, readTimeSecs)
    }

    fun getReadTimeByUrl(url: String): LiveData<StatEntry?> = statsRepository.getByUrl(url)

    private fun onBookmarkClickEntry(urlEntryClickable: UrlEntryClickable) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val bookmarkEntry = BookmarkEntry(
                url = urlEntryClickable.urlEntry.url,
                title = urlEntryClickable.urlEntry.title,
                read = urlEntryClickable.isRead.get(),
                favorite = urlEntryClickable.isFavorite.get()
            )
            bookmarksRepository.deleteByUrl(bookmarkEntry.url)

            if (bookmarkEntry.favorite || bookmarkEntry.read) {
                bookmarksRepository.insert(bookmarkEntry)
            }
        }
    }

    fun getBookmarkByUrl(url: String) = bookmarksRepository.getByUrl(url)

    @JsonClass(generateAdapter = true)
    data class ExportData(val bookmarks: List<BookmarkEntry>)

    suspend fun exportBookmarksAsJson(): String {
        val json: String
        withContext(Dispatchers.IO) {
            val list = bookmarksRepository.allBookmarks.await()
            val moshi = Moshi.Builder().build()
            val jsonAdapter =
                moshi.adapter(ExportData::class.java).indent("  ")

            json = jsonAdapter.toJson(ExportData(list))
            return@withContext json
        }

        return json
    }

    suspend fun importBookmarksFromJson(json: String) {
        withContext(Dispatchers.IO) {
            val list = bookmarksRepository.allBookmarks.await()
            val moshi = Moshi.Builder().build()
            val jsonAdapter =
                moshi.adapter(ExportData::class.java)

            val bookmarks: ExportData? = jsonAdapter.fromJson(json)
            if (null != bookmarks) {
                bookmarks.bookmarks.forEach {
                    val url = RegexUtils.normalizeUrl(context, it.url)
                    if (url.startsWith(context.getString(R.string.base_path))) {
                        var bookmark = getBookmarkByUrl(url).await()
                        if (null != bookmark) {
                            // If existing bookmark, update read / favorite values
                            bookmark.favorite = bookmark.favorite || it.favorite
                            bookmark.read = bookmark.read || it.read
                            logv("Updating existing bookmark: $bookmark")
                        } else {
                            // Add new bookmark
                            bookmark = it
                            logv("Importing new bookmark: $bookmark")
                        }

                        updateBookmarkEntry(bookmark)
                    }
                }
            }
        }
    }

    private fun createListItemClickable(urlEntry: UrlEntry): UrlEntryClickable {
        return UrlEntryClickable(urlEntry).apply {
            clickHandler = { entry -> onClickEntry(entry) }
            clickBookmarkHandler = { entry -> onBookmarkClickEntry(this) }
        }
    }

    private fun onClickEntry(urlEntry: UrlEntry) {
        _onEntryClick.value = urlEntry
    }

    fun clearOnClickEntry() {
        _onEntryClick.value = null
    }

    private fun UrlEntryClickable.toRecyclerItem() = RecyclerItem(
        data = this,
        layoutId = R.layout.urllist_item,
        variableId = BR.urlitem
    )
}

class UrlEntryClickable(val urlEntry: UrlEntry) : RecyclerItemFilter {
    lateinit var clickHandler: (urlEntry: UrlEntry) -> Unit
    lateinit var clickBookmarkHandler: (urlEntry: UrlEntry) -> Unit

    fun onClick() {
        clickHandler(urlEntry)
    }

    fun onReadClick() {
        isRead.set(!isRead.get())
        clickBookmarkHandler(urlEntry)
    }

    fun onFavoriteClick() {
        isFavorite.set(!isFavorite.get())
        clickBookmarkHandler(urlEntry)
    }

    var isRead = ObservableBoolean(false)
    var isFavorite = ObservableBoolean(false)

    val readIcon: ObservableInt = object : ObservableInt(isRead) {
        override fun get(): Int {
            if (isRead.get()) {
                return R.drawable.baseline_bookmark_24
            } else {
                return R.drawable.baseline_bookmark_border_24
            }
        }
    }

    val favoriteIcon: ObservableInt = object : ObservableInt(isFavorite) {
        override fun get(): Int {
            if (isFavorite.get()) {
                return R.drawable.baseline_favorite_24
            } else {
                return R.drawable.baseline_favorite_border_24
            }
        }
    }

    override fun filter(value: String): Boolean {
        val escapedValue = value.replace("<", "&lt;")
            .replace(">", "&gt;")
        return urlEntry.title.contains(escapedValue, ignoreCase = true)
    }
}

class UrlEntryComparator {
    companion object : Comparator<RecyclerItem> {
        override fun compare(item1: RecyclerItem, item2: RecyclerItem): Int {
            val entry1 = (item1.data as UrlEntryClickable).urlEntry
            val entry2 = (item2.data as UrlEntryClickable).urlEntry

            val num = entry1.num - entry2.num
            if (num != 0) {
                return num
            }
            return entry1.title.compareTo(entry2.title)
        }
    }
}
