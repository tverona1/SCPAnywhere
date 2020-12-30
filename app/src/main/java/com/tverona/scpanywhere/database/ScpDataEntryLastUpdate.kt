package com.tverona.scpanywhere.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents the last update timestamp
 */
@Entity(tableName = "scpentries_lastupdate")
data class ScpDataEntryLastUpdate(
    @PrimaryKey @ColumnInfo(name = "cardinal", defaultValue = "0") val cardinal: Int = 0,
    @ColumnInfo(name = "lastupdate", defaultValue = "0") val lastUpdate: Long
)
