package com.tverona.scpanywhere.ui

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.databinding.FragmentScplistBinding
import com.tverona.scpanywhere.recycleradapter.DataBindingRecyclerAdapter
import com.tverona.scpanywhere.recycleradapter.RecyclerItem
import com.tverona.scpanywhere.recycleradapter.setRecyclerViewItems
import com.tverona.scpanywhere.utils.logv
import com.tverona.scpanywhere.viewmodels.ScpDataViewModel
import com.tverona.scpanywhere.viewmodels.UrlEntryClickable
import com.tverona.scpanywhere.viewmodels.UrlEntryComparator
import com.tverona.scpanywhere.viewmodels.WebDataViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment to display list of url entries
 */
class ScpListItemFragment : Fragment() {
    private val viewModel: ScpDataViewModel by activityViewModels()
    private lateinit var binding: FragmentScplistBinding

    companion object {
        const val seriesKey = "seriesKey"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val webViewModel: WebDataViewModel by activityViewModels()
        viewModel.seriesKey.value = arguments?.getString(seriesKey)

        viewModel.onEntryClick.observe(viewLifecycleOwner) { item ->
            if (null != item) {
                logv("click item: ${item.title}, ${item.url}")
                viewModel.clearOnClickEntry()
                webViewModel.url.value = item.url
                findNavController(requireActivity(), R.id.nav_host_fragment)
                    .navigate(R.id.nav_home)
            }
        }

        setHasOptionsMenu(true)

        binding = FragmentScplistBinding.inflate(inflater, container, false)
            .also {
                it.viewModel = viewModel
                it.lifecycleOwner = viewLifecycleOwner
                it.recyclerView.layoutManager = object :
                    LinearLayoutManager(activity, VERTICAL, false) {
                    override fun onLayoutCompleted(state: RecyclerView.State?) {
                        // Scroll to top after finishing displaying the list
                        super.onLayoutCompleted(state)
                        it.recyclerView.scrollToPosition(0)
                    }
                }
            }

        return binding.root
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.urllist_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val items = (binding.recyclerView.adapter as? DataBindingRecyclerAdapter)?.currentList
        var sorted: List<RecyclerItem>?

        // Handle sorting
        return when (item.itemId) {
            R.id.alphabetical -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        sorted = items?.sortedWith(
                            UrlEntryComparator
                        )
                    }
                    withContext(Dispatchers.Main) {
                        setRecyclerViewItems(binding.recyclerView, sorted)
                    }
                }
                true
            }
            R.id.rating -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        sorted = items?.sortedByDescending {
                            val entry = it.data as UrlEntryClickable
                            entry.urlEntry.rating
                        }
                    }
                    withContext(Dispatchers.Main) {
                        setRecyclerViewItems(binding.recyclerView, sorted)
                    }
                }
                true
            }
            R.id.unread -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        sorted = items?.sortedBy {
                            val entry = it.data as UrlEntryClickable
                            entry.isRead.get()
                        }
                    }
                    withContext(Dispatchers.Main) {
                        setRecyclerViewItems(binding.recyclerView, sorted)
                    }
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}