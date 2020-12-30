package com.tverona.scpanywhere.ui

import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.EditText
import android.widget.NumberPicker
import androidx.preference.PreferenceDialogFragmentCompat

/**
 * Number picker preference dialog
 */
class NumberPickerPreferenceDialog : PreferenceDialogFragmentCompat() {
    private lateinit var numberPicker: NumberPicker

    override fun onCreateDialogView(context: Context?): View {
        val numberPickerPreference = preference as NumberPickerPreference

        numberPicker = NumberPicker(context)

        numberPicker.minValue = numberPickerPreference.minValue
        numberPicker.maxValue = numberPickerPreference.maxValue
        numberPicker.wrapSelectorWheel = numberPickerPreference.wrapSelectorWheel
        numberPicker.setFormatter(numberPickerPreference.formatter)

        numberPicker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS

        val editView = numberPicker.getChildAt(0)

        if (editView is EditText) {
            // Remove default input filter
            editView.filters = arrayOfNulls<InputFilter>(0)
        }
        return numberPicker
    }

    override fun onBindDialogView(view: View?) {
        super.onBindDialogView(view)
        numberPicker.value = (preference as NumberPickerPreference).getPersistedInt()
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            numberPicker.clearFocus()
            val newValue: Int = numberPicker.value
            if (preference.callChangeListener(newValue)) {
                (preference as NumberPickerPreference).doPersistInt(newValue)
                preference.summary
            }
        }
    }

    companion object {
        fun newInstance(key: String): NumberPickerPreferenceDialog {
            val fragment = NumberPickerPreferenceDialog()
            val bundle = Bundle(1)
            bundle.putString(ARG_KEY, key)
            fragment.arguments = bundle

            return fragment
        }
    }
}