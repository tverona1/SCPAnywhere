package com.tverona.scpanywhere.repositories

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.tverona.scpanywhere.R
import dagger.hilt.android.qualifiers.ApplicationContext

class OfflineModeRepository constructor(
    @ApplicationContext val context: Context
) {
    private var sharedPreferences: SharedPreferences
    private val _offlineMode = MutableLiveData<Boolean>(false)
    val offlineMode: LiveData<Boolean> = _offlineMode

    private var sharedPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == context.getString(R.string.offline_mode_key)) {
                _offlineMode.value = getOfflineMode()
            }
        }

    private fun getOfflineMode(): Boolean {
        return sharedPreferences.getBoolean(context.getString(R.string.offline_mode_key), false)
    }

    init {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPreferenceListener)
        _offlineMode.value = getOfflineMode()
    }
}
