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
        context.resources.getInteger(R.integer.AppTheme_Red) to R.style.AppTheme_Red,
        context.resources.getInteger(R.integer.AppTheme_Pink) to R.style.AppTheme_Pink,
        context.resources.getInteger(R.integer.AppTheme_Purple) to R.style.AppTheme_Purple,
        context.resources.getInteger(R.integer.AppTheme_DeepPurple) to R.style.AppTheme_DeepPurple,
        context.resources.getInteger(R.integer.AppTheme_Indigo) to R.style.AppTheme_Indigo,
        context.resources.getInteger(R.integer.AppTheme_Blue) to R.style.AppTheme_Blue,
        context.resources.getInteger(R.integer.AppTheme_LightBlue) to R.style.AppTheme_LightBlue,
        context.resources.getInteger(R.integer.AppTheme_Cyan) to R.style.AppTheme_Cyan,
        context.resources.getInteger(R.integer.AppTheme_Teal) to R.style.AppTheme_Teal,
        context.resources.getInteger(R.integer.AppTheme_Green) to R.style.AppTheme_Green,
        context.resources.getInteger(R.integer.AppTheme_LightGreen) to R.style.AppTheme_LightGreen,
        context.resources.getInteger(R.integer.AppTheme_Lime) to R.style.AppTheme_Lime,
        context.resources.getInteger(R.integer.AppTheme_Yellow) to R.style.AppTheme_Yellow,
        context.resources.getInteger(R.integer.AppTheme_Amber) to R.style.AppTheme_Amber,
        context.resources.getInteger(R.integer.AppTheme_Orange) to R.style.AppTheme_Orange,
        context.resources.getInteger(R.integer.AppTheme_DeepOrange) to R.style.AppTheme_DeepOrange,
        context.resources.getInteger(R.integer.AppTheme_Brown) to R.style.AppTheme_Brown,
        context.resources.getInteger(R.integer.AppTheme_Gray) to R.style.AppTheme_Gray,
        context.resources.getInteger(R.integer.AppTheme_BlueGray) to R.style.AppTheme_BlueGray
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
            context.resources.getIntArray(R.array.style_to_light_vs_darK_theme)
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