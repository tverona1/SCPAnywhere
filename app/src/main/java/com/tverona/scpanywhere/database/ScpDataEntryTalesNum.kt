package com.tverona.scpanywhere.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents the number of tale series
 */
@Entity(tableName = "scpentries_talesnum")
data class ScpDataEntryTalesNum(
    @PrimaryKey @ColumnInfo(name = "cardinal", defaultValue = "0") val cardinal: Int = 0,
    @ColumnInfo(name = "num", defaultValue = "0") val num: Int
)