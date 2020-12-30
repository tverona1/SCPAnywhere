package com.tverona.scpanywhere.downloader

import com.tverona.scpanywhere.recycleradapter.RecyclerItemComparator

/**
 * Observable on top of local asset metadata
 */
class LocalAssetMetadataObservable(val asset: LocalAssetMetadata) : RecyclerItemComparator {
    lateinit var clickHandler: (asset: LocalAssetMetadata) -> Unit
    lateinit var deleteClickHandler: (asset: LocalAssetMetadata) -> Unit

    fun onClick() {
        clickHandler(asset)
    }

    fun onDeleteButtonClick() {
        deleteClickHandler(asset)
    }

    override fun isSameItem(other: Any): Boolean {
        if (this === other) return true
        if (javaClass != other.javaClass) return false
        other as LocalAssetMetadataObservable
        return (other.asset == asset)
    }

    override fun isSameContent(other: Any): Boolean {
        other as LocalAssetMetadataObservable
        return (other.asset == asset)
    }
}