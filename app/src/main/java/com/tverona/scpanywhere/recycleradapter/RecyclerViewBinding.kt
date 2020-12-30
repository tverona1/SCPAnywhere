package com.tverona.scpanywhere.recycleradapter

import androidx.databinding.BindingAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Sets items on recycler view
 */
@BindingAdapter("items")
fun setRecyclerViewItems(
    recyclerView: RecyclerView,
    items: List<RecyclerItem>?
) {
    var adapter = (recyclerView.adapter as? DataBindingRecyclerAdapter)
    if (adapter == null) {
        adapter = DataBindingRecyclerAdapter()
        recyclerView.adapter = adapter
    }

    if (null == adapter.items) {
        adapter.items = items
    }

    adapter.submitList(
        items.orEmpty()
    )
}
