package com.tverona.scpanywhere.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a row in the taleentries table
 */
@Entity(tableName = "taleentries")
data class TaleDatabaseEntry(
    @PrimaryKey @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") var title: String,
    @ColumnInfo(name = "rating") var rating: Int?
)
