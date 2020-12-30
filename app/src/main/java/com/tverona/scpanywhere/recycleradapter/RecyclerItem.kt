package com.tverona.scpanywhere.recycleradapter

import androidx.annotation.LayoutRes

/**
 * Item in the recycler adapter
 */
data class RecyclerItem(
    val data: Any,
    @LayoutRes val layoutId: Int,
    val variableId: Int
)