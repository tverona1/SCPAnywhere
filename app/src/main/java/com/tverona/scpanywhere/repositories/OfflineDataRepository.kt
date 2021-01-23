package com.tverona.scpanywhere.repositories

import androidx.lifecycle.LiveData
import com.tverona.scpanywhere.downloader.DownloadAssetMetadata
import com.tverona.scpanywhere.downloader.DownloadAssetMetadataObservable
import com.tverona.scpanywhere.downloader.LocalAssetMetadata
import com.tverona.scpanywhere.downloader.LocalAssetMetadataObservable
import com.tverona.scpanywhere.zipresource.ZipResourceFile
import java.util.*

data class ExternalStorageMetadata(val name: String, val path: String, val usableSpace: Long)

/**
 * Repository interface for offline data
 */
interface OfflineDataRepository {
    val zipResourceFile: LiveData<ZipResourceFile>

    val externalStorageList: LiveData<List<ExternalStorageMetadata>>
    val currentExternalStorage: LiveData<ExternalStorageMetadata>
    val operationState: StateLiveData<String>

    data class ReleaseMetadataObservable(var publishedAt: Date, var downloadAssetMetadata: List<DownloadAssetMetadataObservable>)
    val releaseMetadataObservable: LiveData<ReleaseMetadataObservable>

    val localItems: LiveData<List<LocalAssetMetadataObservable>>
    val localItemsSize: LiveData<Long>
    val downloadSizeDelta: LiveData<Long>
    val usableSpace: LiveData<Long>
    val requiredSpace: LiveData<Long>
    val isDownloading : LiveData<Boolean>
    val changedStorageLocation: LiveData<Unit>
    val onDownloadableClick: LiveData<DownloadAssetMetadata>
    val onDeleteClick: LiveData<LocalAssetMetadata>
    val onLocalItemClick: LiveData<LocalAssetMetadata>
    val isChangingStorage : LiveData<Boolean>

    suspend fun load()

    suspend fun setStorageLocation(path: String)
    suspend fun getLocal(): Array<LocalAssetMetadata>
    suspend fun getLocalTemp(): Array<LocalAssetMetadata>
    suspend fun getFiles(path: String, ext: String): Array<LocalAssetMetadata>
    suspend fun cleanupTempFiles()
    suspend fun populateStorageEntries()

    fun download()
    fun cancelDownload()
    suspend fun downloadLatestReleaseMetadataSync()
    fun deleteLocalAsset(asset: LocalAssetMetadata)
    suspend fun getLocalAssetsScoped()
    fun cancelChangeStorage()
    suspend fun changeStorageLocation(sourcePath: String, destPath: String)
    fun clearOnDownloadableClickItem()
    fun clearOnDiskClickItem()
    fun clearOnDeleteClickItem()

    companion object {
        const val tmpExt = ".tmp"
        const val zipExt = ".zip"
        const val baseAssetName = "base"
    }
}
