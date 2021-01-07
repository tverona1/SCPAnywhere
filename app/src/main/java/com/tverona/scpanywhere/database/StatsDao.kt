package com.tverona.scpanywhere.database

import androidx.lifecycle.LiveData
import androidx.room.*
import com.tverona.scpanywhere.utils.await

/**
 * DAO for querying & manipulating stats
 */
@Dao
interface StatsDao {
    @Query("select sum(read_time_secs) from stats")
    fun getTotalReadTimeSecs(): LiveData<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(statEntry: StatEntry): Long

    @Update
    suspend fun update(statEntry: StatEntry)

    @Query("select * from stats where url = :url limit 1")
    fun getByUrl(url: String): LiveData<StatEntry?>

    suspend fun addReadTime(url: String, readTimeSecs: Long): Long {
        val entry = getByUrl(url).await()
        if (null == entry) {
            insert(StatEntry(url = url, readTimeSecs = readTimeSecs))
            return readTimeSecs
        } else {
            entry.readTimeSecs += readTimeSecs
            update(entry)
            return entry.readTimeSecs
        }
    }
}