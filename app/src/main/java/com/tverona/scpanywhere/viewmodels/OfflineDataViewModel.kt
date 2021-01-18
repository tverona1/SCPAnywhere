package com.tverona.scpanywhere.viewmodels

import android.content.Context
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tverona.scpanywhere.BR
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.downloader.DownloadAssetMetadataObservable
import com.tverona.scpanywhere.downloader.LocalAssetMetadata
import com.tverona.scpanywhere.downloader.LocalAssetMetadataObservable
import com.tverona.scpanywhere.recycleradapter.RecyclerItem
import com.tverona.scpanywhere.repositories.OfflineDataRepository
import com.tverona.scpanywhere.utils.StringFormatter
import com.tverona.scpanywhere.zipresource.ZipResourceFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch

/**
 * View model that represents local (on-disk) assets and downloadable assets
 */
class OfflineDataViewModel @ViewModelInject constructor(
    @ApplicationContext val context: Context,
    private val offlineDataRepository: OfflineDataRepository
) : ViewModel() {
    val zip: LiveData<ZipResourceFile> = offlineDataRepository.zipResourceFile
    var externalStorageMetadata = offlineDataRepository.externalStorageList

    val operationState = offlineDataRepository.operationState

    // Downloadable items
    val downloadableItems: LiveData<List<RecyclerItem>> =
        Transformations.map(offlineDataRepository.downloadableItems) {
            it.map { item -> item.toRecyclerItem() }
        }

    // Local (on-disk) items & their sizes
    val localItems: LiveData<List<RecyclerItem>> =
        Transformations.map(offlineDataRepository.localItems) {
            it.map { item -> item.toRecyclerItem() }
        }

    val localItemsSize: LiveData<Long> = offlineDataRepository.localItemsSize

    // Download size required
    val downloadSizeDelta = offlineDataRepository.downloadSizeDelta

    // Usable space
    val usableSpaceString: LiveData<String> =
        Transformations.map(offlineDataRepository.usableSpace) {
            "${context.getString(R.string.free_space)}: ${StringFormatter.fileSize(it)}"
        }

    // Required space


    private val _localItemClick = MutableLiveData<LocalAssetMetadata>()

    private var downloadJob: Job? = null
    private var changeStorageJob: Job? = null

    val isDownloading: Boolean
        get() = downloadJob?.isActive == true

    private val _isDownloadingObservable = MutableLiveData<Boolean>(false)
    val isDownloadingObservable : LiveData<Boolean> = _isDownloadingObservable
            "${context.getString(R.string.space_needed)}: ${StringFormatter.fileSize(it)}"
        }

    val changedStorageLocation = offlineDataRepository.changedStorageLocation
    val onDeleteClick = offlineDataRepository.onDeleteClick
    val isDownloading = offlineDataRepository.isDownloading
    val isChangingStorage = offlineDataRepository.isChangingStorage

    val currentExternalStorage = offlineDataRepository.currentExternalStorage

    /**
     * Download assets
     */
    fun download() = offlineDataRepository.download()

    /**
     * Cancel downloading
     */
    fun cancelDownload() = offlineDataRepository.cancelDownload()

    /**
     * Cancel changing storage location
     */
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
    suspend fun changeStorageLocation(sourcePath: String, destPath: String) =
        offlineDataRepository.changeStorageLocation(sourcePath, destPath)

    /**
     * Download latest release metadata
     */
    fun downloadLatestReleaseMetadata() {
        viewModelScope.launch {
            offlineDataRepository.downloadLatestReleaseMetadataSync()
        }
    }

    fun populateStorageEntries() {
        viewModelScope.launch {
            offlineDataRepository.populateStorageEntries()
        }
    }

    fun getLocalAssets() {
        viewModelScope.launch {
            offlineDataRepository.getLocalAssetsScoped()
        }
    }

    fun deleteLocalAsset(asset: LocalAssetMetadata) = offlineDataRepository.deleteLocalAsset(asset)
    fun clearOnDeleteClickItem() = offlineDataRepository.clearOnDeleteClickItem()
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