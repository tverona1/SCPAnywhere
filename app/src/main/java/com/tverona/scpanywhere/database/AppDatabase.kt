package com.tverona.scpanywhere.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Application's database
 */
@Database(
    entities = arrayOf(
        BookmarkEntry::class,
        ScpDatabaseEntry::class,
        TaleDatabaseEntry::class,
        ScpDataEntryLastUpdate::class,
        ScpDataEntryTalesNum::class,
        StatEntry::class
    ), version = 1
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarksDao(): BookmarksDao
    abstract fun scpDatabaseEntriesDao(): ScpDatabaseEntriesDao
    abstract fun statsDao(): StatsDao
}