package com.tverona.scpanywhere.downloader

import com.tverona.scpanywhere.utils.StringFormatter

/**
 * Metadata for a downloaded asset that resides on disk
 */
data class LocalAssetMetadata(
    var name: String,
    var size: Long = 0,
    var path: String,
    var status: String? = null
) {
    companion object {
        fun formatSize(size: Long) = StringFormatter.fileSize(size)
    }
}