package com.tverona.scpanywhere.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for querying & manipulating various database entries
 */
@Dao
interface ScpDatabaseEntriesDao {
    @Query("select * from scpentries order by title asc")
    suspend fun getScpEntries(): List<ScpDatabaseEntry>?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScpEntries(scpDatabaseEntries: List<ScpDatabaseEntry>)

    @Query("delete from scpentries")
    suspend fun deleteScpEntries()

    @Query("select * from taleentries order by title asc")
    suspend fun getTaleEntries(): List<TaleDatabaseEntry>?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaleEntries(taleDatabaseEntries: List<TaleDatabaseEntry>)

    @Query("delete from taleentries")
    suspend fun deleteTaleEntries()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setTalesNum(scpDataEntryEntryTalesNum: ScpDataEntryTalesNum)

    @Query("select * from scpentries_talesnum limit 1")
    suspend fun getTalesNum(): ScpDataEntryTalesNum?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setLastUpdate(scpDataEntryLastUpdate: ScpDataEntryLastUpdate)

    @Query("select * from scpentries_lastupdate limit 1")
    suspend fun getLastUpdate(): ScpDataEntryLastUpdate?
}