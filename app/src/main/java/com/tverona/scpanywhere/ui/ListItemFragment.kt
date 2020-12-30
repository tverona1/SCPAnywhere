package com.tverona.scpanywhere.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.observe
import androidx.navigation.Navigation.findNavController
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.databinding.FragmentListBinding
import com.tverona.scpanywhere.utils.logv
import com.tverona.scpanywhere.viewmodels.ListItem
import com.tverona.scpanywhere.viewmodels.ListItemViewModel
import com.tverona.scpanywhere.viewmodels.WebDataViewModel

/**
 * Fragment for displaying list of urls
 */
class ListItemFragment : Fragment() {
    private val viewModel: ListItemViewModel by activityViewModels()

    companion object {
        const val listArgument = "list"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val webViewModel: WebDataViewModel by activityViewModels()

        val listItemViewModel: ListItemViewModel by activityViewModels()
        val list = arguments?.getParcelableArrayList<ListItem>(listArgument)
        if (list != null) {
            listItemViewModel.loadData(list)
        }

        viewModel.onItemClick.observe(viewLifecycleOwner) { item ->
            // Note: We need to check for nullability since we clear the livedata. Kotlin warning is incorrect here.
            if (null != item) {
                logv("click item: ${item.title}, ${item.url}")
                viewModel.clearOnClickItem()
                webViewModel.url.value = item.url
                findNavController(requireActivity(), R.id.nav_host_fragment)
                    .navigate(R.id.nav_home)
            }
        }

        return FragmentListBinding.inflate(inflater, container, false)
            .also {
                it.viewModel = viewModel
                it.lifecycleOwner = viewLifecycleOwner
            }
            .root
    }
}