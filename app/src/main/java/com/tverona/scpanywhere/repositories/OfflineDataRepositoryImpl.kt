package com.tverona.scpanywhere.repositories

import android.content.Context
import android.os.Environment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.preference.PreferenceManager
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.downloader.LocalAssetMetadata
import com.tverona.scpanywhere.repositories.OfflineDataRepository.Companion.tmpExt
import com.tverona.scpanywhere.repositories.OfflineDataRepository.Companion.zipExt
import com.tverona.scpanywhere.utils.*
import com.tverona.scpanywhere.zipresource.ZipResourceFile
import kotlinx.coroutines.*
import java.io.File
import java.util.*

/**
 * Implementation of offline data repository, backed by zip files
 */
class OfflineDataRepositoryImpl constructor(
    private val context: Context
) : OfflineDataRepository {
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var defaultExternalDirIndex = 0

    // Zip resource file
    private val _zipResourceFile = MutableLiveData<ZipResourceFile>()
    override val zipResourceFile = _zipResourceFile

    // List of available external storage locations
    private val _externalStorageList = MutableLiveData<List<ExternalStorageMetadata>>()
    override val externalStorageList = _externalStorageList

    // Current external storage location
    private val _currentExternalStorage = MutableLiveData<ExternalStorageMetadata>()
    override val currentExternalStorage = _currentExternalStorage

    init {
        ProcessLifecycleOwner.get().lifecycle.coroutineScope.launch {
            populateStorageEntries()

            // Clean up any temporary files
            cleanupTempFiles()

            // Load zip files
            load()
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

        // todo: what if sd card is ejected? or new one inserted?

        // Default storage to external, else fall back to internal
        defaultExternalDirIndex = default ?: 0
        _externalStorageList.postValue(list)
        _currentExternalStorage.postValue(getStorageMetadata(list[defaultExternalDirIndex].path))
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
