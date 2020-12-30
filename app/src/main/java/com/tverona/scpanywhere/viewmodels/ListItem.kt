package com.tverona.scpanywhere.viewmodels

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

/**
 * Represents a list item with title & url
 */
@Parcelize
data class ListItem(
    val title: String,
    val url: String
) : Parcelable