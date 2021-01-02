package com.tverona.scpanywhere.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.tverona.scpanywhere.R

/**
 * Helper to intialize theme
 */
class ThemeUtil(private val context: Context) {
    private val styleToAppTheme = mapOf(
        context.resources.getInteger(R.integer.AppTheme_SCP) to R.style.AppTheme_SCP,
        context.resources.getInteger(R.integer.AppTheme_Warm) to R.style.AppTheme_Warm,
        context.resources.getInteger(R.integer.AppTheme_Night) to R.style.AppTheme_Night,
        context.resources.getInteger(R.integer.AppTheme_Navy) to R.style.AppTheme_Navy,
        context.resources.getInteger(R.integer.AppTheme_EBook) to R.style.AppTheme_EBook
    )

    fun initializeTheme(themeValue: String?) {
        // Look up app theme based on style theme
        val index = context.resources.getStringArray(R.array.style_values).indexOf(themeValue)
        if (index < 0) {
            loge("Theme not found: $themeValue")
            return
        }

        val themeInt = context.resources.getIntArray(R.array.style_to_app_theme).getOrNull(index)
        if (null == themeInt) {
            loge("Theme not found in mapping array: theme value: $themeValue, index: $index")
            return
        }

        val themeId = styleToAppTheme[themeInt]
        if (null == themeId) {
            loge("Theme id not found for theme value: $themeValue, index: $index, theme int $themeInt")
            return
        }

        val isLightTheme =
            context.resources.getIntArray(R.array.style_to_light_vs_dark_theme)
                .getOrNull(index) == 0

        // Set the theme
        context.setTheme(themeId)

        // Set light / dark mode
        if (isLightTheme) {
            logv("Using light theme for theme value: $themeValue")
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } else {
            logv("Using dark theme for theme value: $themeValue")
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }
}