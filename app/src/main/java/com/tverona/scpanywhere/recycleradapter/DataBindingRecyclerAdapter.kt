package com.tverona.scpanywhere.recycleradapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.ListAdapter

/**
 * Data binding recylcer adapter, implemented on top of a list adapter
 */
class DataBindingRecyclerAdapter : ListAdapter<RecyclerItem, BindingViewHolder>(
    DiffCallback()
), Filterable {
    override fun getItemViewType(position: Int): Int {
        return getItem(position).layoutId
    }

    var items: List<RecyclerItem>? = null

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): BindingViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding: ViewDataBinding = DataBindingUtil.inflate(inflater, viewType, parent, false)
        return BindingViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: BindingViewHolder,
        position: Int
    ) {
        holder.run {
            getItem(position).bind(binding)
            if (binding.hasPendingBindings()) {
                binding.executePendingBindings()
            }
        }
    }

    /**
     * Filter used to filter search results
     */
    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSequence: CharSequence): FilterResults {
                val charString = charSequence.toString()
                var result: List<RecyclerItem>? = null
                if (charString.isEmpty()) {
                    result = items
                } else {
                    result =
                        items?.filter { it.data is RecyclerItemFilter && it.data.filter(charString) }
                }

                val filterResults = FilterResults()
                filterResults.values = result
                return filterResults
            }

            override fun publishResults(
                charSequence: CharSequence,
                filterResults: FilterResults
            ) {
                submitList(filterResults.values as List<RecyclerItem>)
            }
        }
    }
}

private fun RecyclerItem.bind(binding: ViewDataBinding) {
    val isVariableFound = binding.setVariable(variableId, data)
    if (isVariableFound.not()) {
        throw IllegalStateException(
            buildErrorMessage(variableId, binding)
        )
    }
}

private fun buildErrorMessage(
    variableId: Int,
    binding: ViewDataBinding
): String {
    val variableName = DataBindingUtil.convertBrIdToString(variableId)
    val className = binding::class.simpleName
    return "Failed to find variable='$variableName' in the following databinding layout: $className"
}
