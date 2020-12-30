package com.tverona.scpanywhere.repositories

import com.tverona.scpanywhere.database.BookmarkEntry
import com.tverona.scpanywhere.database.BookmarksDao
import javax.inject.Inject

/**
 * Repository for bookmarks backed by bookmark DAO
 */
class BookmarksRepository @Inject constructor(private val bookmarksDao: BookmarksDao) {
    val allBookmarks = bookmarksDao.getAll()
    val allRead = bookmarksDao.getAllRead()
    val allFavorites = bookmarksDao.getAllFavorites()
    fun getByUrl(url: String) = bookmarksDao.getByUrl(url)
    suspend fun insert(bookmarkEntry: BookmarkEntry) = bookmarksDao.insert(bookmarkEntry)
    suspend fun deleteByUrl(url: String) = bookmarksDao.deleteByUrl(url)
}