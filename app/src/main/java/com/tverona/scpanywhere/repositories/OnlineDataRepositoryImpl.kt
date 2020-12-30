package com.tverona.scpanywhere.repositories

import com.tverona.scpanywhere.database.*
import com.tverona.scpanywhere.onlinedatasource.RatingsDownloader
import com.tverona.scpanywhere.onlinedatasource.ScpListDownloader
import com.tverona.scpanywhere.onlinedatasource.TaleListDownloader
import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logv
import com.tverona.scpanywhere.viewmodels.UrlEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class OnlineDataRepositoryImpl @Inject constructor(
    private val scpDatabaseEntriesDao: ScpDatabaseEntriesDao,
    private val scpListDownloader: ScpListDownloader,
    private val taleListDownloader: TaleListDownloader,
    private val ratingsDownloader: RatingsDownloader
) : OnlineDataRepository {
    override suspend fun getScpEntries(): List<UrlEntry> {
        val entries = scpDatabaseEntriesDao.getScpEntries()
        if (null != entries) {
            val output = entries.map {
                UrlEntry(
                    url = it.url,
                    series = it.series,
                    title = it.title,
                    name = it.name,
                    rating = it.rating
                )
            }
            return UrlEntry.linkEntries(output)
        } else {
            return listOf<UrlEntry>()
        }
    }

    override suspend fun getTaleEntries(): List<UrlEntry> {
        val entries = scpDatabaseEntriesDao.getTaleEntries()
        if (null != entries) {
            return entries.map {
                UrlEntry(
                    url = it.url,
                    title = it.title,
                    rating = it.rating,
                    series = null,
                    name = null
                )
            }.sortedBy { it.title }
        } else {
            return listOf<UrlEntry>()
        }
    }

    override suspend fun getTalesNum(): Int {
        val entry = scpDatabaseEntriesDao.getTalesNum()
        if (null != entry) {
            return entry.num
        } else {
            return 0
        }
    }

    private fun refreshTalesNum(): Int {
        try {
            return scpListDownloader.getTalesNum()
        } catch (e: Exception) {
            loge("Error refreshing tales num", e)
        }

        return 0
    }

    private fun downloadRatings(): RatingsDownloader.EntryRatings? {
        try {
            return ratingsDownloader.download()
        } catch (e: Exception) {
            loge("Error downloading ratings")
        }

        return null
    }

    override suspend fun getLastUpdate(): Long {
        val entry = scpDatabaseEntriesDao.getLastUpdate()
        if (null != entry) {
            return entry.lastUpdate
        } else {
            return 0
        }
    }

    private suspend fun refreshTaleEntries(ratings: RatingsDownloader.EntryRatings?) {
        logv("Refreshing tale titles")

        try {
            val list = taleListDownloader.download()
            scpDatabaseEntriesDao.deleteTaleEntries()
            scpDatabaseEntriesDao.insertTaleEntries(list.map {
                TaleDatabaseEntry(
                    url = it.url,
                    title = it.title,
                    rating = ratings?.ratings?.get(it.url)
                )
            })
        } catch (e: Exception) {
            loge("Error refreshing tale titles", e)
        }
    }

    private suspend fun refreshScpEntries(ratings: RatingsDownloader.EntryRatings?): Boolean = withContext(Dispatchers.IO) {
        withContext(Dispatchers.IO) {
            logv("Refreshing scp list")

            try {
                val list = scpListDownloader.download()
                if (list.size == 0) {
                    // If we got nothing back, consider this a failure to refresh
                    return@withContext false
                }

                scpDatabaseEntriesDao.deleteScpEntries()
                scpDatabaseEntriesDao.insertScpEntries(list.map {
                    ScpDatabaseEntry(
                        url = it.url,
                        title = it.title,
                        series = it.series!!,
                        name = it.name!!,
                        rating = ratings?.ratings?.get(it.url)
                    )
                })
            } catch (e: Exception) {
                loge("Error refreshing scp list", e)
            }

            return@withContext true
        }
    }

    override suspend fun refresh(lastUpdate: Long) {
        withContext(Dispatchers.IO) {
            logv("Refreshing scp list")

            try {
                val ratings = downloadRatings()
                val success = refreshScpEntries(ratings)
                if (!success) {
                    // Don't refresh update time if we could not get any scp entries
                    loge("Error refreshing scp entries")
                    return@withContext
                }

                refreshTaleEntries(ratings)
                val talesNum = refreshTalesNum()
                scpDatabaseEntriesDao.setTalesNum(ScpDataEntryTalesNum(num = talesNum))

                scpDatabaseEntriesDao.setLastUpdate(ScpDataEntryLastUpdate(lastUpdate = lastUpdate))
            } catch (e: Exception) {
                loge("Error refreshing scp and tale entries", e)
            }
        }
    }
}
