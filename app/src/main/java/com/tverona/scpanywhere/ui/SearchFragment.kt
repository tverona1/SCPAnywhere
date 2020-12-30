package com.tverona.scpanywhere.ui

import android.content.Context
import android.os.Bundle
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.databinding.FragmentSearchBinding
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
 * Fragment for searching entries
 */
class SearchFragment : Fragment() {
    private lateinit var binding: FragmentSearchBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val args = arguments
        val isScpSearch = args?.getBoolean(getString(R.string.is_scp_search))

        val webViewModel: WebDataViewModel by activityViewModels()
        val viewModel: ScpDataViewModel by activityViewModels()

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

        binding = FragmentSearchBinding.inflate(inflater, container, false)
            .also {
                it.lifecycleOwner = viewLifecycleOwner

                setRecyclerViewItems(
                    it.recyclerView,
                    if (isScpSearch == true) viewModel.allScpEntries.value else viewModel.allTaleEntries.value
                )

                it.recyclerView.layoutManager = object :
                    LinearLayoutManager(activity, VERTICAL, false) {
                    override fun onLayoutCompleted(state: RecyclerView.State?) {
                        // Scroll to top after finishing displaying the list
                        super.onLayoutCompleted(state)
                        it.recyclerView.scrollToPosition(0)
                    }
                }

                it.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String): Boolean {
                        showSoftwareKeyboard(false)
                        return true
                    }

                    override fun onQueryTextChange(newText: String): Boolean {
                        if (null == it.recyclerView.adapter) {
                            return false
                        }
                        (it.recyclerView.adapter as DataBindingRecyclerAdapter).filter.filter(
                            newText
                        )
                        return true
                    }
                })
            }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        binding.search.setQuery("", false)
    }

    override fun onPause() {
        super.onPause()
        showSoftwareKeyboard(false)
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

    private fun showSoftwareKeyboard(showKeyboard: Boolean) {
        val inputManager =
            requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val currentFocusedView = requireActivity().currentFocus
        currentFocusedView?.let {
            inputManager.hideSoftInputFromWindow(
                currentFocusedView.windowToken,
                if (showKeyboard) InputMethodManager.SHOW_FORCED else InputMethodManager.HIDE_NOT_ALWAYS
            )
        }
    }
}