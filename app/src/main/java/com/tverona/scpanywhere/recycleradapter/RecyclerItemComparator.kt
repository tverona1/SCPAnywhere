package com.tverona.scpanywhere.recycleradapter

/**
 * Interface to recycler item comparison
 */
interface RecyclerItemComparator {
    fun isSameItem(other: Any): Boolean
    fun isSameContent(other: Any): Boolean
}