package com.tverona.scpanywhere.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.observe
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.databinding.FragmentStatsBinding
import com.tverona.scpanywhere.utils.StringFormatter
import com.tverona.scpanywhere.utils.logv
import com.tverona.scpanywhere.viewmodels.ScpDataViewModel
import com.tverona.scpanywhere.viewmodels.UrlEntryClickable

/**
 * Fragment to display reading stats
 */
class StatsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val scpDataViewModel: ScpDataViewModel by activityViewModels()

        return FragmentStatsBinding.inflate(inflater, container, false)
            .also { binding ->
                scpDataViewModel.totalReadTimeSecs.observe(viewLifecycleOwner) {
                    val secs = it?.toLong()
                    val duration = StringFormatter.durationFromSec(secs)
                    logv("Total read duration: $secs secs, durartion: $duration")
                    binding.readTime.text = getString(
                        R.string.total_read_time,
                        duration.first,
                        duration.second,
                        duration.third
                    )
                }
                scpDataViewModel.allRead.observe(viewLifecycleOwner) {
                    val readScp =
                        it.filter { scpDataViewModel.scpEntriesByUrl.value?.containsKey((it.data as UrlEntryClickable).urlEntry.url) == true }.size
                    val readTales =
                        it.filter { scpDataViewModel.taleEntriesByUrl.value?.containsKey((it.data as UrlEntryClickable).urlEntry.url) == true }.size

                    var percentScp = 1.0f
                    var percentTales = 1.0f
                    var totalScp = scpDataViewModel.allScpEntries.value?.size
                    if (totalScp != null && totalScp != 0) {
                        percentScp = readScp.toFloat() / totalScp
                    } else {
                        totalScp = 0
                    }
                    var totalTales = scpDataViewModel.allTaleEntries.value?.size
                    if (totalTales != null && totalTales != 0) {
                        percentTales = readTales.toFloat() / totalTales
                    } else {
                        totalTales = 0
                    }
                    binding.scpReadStats.text =
                        getString(R.string.scp_read_stats, readScp, totalScp, percentScp)
                    binding.talesReadStats.text =
                        getString(R.string.tales_read_stats, readTales, totalTales, percentTales)
                }
            }
            .root
    }
}