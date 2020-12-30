package com.tverona.scpanywhere.utils

import android.view.View
import android.widget.TextView
import com.google.android.material.R
import com.google.android.material.snackbar.Snackbar


fun showSnackbar(v: View, message: String) {
    val snackbar = Snackbar.make(v, message, Snackbar.LENGTH_SHORT)
    val snackbarView: View = snackbar.getView()
    val snackTextView = snackbarView.findViewById<View>(R.id.snackbar_text) as TextView
    snackTextView.maxLines = 4
    snackbar.show()
}

fun showSnackbar(v: View, stringId: Int) {
    val snackbar = Snackbar.make(v, stringId, Snackbar.LENGTH_SHORT)
    val snackbarView: View = snackbar.getView()
    val snackTextView = snackbarView.findViewById<View>(R.id.snackbar_text) as TextView
    snackTextView.maxLines = 4
    snackbar.show()
}