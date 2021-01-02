package com.tverona.scpanywhere.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation.findNavController
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.databinding.FragmentLocalassetsBinding
import com.tverona.scpanywhere.utils.logv
import com.tverona.scpanywhere.viewmodels.OfflineDataViewModel

/**
 * Fragment for displaying list of local (on-disk) downloaded assets
 */
class LocalFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val offlineDataViewModel: OfflineDataViewModel by activityViewModels()

        offlineDataViewModel.getLocalAssets()

        offlineDataViewModel.onDeleteClick.observe(viewLifecycleOwner) { item ->
            if (null != item) {
                offlineDataViewModel.clearOnDeleteClickItem()

                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.titleDeleteItem, item.name))
                    .setMessage(getString(R.string.descDeleteItem))
                    .setPositiveButton(
                        getString(android.R.string.ok)
                    ) { dialog, which ->
                        logv("Deleting file: ${item.path}")
                        offlineDataViewModel.deleteLocalAsset(item)
                    }
                    .setNegativeButton(
                        getString(android.R.string.cancel)
                    ) { dialog, which -> }
                    .show()
            }
        }

        return FragmentLocalassetsBinding.inflate(inflater, container, false)
            .also {
                it.viewModel = offlineDataViewModel
                it.lifecycleOwner = viewLifecycleOwner
                it.downloadFragmentButton.setOnClickListener {
                    findNavController(
                        requireActivity(),
                        R.id.nav_host_fragment
                    ).navigate(R.id.downloader)
                }
            }
            .root
    }
}