package com.tverona.scpanywhere.recycleradapter

/**
 * Filter interface for recycler item
 */
interface RecyclerItemFilter {
    fun filter(value: String): Boolean
}