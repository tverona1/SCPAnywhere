package com.tverona.scpanywhere.utils

import android.widget.TextView
import androidx.databinding.BindingAdapter

@BindingAdapter("android:htmlText")
fun setHtmlTextValue(textView: TextView, htmlText: String?) {
    val result = StringFormatter.htmlText(htmlText)
    if (result != null) {
        textView.text = result
    }
}
