package com.tverona.scpanywhere.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.SimpleAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.databinding.FragmentExportBinding

/**
 * Dialog fragment for exporting saved data to given [inputFileName] file path. User's choice is invoked on [exportActionListener] call back.
 */
class ExportFragment(
    private val inputFileName: String,
    private val exportActionListener: ExportActionListener
) : DialogFragment() {
    interface ExportActionListener {
        fun onExportToShare(fileName: String)
        fun onExportToDocumentStorage(fileName: String)
    }

    private var selectedPosition = -1

    /**
     * Spinner for showing icon & name
     */
    private class NameIconSpinnerAdapter(
        private val context: Context, private val data: List<Map<String, Any>>,
        resource: Int, from: Array<String>, to: IntArray
    ) :
        SimpleAdapter(context, data, resource, from, to) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.export_item, parent, false)
            (view.findViewById(R.id.text) as TextView).text = data[position]["name"] as String
            (view.findViewById(R.id.icon) as ImageView).setBackgroundResource(data[position]["icon"] as Int)
            return view
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = FragmentExportBinding.inflate(layoutInflater)

        binding.fileName.setText(inputFileName)

        // Show export options
        val exportOptions = listOf(
            mapOf(
                "name" to getString(R.string.document_storage),
                "icon" to R.drawable.baseline_description_24
            ),
            mapOf("name" to getString(R.string.share), "icon" to R.drawable.baseline_share_24)
        )

        val adapter = NameIconSpinnerAdapter(
            requireContext(),
            exportOptions,
            R.layout.export_item,
            arrayOf("name", "icon"), intArrayOf(R.id.text, R.id.icon)
        )
        binding.spinner.adapter = adapter

        binding.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                selectedPosition = position
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedPosition = -1
            }

        }

        val alert = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.export_data))
            .setPositiveButton(android.R.string.ok) { dialogInterface: DialogInterface, which: Int ->
                when (selectedPosition) {
                    0 -> {
                        exportActionListener.onExportToDocumentStorage(binding.fileName.text.toString())
                    }
                    1 -> {
                        exportActionListener.onExportToShare(binding.fileName.text.toString())
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setView(binding.root)
            .create()

        return alert
    }
}