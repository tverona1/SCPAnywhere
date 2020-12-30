package com.tverona.scpanywhere.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

/**
 * Represents bookmark row in bookmarks table
 */
@JsonClass(generateAdapter = true)
@Entity(tableName = "bookmarks")
data class BookmarkEntry(
    @PrimaryKey @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "read") var read: Boolean,
    @ColumnInfo(name = "favorite") var favorite: Boolean
)