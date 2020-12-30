package com.tverona.scpanywhere.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.tverona.scpanywhere.BR
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.recycleradapter.RecyclerItem

/**
 * View model used to populate list item fragment
 */
class ListItemViewModel : ViewModel() {
    private val _recyclerItems = MutableLiveData<List<RecyclerItem>>()
    val recyclerItems: LiveData<List<RecyclerItem>> = _recyclerItems

    private val _onItemClick = MutableLiveData<ListItem>()
    val onItemClick: LiveData<ListItem> = _onItemClick

    private fun createListItemClickable(listItem: ListItem): ListItemClickable {
        return ListItemClickable(listItem).apply {
            itemClickHandler = { item -> onClickItem(item) }
        }
    }

    fun loadData(listItems: List<ListItem>) {
        _recyclerItems.value = listItems
            .map { createListItemClickable(it) }
            .map { it.toRecyclerItem() }
    }

    private fun onClickItem(listItem: ListItem) {
        _onItemClick.value = listItem
    }

    fun clearOnClickItem() {
        _onItemClick.value = null
    }
}

class ListItemClickable(val listItem: ListItem) {
    lateinit var itemClickHandler: (listItem: ListItem) -> Unit

    fun onClick() {
        itemClickHandler(listItem)
    }
}

private fun ListItemClickable.toRecyclerItem() = RecyclerItem(
    data = this,
    layoutId = R.layout.list_item,
    variableId = BR.item
)
