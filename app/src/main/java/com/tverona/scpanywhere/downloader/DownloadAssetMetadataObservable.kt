package com.tverona.scpanywhere.downloader

import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.databinding.ObservableLong
import com.tverona.scpanywhere.recycleradapter.RecyclerItemComparator

/**
 * Observable on top of downloadable asset metadata
 */
class DownloadAssetMetadataObservable(val asset: DownloadAssetMetadata) : RecyclerItemComparator {
    lateinit var clickHandler: (asset: DownloadAssetMetadata) -> Unit
    lateinit var shouldDownloadClickHandler: (asset: DownloadAssetMetadata) -> Unit

    val downloadingProgress = ObservableInt(0)
    val downloadingSize = ObservableLong(0)
    val isDownloading = ObservableField(false)
    val shouldDownload: ObservableBoolean = object : ObservableBoolean(true) {
        override fun set(value: Boolean) {
            shouldDownloadClickHandler(asset)
            super.set(value)
        }
    }

    fun onClick() {
        clickHandler(asset)
    }

    override fun isSameItem(other: Any): Boolean {
        if (this === other) return true
        if (javaClass != other.javaClass) return false
        other as DownloadAssetMetadataObservable
        return (other.asset == asset)
    }

    override fun isSameContent(other: Any): Boolean {
        other as DownloadAssetMetadataObservable
        return (other.asset == asset)
    }
}
