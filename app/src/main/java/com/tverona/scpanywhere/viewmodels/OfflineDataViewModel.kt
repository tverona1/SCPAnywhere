package com.tverona.scpanywhere.viewmodels

import android.content.Context
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.*
import com.tverona.scpanywhere.BR
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.downloader.*
import com.tverona.scpanywhere.recycleradapter.RecyclerItem
import com.tverona.scpanywhere.repositories.OfflineDataRepository
import com.tverona.scpanywhere.utils.*
import com.tverona.scpanywhere.zipresource.ZipResourceFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okio.IOException
import java.io.File

/**
 * View model that represents local (on-disk) assets and downloadable assets
 */
class OfflineDataViewModel @ViewModelInject constructor(
    @ApplicationContext val context: Context,
    private val offlineDataRepository: OfflineDataRepository,
    private val client: OkHttpClient,
    private val githubReleaseDownloader: GithubReleaseDownloader
) : ViewModel() {
    val zip: LiveData<ZipResourceFile> = offlineDataRepository.zipResourceFile
    var externalStorageMetadata = offlineDataRepository.externalStorageList

    val operationState = StateLiveData<String>()

    // Downloadable items
    private val _downloadableItems = MutableLiveData<List<RecyclerItem>>()
    val downloadableItems: LiveData<List<RecyclerItem>> = _downloadableItems
    private val downloadableItemsByName = HashMap<String, RecyclerItem>()

    // Local (on-disk) items & their sizes
    private val _localItems = MutableLiveData<List<RecyclerItem>>()
    val localItems: LiveData<List<RecyclerItem>> = _localItems
    val _localItemsSize = MutableLiveData<Long>(0)
    val localItemsSize: LiveData<Long> = _localItemsSize

    // Download size required
    private val _downloadSizeDelta = MutableLiveData<Long>()
    val downloadSizeDelta: LiveData<Long> = _downloadSizeDelta
    val downloadSizeDeltaString: LiveData<String> = Transformations.map(_downloadSizeDelta) {
        "${context.getString(R.string.space_needed)}: ${StringFormatter.fileSize(it)}"
    }

    // Usable space
    private val _usableSpace = MutableLiveData<Long>()
    val usableSpaceString: LiveData<String> = Transformations.map(_usableSpace) {
        "${context.getString(R.string.free_space)}: ${StringFormatter.fileSize(it)}"
    }

    // Required space
    private val _requiredSpace = MutableLiveData<Long>()
    val requiredSpace: LiveData<Long> = _requiredSpace
    val requiredSpaceString: LiveData<String> = Transformations.map(_requiredSpace) {
        "${context.getString(R.string.space_needed)}: ${StringFormatter.fileSize(it)}"
    }

    private val _changedStorageLocation = MutableLiveData<Unit>()
    val changedStorageLocation: LiveData<Unit> = _changedStorageLocation

    private val localFilesToDelete = mutableListOf<String>()

    private val localMatchingItems = MutableLiveData<List<DownloadAssetMetadata>>()

    private val _onDownloadableItemClick = MutableLiveData<DownloadAssetMetadata>()
    val onDownloadableClick: LiveData<DownloadAssetMetadata> = _onDownloadableItemClick

    private val _onDeleteItemClick = MutableLiveData<LocalAssetMetadata>()
    val onDeleteClick: LiveData<LocalAssetMetadata> = _onDeleteItemClick

    private val _localItemClick = MutableLiveData<LocalAssetMetadata>()
    val onLocalItemClick: LiveData<LocalAssetMetadata> = _localItemClick

    private var downloadJob: Job? = null
    private var changeStorageJob: Job? = null

    val isDownloading: Boolean
        get() = downloadJob?.isActive == true

    private val _isDownloadingObservable = MutableLiveData<Boolean>(false)
    val isDownloadingObservable : LiveData<Boolean> = _isDownloadingObservable

    val isChangingStorage: Boolean
        get() = changeStorageJob?.isActive == true

    val currentExternalStorage = offlineDataRepository.currentExternalStorage

    /**
     * Download assets
     */
    @ExperimentalCoroutinesApi
    fun download() {
        // If we're currently downloading, return
        if (isDownloading) {
            logv("Already downloading")
            return
        }

        if (downloadSizeDelta.value!! < 0) {
            logv("Not enough space")
            return
        }

        val assetsToDownload = downloadableItems.value.orEmpty()
            .map { it.data }
            .filterIsInstance<DownloadAssetMetadataObservable>()
            .filter { it.shouldDownload.get() && !it.isDownloading.get()!! }

        downloadJob = downloadAssetsAsync(assetsToDownload)
        if (null != downloadJob) {
            _isDownloadingObservable.postValue(true)
            downloadJob?.invokeOnCompletion { cause ->
                _isDownloadingObservable.postValue(false)
            }
        }
    }

    fun cancelDownload() {
        viewModelScope.launch {
            cancelDownloadSync()
        }
    }

    /**
     * Cancel downloading
     */
    suspend fun cancelDownloadSync() {
        if (!isDownloading) {
            return
        }

        withContext(Dispatchers.Main) {
            logv("Canceling download")
            downloadJob?.cancelAndJoin()
            offlineDataRepository.cleanupTempFiles()

            // Reset progress for all downloadable items
            downloadableItems.value.orEmpty()
                .map { it.data }
                .filterIsInstance<DownloadAssetMetadataObservable>()
                .map {
                    logv("Resetting: ${it.asset.name}")
                    it.isDownloading.set(false)
                    it.downloadingProgress.set(0)
                    it.downloadingSize.set(0)
                    logv("Done resetting: ${it.asset.name}")
                }
            operationState.postUpdate("Download cancelled")
        }
    }

    /**
     * Cancel changing storage location
     */
    suspend fun cancelChangeStorageSync() {
        if (!isChangingStorage) {
            return
        }

        withContext(Dispatchers.Main) {
            logv("Canceling change storage")
            changeStorageJob?.cancelAndJoin()
            offlineDataRepository.cleanupTempFiles()
            operationState.postUpdate("Change storage cancelled")
        }
    }

    fun changeStorageLocation(sourcePath: String, destPath: String) {
        viewModelScope.launch {
            // Cancel any in-flight download or change storage operations
            cancelDownloadSync()
            cancelChangeStorageSync()
            changeStorageJob = changeStorageLocationAsync(sourcePath, destPath)
        }
    }

    /**
     * Change storage location. If already downloaded data, this will move the data.
     */
    private fun changeStorageLocationAsync(sourcePath: String, destPath: String): Job {
        return viewModelScope.launch {
            withContext(Dispatchers.IO) {

                // Clean up any temporary files
                offlineDataRepository.cleanupTempFiles()

                val destTempFiles = mutableListOf<File>()
                val sourceFiles =
                    offlineDataRepository.getFiles(sourcePath, OfflineDataRepository.zipExt)

                operationState.postUpdate("Changing storage location")

                try {
                    sourceFiles.forEach {
                        val sourceFile = File(it.path)
                        val nameWithoutExt = sourceFile.nameWithoutExtension
                        val destTempFile =
                            File(destPath + File.separator + nameWithoutExt + OfflineDataRepository.tmpExt)

                        logv(
                            "Copying ${sourceFile.absolutePath} to ${destTempFile.absolutePath} ${
                                StringFormatter.fileSize(
                                    it.size
                                )
                            }"
                        )
                        operationState.postUpdate("Copying $nameWithoutExt")

                        if (!isActive) {
                            throw java.io.IOException("Change storage path cancelled")
                        }

                        sourceFile.copyTo(destTempFile) { progress, total ->
                            if (isActive) {
                                val percent = ((progress.toFloat() / total) * 100).toInt()
                            }
                        }
                        logv(
                            "Finished copying ${sourceFile.absolutePath} to ${destTempFile.absolutePath} ${
                                StringFormatter.fileSize(
                                    it.size
                                )
                            }"
                        )
                        destTempFiles.add(destTempFile)
                    }

                    destTempFiles.forEach {
                        val destFile =
                            File(destPath + File.separator + it.nameWithoutExtension + OfflineDataRepository.zipExt)
                        logv("Renaming ${it.absolutePath} to ${destFile.name}")
                        it.rename(destFile)
                    }

                    sourceFiles.forEach {
                        logv("Deleting ${it.path}")
                        val sourceFile = File(it.path)
                        sourceFile.truncateAndDelete()
                    }
                } catch (e: Exception) {
                    loge("Error moving files: ${e.message}", e)
                }

                // Update storage location
                offlineDataRepository.setStorageLocation(destPath)

                // Get latest on-disk assets
                getLocalAssetsScoped()

                // Reload offline repository
                logv("Loading offline data after changing storage")
                offlineDataRepository.load()
                operationState.postUpdate("Changed storage location")
                _changedStorageLocation.postValue(null)
            }
        }
    }

    /**
     * Async download assets
     */
    @ExperimentalCoroutinesApi
    private fun downloadAssetsAsync(assetsToDownload: List<DownloadAssetMetadataObservable>): Job {
        return viewModelScope.launch {
            // Cancel any in-flight change storage operation
            cancelChangeStorageSync()

            operationState.postUpdate("Downloading ${assetsToDownload.size} items")

            // Clean up any temporary files
            offlineDataRepository.cleanupTempFiles()

            // Nuke any local-only files
            deleteLocalOnlyFiles()

            assetsToDownload.map {
                launch {

                    // Download the asset
                    try {
                        downloadAsset(it)
                        operationState.postUpdate("Downloaded ${it.asset.name}")

                        // Reload download asset list
                        val tempDownloadableAssets = mutableListOf<DownloadAssetMetadata>()
                        tempDownloadableAssets.addAll(localMatchingItems.value.orEmpty())
                        tempDownloadableAssets.addAll(
                            downloadableItems.value.orEmpty()
                                .map { it.data }
                                .filterIsInstance<DownloadAssetMetadataObservable>()
                                .map { it.asset }
                        )
                        processDownloadableAssetList(tempDownloadableAssets)

                        // Reload offline repository
                        logv("Loading offline data after download")
                        offlineDataRepository.load()
                    } catch (e: Exception) {
                        loge("Failed to download asset: ${it.asset.name}")
                        operationState.postError("Error downloading ${it.asset.name}", e)
                    }
                }
            }
        }
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

    /**
     * Download asset represented by [downloadAssetMetadataObservable]
     */
    @ExperimentalCoroutinesApi
    private suspend fun downloadAsset(downloadAssetMetadataObservable: DownloadAssetMetadataObservable) {
        return withContext(Dispatchers.IO) {

            val storageDir = currentExternalStorage.await().path
            val file =
                File(storageDir + File.separator + downloadAssetMetadataObservable.asset.name + OfflineDataRepository.tmpExt)
            logv("Downloading ${downloadAssetMetadataObservable.asset.url} to file ${file.absolutePath}")

            try {
                var isDownloading: Boolean
                synchronized(downloadAssetMetadataObservable) {
                    // Ensure that we're not downloading the same asset simultaneously
                    isDownloading = downloadAssetMetadataObservable.isDownloading.get()!!
                    if (!isDownloading) {
                        downloadAssetMetadataObservable.isDownloading.set(true)
                    }
                }

                if (!isDownloading) {
                    // Delete destination first to save storage space, unless it's the base asset;
                    // then keep it since it's relatively small to allow continuing to browse in the background
                    val fileDest =
                        File(storageDir + File.separator + downloadAssetMetadataObservable.asset.name)
                    if (!downloadAssetMetadataObservable.asset.name.contains(
                            baseAssetName,
                            ignoreCase = true
                        )
                    ) {
                        fileDest.truncateAndDelete()
                    }

                    val url = downloadAssetMetadataObservable.asset.url!!
                    githubReleaseDownloader.downloadReleaseAsset(url, file) { progress, total ->
                        if (isActive) {
                            val percent = ((progress.toFloat() / total) * 100).toInt()
                            downloadAssetMetadataObservable.downloadingProgress.set(percent)
                            downloadAssetMetadataObservable.downloadingSize.set(progress)
                        }
                    }

                    val ret = file.rename(fileDest)
                    if (!ret) {
                        throw IOException("Unable to move file from ${file.absolutePath} to ${fileDest.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                loge("Download asset ${downloadAssetMetadataObservable.asset.name} error: $e", e)

                // Reset the asset info on error
                withContext(Dispatchers.Main) {
                    downloadAssetMetadataObservable.downloadingSize.set(0)
                    downloadAssetMetadataObservable.downloadingProgress.set(0)
                }
                throw e
            } finally {
                downloadAssetMetadataObservable.isDownloading.set(false)
            }
        }
    }

    /**
     * Download latest release metadata
     */
    fun downloadLatestReleaseMetadata() {
        if (isDownloading) {
            // If we're currently downloading, don't re-fresh the release metadata
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val githubRelease =
                        githubReleaseDownloader.downloadLatestReleaseMetadata(context.getString(R.string.github_repo))
                    if (githubRelease?.assets != null && githubRelease.assets.isNotEmpty()) {
                        val tempDownloadableAssets = githubRelease.assets.map {
                            DownloadAssetMetadata(
                                name = it.name,
                                url = it.url,
                                size = it.size
                            )
                        }

                        processDownloadableAssetList(tempDownloadableAssets.sortedBy { it.name })
                    }
                } catch (e: Exception) {
                    loge("Download release error: $e", e)
                    operationState.postError("Error querying for updates", e)
                }
            }
        }
    }

    fun populateStorageEntries() {
        viewModelScope.launch {
            offlineDataRepository.populateStorageEntries()
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
                .map { it -> it.data }
                .filterIsInstance<DownloadAssetMetadataObservable>()
                .filter { it.shouldDownload.get() }

            assetsToDownload.forEach {
                if (null != localItems.value) {
                    for (localItem in localItems.value!!) {
                        val localAsset = localItem.data as LocalAssetMetadataObservable
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
     * Processes the list of [downloadableAssets]
     */
    @Synchronized
    private suspend fun processDownloadableAssetList(
        downloadableAssets: List<DownloadAssetMetadata>
    ) {
        val localAssets = offlineDataRepository.getLocal()

        // Get latest on-disk assets
        getLocalAssetsScoped()

        localFilesToDelete.clear()

        val localTempFilesToDelete = mutableListOf<String>()
        val localTemp = offlineDataRepository.getLocalTemp()
        for (it in localTemp) {
            val downloadingAssetName = it.name.removeSuffix(OfflineDataRepository.tmpExt)
            if (downloadableItemsByName.containsKey(downloadingAssetName)) {
                val downloadItem =
                    downloadableItemsByName[downloadingAssetName]?.data as DownloadAssetMetadataObservable
                if (downloadItem.isDownloading.get()!!) {
                    // If currently downloading, skip this one
                    continue
                }
            }
            logv("Process latest release: Found temp file ${it.name}")
            localTempFilesToDelete.add(it.path)
        }

        // Delete temp files
        localTempFilesToDelete.forEach {
            deleteFile(it)
        }

        logv("Process latest release: Total of ${downloadableAssets.size} release assets")

        val items = mutableListOf<RecyclerItem>()
        val localMatches = mutableListOf<DownloadAssetMetadata>()
        for (asset in downloadableAssets) {
            val localAsset = localAssets.find { it.name.equals(asset.name) }

            // Existing asset - check if different. We use size - that is a good enough differentiator
            if (null != localAsset && localAsset.size == asset.size) {
                logv("Process latest release: Existing asset matches latest: ${asset.name}, size: ${asset.size}")
                localMatches.add(asset)
                continue
            }

            if (downloadableItemsByName.containsKey(asset.name)) {
                val downloadAssetMetadata =
                    downloadableItemsByName[asset.name]?.data as DownloadAssetMetadataObservable
                downloadAssetMetadata.asset.url = asset.url
                downloadAssetMetadata.asset.size = asset.size
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
                    .toRecyclerItem()
                items.add(item)
                downloadableItemsByName[asset.name] = item
                logv("Process latest release: Added downloadable item: ${asset.name}")
            }
        }

        // Find any on disk assets that are not in the release
        _localItems.value?.forEach {
            val localAsset = it.data as LocalAssetMetadataObservable
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

    fun getLocalAssets() {
        viewModelScope.launch {
            getLocalAssetsScoped()
        }
    }

    private suspend fun getLocalAssetsScoped() {
        withContext(Dispatchers.IO) {
            try {
                var localAssetsSize: Long = 0
                val localAssets = offlineDataRepository.getLocal()
                    .map {
                        localAssetsSize += it.size
                        createLocalAssetObservable(it)
                    }
                    .map {
                        it.toRecyclerItem()
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

    private fun createDownloadAssetObservable(asset: DownloadAssetMetadata): DownloadAssetMetadataObservable {
        return DownloadAssetMetadataObservable(asset).apply {
            clickHandler = { item -> onClickItem(item) }
            shouldDownloadClickHandler = { item -> onShouldDownloadClickItem(item) }
        }
    }

    private fun createLocalAssetObservable(asset: LocalAssetMetadata): LocalAssetMetadataObservable {
        return LocalAssetMetadataObservable(asset).apply {
            clickHandler = { item -> onClickItem(item) }
            deleteClickHandler = { item -> onDeleteClickItem(item) }
        }
    }

    private fun onClickItem(asset: DownloadAssetMetadata) {
        _onDownloadableItemClick.value = asset
    }

    private fun onShouldDownloadClickItem(asset: DownloadAssetMetadata) {
        viewModelScope.launch {
            computeDownloadSizeDelta()
        }
    }

    private fun onClickItem(asset: LocalAssetMetadata) {
        _localItemClick.value = asset
    }

    private fun onDeleteClickItem(asset: LocalAssetMetadata) {
        _onDeleteItemClick.value = asset
    }

    fun deleteLocalAsset(asset: LocalAssetMetadata) {
        viewModelScope.launch {
            deleteFile(asset.path)

            // Refresh local items
            getLocalAssetsScoped()
        }
    }

    private suspend fun deleteFile(filePath: String) {
        withContext(Dispatchers.IO) {
            logv("Deleting item: filePath")
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

    fun clearOnDownloadableClickItem() {
        _onDownloadableItemClick.value = null
    }

    fun clearOnOnDiskClickItem() {
        _localItemClick.value = null
    }

    fun clearOnDeleteClickItem() {
        _onDeleteItemClick.value = null
    }

    companion object {
        val baseAssetName = "base"
    }
}

private fun DownloadAssetMetadataObservable.toRecyclerItem() = RecyclerItem(
    data = this,
    layoutId = R.layout.download_item,
    variableId = BR.download_item
)

private fun LocalAssetMetadataObservable.toRecyclerItem() = RecyclerItem(
    data = this,
    layoutId = R.layout.local_item,
    variableId = BR.local_item
)