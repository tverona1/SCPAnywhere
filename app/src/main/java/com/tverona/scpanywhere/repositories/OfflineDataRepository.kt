package com.tverona.scpanywhere.repositories

import androidx.lifecycle.LiveData
import com.tverona.scpanywhere.downloader.LocalAssetMetadata
import com.tverona.scpanywhere.zipresource.ZipResourceFile

data class ExternalStorageMetadata(val name: String, val path: String, val usableSpace: Long)

/**
 * Repository interface for offline data
 */
interface OfflineDataRepository {
    val zipResourceFile: LiveData<ZipResourceFile>

    val externalStorageList: LiveData<List<ExternalStorageMetadata>>
    val currentExternalStorage: LiveData<ExternalStorageMetadata>

    suspend fun load()

    suspend fun setStorageLocation(path: String)
    suspend fun getLocal(): Array<LocalAssetMetadata>
    suspend fun getLocalTemp(): Array<LocalAssetMetadata>
    suspend fun getFiles(path: String, ext: String): Array<LocalAssetMetadata>
    suspend fun cleanupTempFiles()
    suspend fun populateStorageEntries()

    companion object {
        const val tmpExt = ".tmp"
        const val zipExt = ".zip"
    }
}
