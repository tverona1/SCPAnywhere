package com.tverona.scpanywhere.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation.findNavController
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.databinding.FragmentReadBinding
import com.tverona.scpanywhere.viewmodels.ScpDataViewModel
import com.tverona.scpanywhere.viewmodels.WebDataViewModel

/**
 * Fragment to display list of read items
 */
class ReadItemFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val webViewModel: WebDataViewModel by activityViewModels()
        val scpDataViewModel: ScpDataViewModel by activityViewModels()

        scpDataViewModel.onEntryClick.observe(viewLifecycleOwner) { item ->
            if (null != item) {
                scpDataViewModel.clearOnClickEntry()
                webViewModel.url.value = item.url
                findNavController(requireActivity(), R.id.nav_host_fragment)
                    .navigate(R.id.nav_home)
            }
        }

        return FragmentReadBinding.inflate(inflater, container, false)
            .also {
                it.viewModel = scpDataViewModel
                it.lifecycleOwner = viewLifecycleOwner
            }
            .root
    }
}