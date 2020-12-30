package com.tverona.scpanywhere.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.NumberPicker
import androidx.preference.DialogPreference

/**
 * Simple number picker preference
 */
class NumberPickerPreference(context: Context?, attrs: AttributeSet?) :
    DialogPreference(context, attrs) {

    var minValue = 0
    var maxValue = 100
    var initialValue = 0
    var formatter: NumberPicker.Formatter? = null
    var wrapSelectorWheel: Boolean = true

    override fun getSummary(): CharSequence {
        return if (formatter != null) {
            formatter!!.format(getPersistedInt(initialValue))
        } else {
            getPersistedInt(initialValue).toString()
        }
    }

    fun getPersistedInt() = super.getPersistedInt(initialValue)

    fun doPersistInt(value: Int) {
        super.persistInt(value)
        notifyChanged()
    }
}