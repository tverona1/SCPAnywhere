package com.tverona.scpanywhere.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a row in the stats table
 */
@Entity(tableName = "stats")
data class StatEntry(
    @PrimaryKey @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "read_time_secs") var readTimeSecs: Long
)