package com.tverona.scpanywhere.repositories

import com.tverona.scpanywhere.database.StatsDao
import javax.inject.Inject

/**
 * Repository of stats, backed by stats DAO
 */
class StatsRepository @Inject constructor(private val statsDao: StatsDao) {
    val totalReadTimeSecs = statsDao.getTotalReadTimeSecs()
    suspend fun addReadTime(url: String, readTimeSecs: Long) : Long =
        statsDao.addReadTime(url, readTimeSecs)
}
