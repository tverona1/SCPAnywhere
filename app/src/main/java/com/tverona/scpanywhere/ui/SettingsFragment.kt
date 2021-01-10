package com.tverona.scpanywhere.ui

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.utils.*
import com.tverona.scpanywhere.viewmodels.OfflineDataViewModel
import com.tverona.scpanywhere.viewmodels.ScpDataViewModel
import com.tverona.scpanywhere.viewmodels.TextToSpeechViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.android.synthetic.main.export_item.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Settings fragment
 */
@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {
    private val offlineDataViewModel: OfflineDataViewModel by activityViewModels()
    private val textToSpeechViewModel: TextToSpeechViewModel by activityViewModels()
    private val scpDataViewModel: ScpDataViewModel by activityViewModels()
    lateinit private var sharedPreferences: SharedPreferences
    lateinit private var fragmentView: View

    @Inject
    lateinit var textToSpeechProvider: TextToSpeechProvider

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        this.fragmentView = view

        // Refresh local assets
        offlineDataViewModel.getLocalAssets()

        val themePreference: ListPreference? = findPreference(getString(R.string.theme_key))
        if (themePreference != null) {
            themePreference.onPreferenceChangeListener = onThemeUpdated
        }

        // Set up font size preference
        val fontSizePreference: NumberPickerPreference? =
            findPreference(getString(R.string.font_size_key))
        if (fontSizePreference != null) {
            fontSizePreference.minValue = 4
            fontSizePreference.maxValue = 15
            fontSizePreference.initialValue = 5
            fontSizePreference.wrapSelectorWheel = false
            fontSizePreference.formatter =
                NumberPicker.Formatter { i: Int -> "${i * resources.getInteger(R.integer.font_size_scale_factor)}%" }
        }

        // Seet up storage location preference
        val storagePreference = findPreference<ListPreference>(getString(R.string.storage_key))
        if (storagePreference != null) {
            offlineDataViewModel.currentExternalStorage.observeOnce(viewLifecycleOwner) {
                initStoragePreference(storagePreference)
            }
        }

        // Set up export & import preferences
        val exportDataPreference = findPreference<Preference>(getString(R.string.export_data_key))
        exportDataPreference?.onPreferenceClickListener = exportDataPreferenceClickListener

        val importDataPreference = findPreference<Preference>(getString(R.string.import_data_key))
        importDataPreference?.onPreferenceClickListener = importDataPreferenceClickListener

        // Set up system speech setting preferences
        val systemSpeechPreference =
            findPreference<Preference>(getString(R.string.system_speech_settings_key))
        systemSpeechPreference?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                try {
                    val intent = Intent()
                    intent.action = "com.android.settings.TTS_SETTINGS"
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                } catch (e: Exception) {
                    loge("Error opening text-to-speech system settings", e)
                    showSnackbar(view, R.string.error_tts_settings)
                }
                true
            }

        // Set up offline vs online  preference
        val offlineDataPreference = findPreference<Preference>(getString(R.string.offline_data_key))
        offlineDataPreference?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                findNavController().navigate(R.id.local_assets)
                true
            }

        // Set up download preference
        val downloadDataPreference =
            findPreference<Preference>(getString(R.string.download_data_key))
        downloadDataPreference?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                findNavController().navigate(R.id.downloader)
                true
            }

        // Set up text to speech preference
        textToSpeechViewModel.initialized.observe(viewLifecycleOwner) {
            if (it) {
                val speechEnginePreference =
                    findPreference<ListPreference>(getString(R.string.speech_engine_key))
                if (speechEnginePreference != null) {
                    initSpeechEnginePreference(speechEnginePreference)
                }

                val voicePreference = findPreference<ListPreference>(getString(R.string.voice_key))
                if (voicePreference != null) {
                    initVoicePreference(voicePreference)
                }

                val pitchPreference = findPreference<ListPreference>(getString(R.string.pitch_key))
                if (pitchPreference != null) {
                    initPitchPreference(pitchPreference)
                }

                val speechRatePreference =
                    findPreference<ListPreference>(getString(R.string.speech_rate_key))
                if (speechRatePreference != null) {
                    initSpeechRatePreference(speechRatePreference)
                }
            }
        }

        // Set up auto mark read preferences
        val autoMarkReadPreference =
            findPreference<ListPreference>(getString(R.string.auto_mark_read_key))
        val autoMarkReadTimePreference =
            findPreference<ListPreference>(getString(R.string.auto_mark_read_time_key))
        autoMarkReadTimePreference?.isEnabled = !sharedPreferences.getString(
            getString(R.string.auto_mark_read_key),
            getString(R.string.auto_mark_read_off)
        ).equals(getString(R.string.auto_mark_read_off))
        autoMarkReadPreference?.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as String
            autoMarkReadTimePreference?.isEnabled =
                !value.equals(getString(R.string.auto_mark_read_off))
            true
        }
    }

    /**
     * Initialize storage location preference
     */
    private fun initStoragePreference(storagePreference: ListPreference) {
        storagePreference.summaryProvider = null

        val storageMetadata = offlineDataViewModel.currentExternalStorage.value!!
        storagePreference.summary = storageMetadata.name
        storagePreference.value = storageMetadata.path

        // Refresh storage entries (in case size changed, etc)
        offlineDataViewModel.localItemsSize.observe(viewLifecycleOwner) {
            offlineDataViewModel.populateStorageEntries()
            storagePreference.entries =
                offlineDataViewModel.externalStorageMetadata.value!!.map { it.name }.toTypedArray()
            storagePreference.entryValues =
                offlineDataViewModel.externalStorageMetadata.value!!.map { it.path }.toTypedArray()
            storagePreference.onPreferenceChangeListener = onStorageUpdated
            storagePreference.summary = storagePreference.entry
        }

        offlineDataViewModel.changedStorageLocation.observe(viewLifecycleOwner) {
            val curStorage = offlineDataViewModel.currentExternalStorage.value!!
            logv("Updating storage location preference to ${curStorage.name}")
            storagePreference.value = curStorage.path
            storagePreference.summary = curStorage.name
        }
    }

    private val exportDataPreferenceClickListener =
        Preference.OnPreferenceClickListener {
            val dialog = ExportFragment(exportDocumentFileName, exportActionListener = object :
                ExportFragment.ExportActionListener {
                override fun onExportToShare(fileName: String) {
                    exportToShare(fileName)
                }

                override fun onExportToDocumentStorage(fileName: String) {
                    exportToDocument(fileName)
                }
            })
            dialog.show(requireActivity().supportFragmentManager, "ExportFragment")
            true
        }

    private var importDataPreferenceClickListener =
        Preference.OnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/json"
                addCategory(Intent.CATEGORY_OPENABLE)
            }

            try {
                startActivityForResult(
                    Intent.createChooser(
                        intent,
                        getString(R.string.select_import_file)
                    ), IMPORT_FILE_INTENT
                )
            } catch (e: Exception) {
                showSnackbar(fragmentView, R.string.no_file_manager)
            }

            true
        }

    private fun initSpeechEnginePreference(speechEnginePreference: ListPreference) {
        val engines = textToSpeechProvider.engines.sortedBy { it.label }
        speechEnginePreference.entries = engines.map { it.label }.toTypedArray()
        speechEnginePreference.entryValues = engines.map { it.name }.toTypedArray()

        // Persist default value if needed
        val persistedSpeechEngine = sharedPreferences.getString(
            getString(R.string.speech_engine_key),
            null
        )
        if (null == persistedSpeechEngine) {
            with(sharedPreferences.edit()) {
                putString(getString(R.string.speech_engine_key), textToSpeechProvider.defaultEngine)
                apply()
            }
            speechEnginePreference.value = textToSpeechProvider.defaultEngine
        }
    }

    private fun initVoicePreference(voicePreference: ListPreference) {
        val voices = textToSpeechProvider.voices
        val voicesList = voices.toList().filterNotNull()
            .sortedBy { it.name }
            .distinctBy { it.locale.displayName }
        val voiceNames = voicesList.map { it.name }.toTypedArray()

        val voiceDisplayNames = voicesList.map {
            if (it.isNetworkConnectionRequired) {
                "${it.locale.displayName} (${getString(R.string.voice_requires_network)})"
            } else {
                it.locale.displayName
            }
        }.toTypedArray()

        voicePreference.entries = voiceDisplayNames
        voicePreference.entryValues = voiceNames

        // Persists default value if needed
        val persistedSpeechVoice = sharedPreferences.getString(getString(R.string.voice_key), null)
        if (null == persistedSpeechVoice) {
            with(sharedPreferences.edit()) {
                putString(getString(R.string.voice_key), textToSpeechProvider.voice?.name)
                apply()
            }
            voicePreference.value = textToSpeechProvider.voice?.name
        }
    }

    private fun initPitchPreference(pitchPreference: ListPreference) {
        val pitches = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f, 4.0f, 5.0f)
        val pitchStrings = pitches.map { it.toString() }.toTypedArray()

        pitchPreference.entries = pitchStrings
        pitchPreference.entryValues = pitchStrings
    }

    private fun initSpeechRatePreference(speechRatePreference: ListPreference) {
        val speechRates = listOf(
            0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f,
            4.0f, 5.0f
        )
        val speechRateStrings = speechRates.map { it.toString() }.toTypedArray()

        speechRatePreference.entries = speechRateStrings
        speechRatePreference.entryValues = speechRateStrings
    }

    private val onStorageUpdated =
        Preference.OnPreferenceChangeListener { preference, newValue ->
            logv("Updating storage preference")
            var cancelOnly = false
            val listPreference = preference as ListPreference

            offlineDataViewModel.localItems.observeOnce(viewLifecycleOwner) {
                var title: String? = null
                var description: String? = null
                val newPath = newValue as String

                if (offlineDataViewModel.isChangingStorage) {
                    // Changing storage, ask if we should cancel
                    title = getString(R.string.titleCancelChangingStorage)
                    description = getString(R.string.descCancelChangingStorage)
                    cancelOnly = true
                } else if (newPath.equals(listPreference.value, ignoreCase = true)) {
                    // No-op
                    return@observeOnce
                } else if (offlineDataViewModel.isDownloading) {
                    // Downloading, ask if we should cancel
                    title = getString(R.string.titleCancelDownlading)
                    description = getString(R.string.descCancelDownloading)
                } else if (offlineDataViewModel.localItems.value?.size!! > 0) {
                    // Local items exist, ask for confirmation
                    title = getString(R.string.titleConfirmChangeStorage)
                    description = getString(R.string.descConfirmChangeStorage)
                }

                if (null != title) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(title)
                        .setMessage(description)
                        .setPositiveButton(
                            getString(android.R.string.ok)
                        ) { dialog, which ->
                            changeStorageLocation(
                                listPreference,
                                newPath,
                                cancelOnly
                            )
                        }
                        .setNegativeButton(
                            getString(android.R.string.cancel)
                        ) { dialog, which -> }
                        .show()
                } else {
                    changeStorageLocation(listPreference, newPath, cancelOnly)
                }
            }
            false
        }

    private val onThemeUpdated =
        Preference.OnPreferenceChangeListener { preference, newValue ->
            requireActivity().recreate()
            true
        }

    private fun changeStorageLocation(
        storagePreference: ListPreference,
        path: String,
        cancelOnly: Boolean = false
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Cancel any on-going operations
            offlineDataViewModel.cancelDownloadSync()
            offlineDataViewModel.cancelChangeStorageSync()

            if (!cancelOnly) {
                // If not enough space, alert user
                val externalStorageMetadata =
                    offlineDataViewModel.externalStorageMetadata.value!!.firstOrNull {
                        it.path.equals(path, ignoreCase = true)
                    }

                if (null != externalStorageMetadata && offlineDataViewModel.localItemsSize.value != null &&
                    offlineDataViewModel.localItemsSize.value!! > externalStorageMetadata.usableSpace
                ) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.titleNotEnoughSpace))
                        .setMessage(getString(R.string.descNotEnoughSpace))
                        .setPositiveButton(
                            getString(android.R.string.ok)
                        ) { dialog, which -> }
                        .show()
                } else {
                    offlineDataViewModel.changeStorageLocation(storagePreference.value, path)
                }
            }
        }
    }

    val exportDocumentFileName: String
        get() {
            return "scp_${StringFormatter.fileNameFromCurrentTime()}.json"
        }

    private fun exportToDocument(fileName: String) {
        GlobalScope.launch {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(
                    Intent.EXTRA_TITLE,
                    fileName
                )
            }
            try {
                startActivityForResult(intent, EXPORT_FILE_INTENT)
            } catch (e: Exception) {
                loge("Error starting activity for ACTION_CREATE_DOCUMENT", e)
                showSnackbar(fragmentView, R.string.error_export_activity)
            }
        }
    }

    private fun exportToShare(fileName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val file = saveExportDataToCacheFile(fileName)
            if (null == file) {
                return@launch
            }

            val contentUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )

            logv("Sharing $contentUri (path: ${file.absolutePath}")

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "${getString(R.string.scp_export_subject)}: fileName"
                )
                putExtra(Intent.EXTRA_STREAM, contentUri)

                // We use text/plain to maximize number of clients to share with
                type = "text/plain"
            }

            val chooser = Intent.createChooser(
                shareIntent, getString(R.string.scp_export_subject)
            )

            val resolveInfoList = requireContext().packageManager
                .queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)

            resolveInfoList.forEach {
                val packageName = it.activityInfo.packageName
                requireContext().grantUriPermission(
                    packageName,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            try {
                requireContext().startActivity(chooser)
            } catch (e: Exception) {
                loge("Error starting activity for ACTION_SEND", e)
                showSnackbar(fragmentView, R.string.error_share_activity)
            }
        }
    }

    private suspend fun saveExportDataToCacheFile(fileName: String): File? {
        val file = File(
            requireContext().cacheDir,
            fileName
        )
        try {
            val json = scpDataViewModel.exportBookmarksAsJson()
            file.writeText(json)
            return file
        } catch (e: Exception) {
            loge("Error creating export file: ${file.absolutePath}", e)
            file.truncateAndDelete()
        }

        return null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            EXPORT_FILE_INTENT -> {
                if (resultCode == RESULT_OK && data != null) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        data.data?.let { uri -> exportData(uri) }
                    }
                }
            }

            IMPORT_FILE_INTENT -> {
                if (resultCode == RESULT_OK && data != null) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        data.data?.let { uri -> importData(uri) }
                    }
                }
            }

            else ->
                super.onActivityResult(requestCode, resultCode, data)
        }
    }

    suspend fun exportData(source: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val json = scpDataViewModel.exportBookmarksAsJson()
                requireContext().contentResolver.openOutputStream(source)
                    ?.use { stream -> stream.write(json.toByteArray()) }
            } catch (e: Exception) {
                loge("failed to export file: $source")
                showSnackbar(fragmentView, R.string.error_export_activity)
            }
        }
    }

    suspend fun importData(source: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val json = requireContext().contentResolver.openInputStream(source)
                    .use { stream -> stream?.bufferedReader().use { it?.readText() } }
                if (json != null) {
                    scpDataViewModel.importBookmarksFromJson(json)
                } else {
                }

                withContext(Dispatchers.Main) {
                    showSnackbar(fragmentView, R.string.imported_data)
                }
            } catch (e: Exception) {
                loge("failed to import file: $source", e)
                withContext(Dispatchers.Main) {
                    showSnackbar(
                        fragmentView,
                        R.string.failed_import_data
                    )
                }
            }
        }
    }

    private val DIALOG_FRAGMENT_TAG = "NumberPickerDialog"
    override fun onDisplayPreferenceDialog(preference: Preference?) {
        if (parentFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG) != null) {
            return
        }
        if (preference is NumberPickerPreference) {
            val dialog = NumberPickerPreferenceDialog.newInstance(preference.key)
            dialog.setTargetFragment(this, 0)
            dialog.show(parentFragmentManager, DIALOG_FRAGMENT_TAG)
        } else
            super.onDisplayPreferenceDialog(preference)
    }

    companion object {
        const val EXPORT_FILE_INTENT = 1
        const val IMPORT_FILE_INTENT = 2
    }
}