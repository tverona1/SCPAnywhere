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
import com.tverona.scpanywhere.repositories.OfflineDataRepositoryImpl
import com.tverona.scpanywhere.utils.StringFormatter
import com.tverona.scpanywhere.utils.combineWith
import com.tverona.scpanywhere.zipresource.ZipResourceFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import java.util.*

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
        Transformations.map(offlineDataRepository.releaseMetadataObservable) {
            it.downloadAssetMetadata.map { item -> item.toRecyclerItem() }
        }

    data class ReleaseMetadataViewModel(var publishedAt: Date, var isEmpty: Boolean)
    val releaseMetadata: LiveData<ReleaseMetadataViewModel> =
        Transformations.map(offlineDataRepository.releaseMetadataObservable) {
            ReleaseMetadataViewModel(it.publishedAt, it.downloadAssetMetadata.isEmpty())
        }

    // Local (on-disk) items & their sizes
    val localItems: LiveData<List<RecyclerItem>> =
        Transformations.map(offlineDataRepository.localItems) {
            it.map { item -> item.toRecyclerItem() }
        }

    val localItemsSize = offlineDataRepository.localItemsSize

    // Download size required
    val downloadSizeDelta = offlineDataRepository.downloadSizeDelta

    // Usable space
    val usableSpaceString: LiveData<String> =
        Transformations.map(offlineDataRepository.usableSpace) {
            "${context.getString(R.string.free_space)}: ${StringFormatter.fileSize(it)}"
        }

    // Required space
    val requiredSpaceString: LiveData<String> =
        Transformations.map(offlineDataRepository.requiredSpace) {
            "${context.getString(R.string.space_needed)}: ${StringFormatter.fileSize(it)}"
        }

    val changedStorageLocation = offlineDataRepository.changedStorageLocation
    val onDeleteClick = offlineDataRepository.onDeleteClick
    val isDownloadingOrResumable = offlineDataRepository.isDownloading.combineWith(offlineDataRepository.hasResumableDownloads, emitOnEitherSource = true) { isDownloading, hasResumableDownloads ->
        Pair(isDownloading == true, hasResumableDownloads == true)
    }
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
    fun cancelChangeStorage() = offlineDataRepository.cancelChangeStorage()

    /**
     * Change storage location
     */
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

    fun cleanupTempFiles() {
        viewModelScope.launch {
            offlineDataRepository.cleanupTempFiles()
            offlineDataRepository.downloadLatestReleaseMetadataSync()
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