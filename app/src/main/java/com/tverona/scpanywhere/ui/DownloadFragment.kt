package com.tverona.scpanywhere.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.databinding.FragmentDownloaderBinding
import com.tverona.scpanywhere.utils.StringFormatter
import com.tverona.scpanywhere.utils.await
import com.tverona.scpanywhere.utils.logv
import com.tverona.scpanywhere.viewmodels.OfflineDataViewModel
import kotlinx.coroutines.launch

/**
 * Fragment to manage downloadable assets for offline mode
 */
class DownloadFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val offlineDataViewModel: OfflineDataViewModel by activityViewModels()

        // Populate available storage locations
        offlineDataViewModel.populateStorageEntries()

        // Get latest release metadata
        offlineDataViewModel.downloadLatestReleaseMetadata()

        val binding = FragmentDownloaderBinding.inflate(inflater, container, false)
            .also {
                it.viewModel = offlineDataViewModel
                it.lifecycleOwner = viewLifecycleOwner
            }

        offlineDataViewModel.releaseMetadata.observe(viewLifecycleOwner) {
            if (it.isEmpty) {
                binding.downloadButton.isEnabled = false
                binding.downloadReleaseStatus.text = getString(
                    R.string.download_release_status_no_updates,
                    StringFormatter.dateFromDate(it.publishedAt)
                )
                binding.recyclerView.visibility = View.GONE
            } else {
                binding.downloadButton.isEnabled = true
                binding.downloadReleaseStatus.text = getString(
                    R.string.download_release_status,
                    StringFormatter.dateFromDate(it.publishedAt)
                )
                binding.recyclerView.visibility = View.VISIBLE
            }
        }

        offlineDataViewModel.isDownloadingOrResumable.observe(viewLifecycleOwner) {
            val (isDownloading, hasResumableDownloads) = it

            if (isDownloading) {
                logv("Downloading")
                binding.downloadButton.text = getString(R.string.pause_download)
                binding.cancelResumeButton.visibility = View.GONE
            } else if (hasResumableDownloads) {
                logv("Not downloading, has resumable downloads")
                binding.downloadButton.text = getString(R.string.resume)
                binding.cancelResumeButton.visibility = View.VISIBLE
            } else {
                logv("Not downloading, no resumable downloads")
                binding.downloadButton.text = getString(R.string.download)
                binding.cancelResumeButton.visibility = View.GONE
            }

            binding.cancelResumeButton.setOnClickListener {
                offlineDataViewModel.cleanupTempFiles()
            }

            binding.downloadButton.setOnClickListener { view ->
                if (isDownloading) {
                    offlineDataViewModel.cancelDownload()
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val title: String?
                        var description: String? = null

                        // If not enough storage left, bail
                        if (offlineDataViewModel.downloadSizeDelta.await() < 0) {
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
                        } else if (offlineDataViewModel.isChangingStorage.await()) {
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
        }

        return binding.root
    }
}