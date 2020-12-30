package com.tverona.scpanywhere.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a row in scpentries table
 */
@Entity(tableName = "scpentries")
data class ScpDatabaseEntry(
    @PrimaryKey @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "series") val series: String,
    @ColumnInfo(name = "title") var title: String,
    @ColumnInfo(name = "name") var name: String,
    @ColumnInfo(name = "rating") var rating: Int?
)
