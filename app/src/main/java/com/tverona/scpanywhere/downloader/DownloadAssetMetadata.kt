package com.tverona.scpanywhere.downloader

import com.tverona.scpanywhere.utils.StringFormatter

/**
 * Metadata of a downloadable asset
 */
data class DownloadAssetMetadata(
    var name: String,
    var url: String? = null,
    var size: Long = 0,
) {
    companion object {
        fun formatSize(size: Long) = StringFormatter.fileSize(size)
        fun formatPercent(percent: Int) = StringFormatter.percent(percent)
    }
}