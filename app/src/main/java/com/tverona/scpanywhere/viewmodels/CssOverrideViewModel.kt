package com.tverona.scpanywhere.viewmodels

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.squareup.moshi.JsonClass
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.utils.logv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * View model that provides CSS content
 */
class CssOverrideViewModel(application: Application) : AndroidViewModel(application),
    SharedPreferences.OnSharedPreferenceChangeListener {
    private var sharedPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(getApplication())

    @JsonClass(generateAdapter = true)
    data class Style(val name: String, val path: String)

    @JsonClass(generateAdapter = true)
    data class Font(val name: String, val prefix: String)

    private var _cssMergedContent = MutableLiveData<String>()
    val cssMergedContent: LiveData<String> by this::_cssMergedContent

    private fun getResourceString(id: Int): String {
        return getApplication<Application>().getString(id)
    }

    private fun getResourceInteger(id: Int): Int {
        return getApplication<Application>().resources.getInteger(id)
    }

    /**
     * Coroutine to update current css contant
     */
    private fun updateContent() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val fontSizeScaleFactor = getResourceInteger(R.integer.font_size_scale_factor)
                val fontSize = (sharedPreferences.getInt(
                    getResourceString(R.string.font_size_key),
                    100 / fontSizeScaleFactor
                ) * fontSizeScaleFactor).toString() + "%"
                val font = sharedPreferences.getString(
                    getResourceString(R.string.font_key),
                    getResourceString(R.string.font_default)
                )!!
                val themePath = sharedPreferences.getString(
                    getResourceString(R.string.theme_key),
                    getResourceString(R.string.theme_default)
                )!!

                logv("Setting css with font size: $fontSize, font: $font, theme path: '$themePath'")

                // Get base css content
                val baseCssContent =
                    getApplication<Application>().assets.open(getResourceString(R.string.base_css_theme))
                        .bufferedReader().use {
                        it.readText()
                    }

                // Get css content
                var cssContent =
                    baseCssContent +
                            getApplication<Application>().assets.open(themePath).bufferedReader()
                                .use {
                                    it.readText()
                                }

                // Add font (if not default)
                if (!font.equals(getResourceString(R.string.font_default), true)) {
                    val fontTemplate =
                        getApplication<Application>().assets.open(getResourceString(R.string.font_template))
                            .bufferedReader().use {
                                it.readText()
                            }
                    cssContent += fontTemplate.replace("\${font-name}", font, true)
                }

                // Add font size
                val fontSizeTemplate =
                    getApplication<Application>().assets.open(getResourceString(R.string.font_size_template))
                        .bufferedReader().use {
                            it.readText()
                        }
                cssContent += fontSizeTemplate.replace("\${font-size}", fontSize, true)

                _cssMergedContent.postValue(cssContent)
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        when (key) {
            getResourceString(R.string.font_size_key) -> {
                updateContent()
            }

            getResourceString(R.string.font_key) -> {
                updateContent()
            }

            getResourceString(R.string.theme_key) -> {
                updateContent()
            }
        }
    }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        updateContent()
    }
}
