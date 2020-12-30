package com.tverona.scpanywhere.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.databinding.FragmentDownloaderBinding
import com.tverona.scpanywhere.utils.observeOnce
import com.tverona.scpanywhere.viewmodels.OfflineDataViewModel

/**
 * Fragment to manage downloadable assets for offline mode
 */
class DownloadFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val offlineDataViewModel: OfflineDataViewModel by activityViewModels()

        // Populate available storage locations
        offlineDataViewModel.populateStorageEntries()

        // Get latest release metadata
        offlineDataViewModel.downloadLatestReleaseMetadata()

        return FragmentDownloaderBinding.inflate(inflater, container, false)
            .also {
                it.viewModel = offlineDataViewModel
                it.lifecycleOwner = viewLifecycleOwner
                it.downloadButton.setOnClickListener { view ->
                    var title: String? = null
                    var description: String? = null

                    // If not enough storage left, bail
                    offlineDataViewModel.downloadSizeDelta.observeOnce(viewLifecycleOwner) {
                        if (offlineDataViewModel.downloadSizeDelta.value!! < 0) {
                            title = getString(R.string.titleNotEnoughSpace)
                            description = getString(R.string.descNotEnoughSpace)
                            AlertDialog.Builder(requireContext())
                                .setTitle(title)
                                .setMessage(description)
                                .setPositiveButton(
                                    getString(android.R.string.ok)
                                ) { dialog, which ->
                                }
                                .show()
                        } else if (offlineDataViewModel.isChangingStorage) {
                            // If already changing storage, ask if we should cancel
                            AlertDialog.Builder(requireContext())
                                .setTitle(getString(R.string.titleCancelChangingStorage))
                                .setMessage(getString(R.string.descCancelChangingStorage))
                                .setPositiveButton(
                                    getString(android.R.string.ok)
                                ) { dialog, which ->
                                    offlineDataViewModel.download()
                                }
                                .setNegativeButton(
                                    getString(android.R.string.cancel)
                                ) { dialog, which -> }
                                .show()
                        } else {
                            offlineDataViewModel.download()
                        }
                    }
                }
            }
            .root
    }
}