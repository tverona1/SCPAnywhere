package com.tverona.scpanywhere.repositories

import android.content.Context
import android.os.Environment
import androidx.lifecycle.*
import androidx.preference.PreferenceManager
import androidx.work.*
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.downloader.*
import com.tverona.scpanywhere.repositories.OfflineDataRepository.Companion.tmpExt
import com.tverona.scpanywhere.repositories.OfflineDataRepository.Companion.zipExt
import com.tverona.scpanywhere.utils.*
import com.tverona.scpanywhere.worker.ChangeStorageWorker
import com.tverona.scpanywhere.worker.DownloadWorker
import com.tverona.scpanywhere.zipresource.ZipResourceFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.*

/**
 * Implementation of offline data repository, backed by zip files
 */
class OfflineDataRepositoryImpl constructor(
    private val context: Context,
    private val githubReleaseDownloader: GithubReleaseDownloader
) : OfflineDataRepository {
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var defaultExternalDirIndex = 0

    override val operationState = StateLiveData<String>()

    private val workManager = WorkManager.getInstance(context)

    // Zip resource file
    private val _zipResourceFile = MutableLiveData<ZipResourceFile>()
    override val zipResourceFile = _zipResourceFile

    // List of available external storage locations
    private val _externalStorageList = MutableLiveData<List<ExternalStorageMetadata>>()
    override val externalStorageList = _externalStorageList

    // Current external storage location
    private val _currentExternalStorage = MutableLiveData<ExternalStorageMetadata>()
    override val currentExternalStorage = _currentExternalStorage

    private val _changedStorageLocation = MutableLiveData<Unit>()
    override val changedStorageLocation: LiveData<Unit> = _changedStorageLocation

    private val localFilesToDelete = mutableListOf<String>()

    private val localMatchingItems = MutableLiveData<List<DownloadAssetMetadata>>()

    private val _onDownloadableItemClick = MutableLiveData<DownloadAssetMetadata>()
    override val onDownloadableClick: LiveData<DownloadAssetMetadata> = _onDownloadableItemClick

    private val _onDeleteItemClick = MutableLiveData<LocalAssetMetadata>()
    override val onDeleteClick: LiveData<LocalAssetMetadata> = _onDeleteItemClick

    private val _localItemClick = MutableLiveData<LocalAssetMetadata>()
    override val onLocalItemClick: LiveData<LocalAssetMetadata> = _localItemClick

    // Downloadable items
    private val _downloadableItems = MutableLiveData<List<DownloadAssetMetadataObservable>>()
    override val downloadableItems: LiveData<List<DownloadAssetMetadataObservable>> = _downloadableItems
    private val downloadableItemsByName = HashMap<String, DownloadAssetMetadataObservable>()

    // Local (on-disk) items & their sizes
    private val _localItems = MutableLiveData<List<LocalAssetMetadataObservable>>()
    override val localItems: LiveData<List<LocalAssetMetadataObservable>> = _localItems
    private val _localItemsSize = MutableLiveData<Long>(0)
    override val localItemsSize: LiveData<Long> = _localItemsSize

    private val _isChangingStorage = MutableLiveData<Boolean>(false)
    override val isChangingStorage : LiveData<Boolean> = _isChangingStorage

    // Download size required
    private val _downloadSizeDelta = MutableLiveData<Long>()
    override val downloadSizeDelta: LiveData<Long> = _downloadSizeDelta

    // Usable space
    private val _usableSpace = MutableLiveData<Long>()
    override val usableSpace: LiveData<Long> = _usableSpace

    // Required space
    private val _requiredSpace = MutableLiveData<Long>()
    override val requiredSpace: LiveData<Long> = _requiredSpace

    private val _isDownloading = MutableLiveData<Boolean>(false)
    override val isDownloading : LiveData<Boolean> = _isDownloading

    // Cached release metadata to avoid hitting quota
    private var cachedReleaseDownloadableAssets: List<DownloadAssetMetadata>? = null

    init {
        ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch {
            populateStorageEntries()

            // Load zip files
            load()

            withContext(Dispatchers.Main) {
                workManager.pruneWork()
                handleDownloadWork()
                handleChangeStorageWork()
            }
        }
    }

    /**
     * Get current storage location based on saved preferences
     */
    private suspend fun getStorageLocation(): String {
        return sharedPreferences.getString(
            context.getString(R.string.storage_key),
            externalStorageList.await().get(defaultExternalDirIndex).path
        )!!
    }

    /**
     * Get storage location metadata for the given [defaultPath]
     */
    private suspend fun getStorageMetadata(defaultPath: String): ExternalStorageMetadata {
        val path = sharedPreferences.getString(
            context.getString(R.string.storage_key), defaultPath
        )!!
        val storageMetadata = _externalStorageList.await().firstOrNull {
            it.path.equals(path, ignoreCase = true)
        }

        if (null == storageMetadata) {
            logw("Storage path not found: $path")
            return ExternalStorageMetadata(name = "Unknown", path = path, usableSpace = 0)
        } else {
            return storageMetadata
        }
    }

    /**
     * Saves current external storage location in preference
     */
    override suspend fun setStorageLocation(path: String) {
        with(sharedPreferences.edit()) {
            putString(context.getString(R.string.storage_key), path)
            apply()
        }

        _currentExternalStorage.postValue(getStorageMetadata(path))
    }

    /**
     * Attempt to clean up any temporary files
     */
    override suspend fun cleanupTempFiles() {
        externalStorageList.await().forEach {
            val path = it.path
            val tempFiles = getFiles(path, tmpExt)
            tempFiles.forEach { it ->
                try {
                    val tempFile = File(it.path)
                    tempFile.truncateAndDelete()
                } catch (e: Exception) {
                    loge("Error deleting file: ${it.path}", e)
                }
            }
        }
    }

    /**
     * Returns list of local (on-disk) zip files
     */
    override suspend fun getLocal(): Array<LocalAssetMetadata> =
        getFiles(getStorageLocation(), zipExt)

    /**
     * Returns list of local (on-disk) temp files
     */
    override suspend fun getLocalTemp(): Array<LocalAssetMetadata> =
        getFiles(getStorageLocation(), tmpExt)

    /**
     * Loads zip files
     */
    override suspend fun load() {
        try {
            val onDiskAssets = getLocal()
            _zipResourceFile.value?.close()
            _zipResourceFile.postValue(loadZipFiles(onDiskAssets))
        } catch (e: Exception) {
            loge("Failed to load assets", e)
        }
    }

    /**
     * Gets files at path [path], filtered by extention [ext]
     */
    override suspend fun getFiles(path: String, ext: String): Array<LocalAssetMetadata> =
        withContext(Dispatchers.IO) {
            logd("Storage dir: " + path)
            val assets: MutableList<LocalAssetMetadata> = mutableListOf()

            try {
                for (file in File(path).listFiles()) {
                    if (!file.name.endsWith(ext)) {
                        continue
                    }

                    assets.add(
                        LocalAssetMetadata(
                            name = file.name,
                            size = file.length(),
                            path = file.absolutePath
                        )
                    )
                }
                assets.sortBy { it.name }
            } catch (e: Exception) {
                loge("Error enumerating path $path", e)
            }

            return@withContext assets.toTypedArray()
        }

    /**
     * Gets list of available storage locations (including external SD cards)
     */
    override suspend fun populateStorageEntries() {
        val externalFilesDirs = context.getExternalFilesDirs(null)
        var internalStorageNum = 0
        var externalStorageNum = 0
        var index = 0
        var default: Int? = null

        val list = externalFilesDirs.map {
            var pathName: String
            if (!Environment.isExternalStorageEmulated(it)) {
                pathName = context.getString(R.string.external_storage)
                if (null == default || 0 == externalStorageNum) {
                    default = index
                }
                if (externalStorageNum > 0) {
                    pathName += " #$externalStorageNum"
                }
                externalStorageNum++
            } else {
                pathName = context.getString(R.string.internal_storage)
                if (null == default) {
                    default = index
                }
                if (internalStorageNum > 0) {
                    pathName += " #$internalStorageNum"
                }
                internalStorageNum++
            }
            index++

            val entry = ExternalStorageMetadata(
                name = "$pathName (${context.getString(R.string.free_space)}: ${
                    StringFormatter.fileSize(
                        it.usableSpace
                    )
                })",
                path = it.absolutePath,
                usableSpace = it.usableSpace
            )
            logv("Storage entry: $entry")
            entry
        }

        // Default storage to external, else fall back to internal
        defaultExternalDirIndex = default ?: 0
        _externalStorageList.postValue(list)
        _currentExternalStorage.postValue(getStorageMetadata(list[defaultExternalDirIndex].path))
    }

    /**
     * Download assets
     */
    @ExperimentalCoroutinesApi
    override fun download() {
        // If we're currently downloading, return
        if (isDownloading.value == true) {
            logv("Already downloading")
            return
        }

        if (downloadSizeDelta.value!! < 0) {
            logv("Not enough space")
            return
        }

        val assetsToDownload = downloadableItems.value.orEmpty()
            .filter { it.shouldDownload.get() && !it.isDownloading.get()!! }

        if (assetsToDownload.isNotEmpty()) {
            downloadAssetsAsync(assetsToDownload)
        }
    }

    /**
     * Cancel downloading
     */
    override fun cancelDownload() {
        workManager.cancelAllWorkByTag(DownloadWorker.DOWNLOAD_WORKER_TAG)
    }

    /**
     * Delete specified asset
     */
    override fun deleteLocalAsset(asset: LocalAssetMetadata) {
        ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch {
            deleteFile(asset.path)

            // Refresh local items
            getLocalAssetsScoped()
        }
    }

    /**
     * Cancel changing storage location
     */
    override fun cancelChangeStorage() {
        workManager.cancelAllWorkByTag(ChangeStorageWorker.CHANGESTORAGE_WORKER_TAG)
    }

    override suspend fun changeStorageLocation(sourcePath: String, destPath: String) {
        // Cancel any in-flight download or change storage operations
        cancelDownload()
        cancelChangeStorage()

        operationState.postUpdate("Changing storage location")

        val changeStorageData = workDataOf(
            ChangeStorageWorker.KEY_SOURCE_PATH to sourcePath,
            ChangeStorageWorker.KEY_DEST_PATH to destPath
        )

        val request =
            OneTimeWorkRequestBuilder<ChangeStorageWorker>()
                .setInputData(changeStorageData)
                .addTag(ChangeStorageWorker.CHANGESTORAGE_WORKER_TAG)
                .build()

        // Enqueue work
        workManager.enqueueUniqueWork(
            ChangeStorageWorker.CHANGESTORAGE_WORKER_TAG,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Async download assets
     */
    @ExperimentalCoroutinesApi
    private fun downloadAssetsAsync(assetsToDownload: List<DownloadAssetMetadataObservable>) = ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch {
        // Cancel any in-flight change storage operation
        cancelChangeStorage()

        operationState.postUpdate("Downloading ${assetsToDownload.size} items")

        // Nuke any local-only files
        deleteLocalOnlyFiles()

        //val
        val downloadData = workDataOf(
            DownloadWorker.KEY_STORAGE_DIR to currentExternalStorage.await().path,
            DownloadWorker.KEY_URLS to assetsToDownload.map { it.asset.url }.toTypedArray(),
            DownloadWorker.KEY_FILE_NAMES to assetsToDownload.map { it.asset.name }.toTypedArray(),
            DownloadWorker.KEY_TOTAL_SIZE to assetsToDownload.map { it.asset.size}.sum()
        )

        val downloadRequest =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(downloadData)
                .addTag(DownloadWorker.DOWNLOAD_WORKER_TAG)
                .build()

        // Enqueue work
        workManager.enqueueUniqueWork(
            DownloadWorker.DOWNLOAD_WORKER_TAG,
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )
    }

    /**
     * Delete files that exist locally but not in latest release (i.e. stale files)
     */
    private suspend fun deleteLocalOnlyFiles() {
        withContext(Dispatchers.IO) {
            localFilesToDelete.forEach {
                deleteFile(it)
            }
            localFilesToDelete.clear()
        }
    }

    private suspend fun deleteFile(filePath: String) {
        withContext(Dispatchers.IO) {
            logv("Deleting item: $filePath")
            try {
                val file = File(filePath)
                val ret = file.truncateAndDelete()
                if (!ret) {
                    throw IOException("Error deleting file $filePath")
                }
            } catch (e: Exception) {
                loge("Error deleting file: $filePath", e)
                operationState.postError("Error deleting file $filePath", e)
            }
        }
    }

    override suspend fun downloadLatestReleaseMetadataSync() {
        withContext(Dispatchers.IO) {
            try {
                if (cachedReleaseDownloadableAssets == null) {
                    val githubRelease =
                        githubReleaseDownloader.downloadLatestReleaseMetadata(
                            context.getString(
                                R.string.github_repo
                            )
                        )
                    if (githubRelease?.assets != null && githubRelease.assets.isNotEmpty()) {
                        cachedReleaseDownloadableAssets = githubRelease.assets.map {
                            DownloadAssetMetadata(
                                name = it.name,
                                url = it.url,
                                size = it.size
                            )
                        }
                    }
                }

                if (cachedReleaseDownloadableAssets != null) {
                    processDownloadableAssetList(cachedReleaseDownloadableAssets!!.sortedBy { it.name })
                }
            } catch (e: Exception) {
                loge("Download release error: $e", e)
                operationState.postError("Error querying for updates", e)
            }
        }
    }

    /**
     * Processes the list of [downloadableAssets]
     */
    val mutex = Mutex()
    private suspend fun processDownloadableAssetList(
        downloadableAssets: List<DownloadAssetMetadata>
    ) = mutex.withLock {
        val localAssets = getLocal()

        // Get latest on-disk assets
        getLocalAssetsScoped()

        localFilesToDelete.clear()
        logv("Process latest release: Total of ${downloadableAssets.size} release assets")

        val items = mutableListOf<DownloadAssetMetadataObservable>()
        val localMatches = mutableListOf<DownloadAssetMetadata>()
        for (asset in downloadableAssets) {
            val localAsset = localAssets.find { it.name.equals(asset.name) }

            // Existing asset - check if different. We use size - that is a good enough differentiator
            if (null != localAsset && localAsset.size == asset.size) {
                logv("Process latest release: Existing asset matches latest: ${asset.name}, size: ${asset.size}")
                localMatches.add(asset)
                continue
            }

            withContext(Dispatchers.Main) {
                if (downloadableItemsByName.containsKey(asset.name)) {
                    val downloadAssetMetadata =
                        downloadableItemsByName[asset.name]!!

                    downloadAssetMetadata.asset.url = asset.url
                    downloadAssetMetadata.asset.size = asset.size

                    if (!isDownloading.await()) {
                        downloadAssetMetadata.downloadingProgress.set(0)
                        downloadAssetMetadata.downloadingSize.set(0)
                        downloadAssetMetadata.isDownloading.set(false)
                        downloadAssetMetadata.shouldDownload.set(true)
                    }

                    downloadableItemsByName[asset.name]?.let { items.add(it) }
                    logv("Process latest release: Updated downloadable item: ${asset.name}")
                } else {
                    val item = createDownloadAssetObservable(
                        DownloadAssetMetadata(
                            name = asset.name,
                            url = asset.url,
                            size = asset.size
                        )
                    )
                    items.add(item)
                    downloadableItemsByName[asset.name] = item
                    logv("Process latest release: Added downloadable item: ${asset.name}")
                }
            }
        }

        // Find any on disk assets that are not in the release
        _localItems.value?.forEach {
            val localAsset = it
            if (!downloadableItemsByName.containsKey(localAsset.asset.name) &&
                null == localMatches.find { it.name.equals(localAsset.asset.name) }
            ) {
                logv("Process latest release: Local-only item: ${localAsset.asset.name}")
                localFilesToDelete.add(localAsset.asset.path)
            }
        }

        localMatchingItems.postValue(localMatches)
        _downloadableItems.postValue(items)
        computeDownloadSizeDelta()
    }

    override suspend fun getLocalAssetsScoped() {
        withContext(Dispatchers.IO) {
            try {
                var localAssetsSize: Long = 0
                val localAssets = getLocal()
                    .map {
                        localAssetsSize += it.size
                        createLocalAssetObservable(it)
                    }
                logv("Local items count: ${localAssets.size}")

                _localItemsSize.postValue(localAssetsSize)
                _localItems.postValue(localAssets)

                computeDownloadSizeDelta()
            } catch (e: Exception) {
                loge("Local items error: $e", e)
            }
        }
    }

    /**
     * Compute if we have enough storage to download assets
     */
    private suspend fun computeDownloadSizeDelta() {
        withContext(Dispatchers.IO) {
            // Required space is: (1) New items to download + (2) Changed items to download - (3) Changed local items - (4) Temp files
            val storageDir = currentExternalStorage.await().path
            val usableBytes: Long = File(storageDir).usableSpace
            logv("Usable space on $storageDir: ${StringFormatter.fileSize(usableBytes)} ")

            var requiredSpace: Long = 0
            val assetsToDownload = downloadableItems.value.orEmpty()
                .filter { it.shouldDownload.get() }

            assetsToDownload.forEach {
                if (null != localItems.value) {
                    for (localItem in localItems.value!!) {
                        val localAsset = localItem as LocalAssetMetadataObservable
                        if (localAsset.asset.name == it.asset.name) {
                            requiredSpace -= localAsset.asset.size
                            break
                        }
                    }
                }

                requiredSpace += it.asset.size
            }

            val remainingSpace = usableBytes - requiredSpace
            logv(
                "size delta: $requiredSpace, total space remaining: ${
                    StringFormatter.fileSize(
                        remainingSpace
                    )
                }"
            )

            _usableSpace.postValue(usableBytes)
            _requiredSpace.postValue(requiredSpace)
            _downloadSizeDelta.postValue(remainingSpace)
        }
    }

    /**
     * Observe download worker and handle status
     */
    private fun handleDownloadWork() {
        workManager.getWorkInfosByTagLiveData(DownloadWorker.DOWNLOAD_WORKER_TAG)
            .observe(ProcessLifecycleOwner.get()) { workInfoList ->
                if (null == workInfoList) {
                    return@observe
                }

                for (workInfo in workInfoList) {
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> {
                            logv("Download work enqueued")
                            _isDownloading.value = true
                        }

                        WorkInfo.State.SUCCEEDED -> {
                            logv("Download work succeeded")
                            _isDownloading.value = false
                            ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch(Dispatchers.Main) {
                                // Reload download asset list
                                downloadLatestReleaseMetadataSync()
                            }
                        }

                        WorkInfo.State.FAILED,
                        WorkInfo.State.CANCELLED -> {
                            logv("Download work cancelled or failed")
                            _isDownloading.value = false
                            ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch(Dispatchers.Main) {
                                cleanupTempFiles()

                                // Reload download asset list
                                downloadLatestReleaseMetadataSync()
                                if (workInfo.state == WorkInfo.State.FAILED) {
                                    operationState.postUpdate("Download failed")
                                } else {
                                    operationState.postUpdate("Download cancelled")
                                }
                            }
                        }

                        WorkInfo.State.RUNNING -> {
                            if (_isDownloading.value != true) {
                                logv("Setting isdownloading to true: $workInfo")
                                _isDownloading.value = true
                            }

                            val fileName =
                                workInfo.progress.getString(DownloadWorker.KEY_PROGRESS_NAME)
                            val downloadItem = downloadableItemsByName[fileName]

                            val isProgress = workInfo.progress.getBoolean(DownloadWorker.KEY_PROGRESS_IS_PROGRESS, false)
                            val isDone = workInfo.progress.getBoolean(DownloadWorker.KEY_PROGRESS_IS_DONE, false)
                            val isError = workInfo.progress.getBoolean(DownloadWorker.KEY_PROGRESS_IS_ERROR, false)
                            if (isDone && fileName != null) {
                                logv("Successfully downloaded asset: $fileName")

                                ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch {
                                    logv("Loading offline data after download")
                                    downloadItem?.isDownloading?.set(false)
                                    downloadItem?.downloadingSize?.set(0)
                                    downloadItem?.downloadingProgress?.set(0)
                                    operationState.postUpdate("Downloaded $fileName")

                                    // Reload download asset list
                                    downloadLatestReleaseMetadataSync()

                                    load()
                                }
                            }
                            if (isError && fileName != null) {
                                loge("Failed to download asset: $fileName, download item: $downloadItem")
                                operationState.postError(
                                    "Error downloading $fileName",
                                    null
                                )
                                if (null != downloadItem) {
                                    downloadItem.downloadingSize.set(0)
                                    downloadItem.downloadingProgress.set(
                                        0
                                    )
                                    downloadItem.isDownloading.set(false)
                                }
                            }
                            if (isProgress && fileName != null) {
                                if (null != downloadItem) {
                                    val currentSize =
                                        workInfo.progress.getLong(
                                            DownloadWorker.KEY_PROGRESS_CURRENT_SIZE,
                                            0
                                        )
                                    val totalSize =
                                        workInfo.progress.getLong(
                                            DownloadWorker.KEY_PROGRESS_TOTAL_SIZE,
                                            0
                                        )
                                    val percent =
                                        ((currentSize.toFloat() / totalSize) * 100).toInt()

                                    downloadItem.isDownloading.set(true)
                                    downloadItem.downloadingProgress.set(percent)
                                    downloadItem.downloadingSize.set(currentSize)
                                }
                            }
                        }

                        else -> {
                        }
                    }
                }
                workManager.pruneWork()
            }
    }

    /**
     * Observe change storage worker and handle status
     */
    private fun handleChangeStorageWork() {
        workManager.getWorkInfosByTagLiveData(ChangeStorageWorker.CHANGESTORAGE_WORKER_TAG)
            .observe(ProcessLifecycleOwner.get()) { workInfoList ->
                if (null == workInfoList) {
                    return@observe
                }

                for (workInfo in workInfoList) {
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> {
                            logv("Change storage enqueued")
                            _isChangingStorage.postValue(true)
                        }

                        WorkInfo.State.SUCCEEDED -> {
                            logv("Change storage succeeded")

                            operationState.postUpdate("Changed storage location")
                            _changedStorageLocation.postValue(null)
                            _isChangingStorage.postValue(false)

                            ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    // Get latest on-disk assets
                                    getLocalAssetsScoped()
                                }
                            }
                        }

                        WorkInfo.State.FAILED,
                        WorkInfo.State.CANCELLED -> {
                            logv("Change storage cancelled or failed")

                            operationState.postUpdate("Change storage cancelled or failed")
                            _isChangingStorage.postValue(false)

                            ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch {
                                withContext(Dispatchers.Main) {
                                    // Get latest on-disk assets
                                    getLocalAssetsScoped()
                                }
                            }
                        }

                        WorkInfo.State.RUNNING -> {
                            if (_isChangingStorage.value != true) {
                                _isChangingStorage.postValue(true)
                            }

                            val currentFileName =
                                workInfo.progress.getString(
                                    ChangeStorageWorker.KEY_PROGRESS_CURRENT_FILENAME
                                )
                            val currentFile =
                                workInfo.progress.getInt(
                                    ChangeStorageWorker.KEY_PROGRESS_CURRENT_FILE,
                                    0
                                )
                            val totalFiles =
                                workInfo.progress.getInt(
                                    ChangeStorageWorker.KEY_PROGRESS_TOTAL_FILES,
                                    0
                                )

                            operationState.postUpdate(context.getString(R.string.changestorage_notification_title, currentFileName, currentFile, totalFiles))
                        }

                        else -> {
                        }
                    }
                }

                workManager.pruneWork()
            }
    }


    private fun createLocalAssetObservable(asset: LocalAssetMetadata): LocalAssetMetadataObservable {
        return LocalAssetMetadataObservable(asset).apply {
            clickHandler = { item -> onClickItem(item) }
            deleteClickHandler = { item -> onDeleteClickItem(item) }
        }
    }

    private fun createDownloadAssetObservable(asset: DownloadAssetMetadata): DownloadAssetMetadataObservable {
        return DownloadAssetMetadataObservable(asset).apply {
            clickHandler = { item -> onClickItem(item) }
            shouldDownloadClickHandler = { item -> onShouldDownloadClickItem(item) }
        }
    }

    private fun onClickItem(asset: LocalAssetMetadata) {
        _localItemClick.value = asset
    }

    private fun onDeleteClickItem(asset: LocalAssetMetadata) {
        _onDeleteItemClick.value = asset
    }

    private fun onClickItem(asset: DownloadAssetMetadata) {
        _onDownloadableItemClick.value = asset
    }

    private fun onShouldDownloadClickItem(asset: DownloadAssetMetadata) {
        ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch {
            computeDownloadSizeDelta()
        }
    }

    override fun clearOnDownloadableClickItem() {
        _onDownloadableItemClick.value = null
    }

    override fun clearOnDiskClickItem() {
        _localItemClick.value = null
    }

    override fun clearOnDeleteClickItem() {
        _onDeleteItemClick.value = null
    }

    /**
     * Loads on-disk zip files
     */
    private suspend fun loadZipFiles(assets: Array<LocalAssetMetadata>): ZipResourceFile =
        withContext(Dispatchers.IO) {
            val zipResourceFile = ZipResourceFile(ProcessLifecycleOwner.get().lifecycle.coroutineScope, assets.map { it.path })
            return@withContext zipResourceFile
        }
}
