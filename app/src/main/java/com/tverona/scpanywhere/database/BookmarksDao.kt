package com.tverona.scpanywhere.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for querying & manipulating bookmarks
 */
@Dao
interface BookmarksDao {
    @Query("select * from bookmarks order by title asc")
    fun getAll(): LiveData<List<BookmarkEntry>>

    @Query("select * from bookmarks where read = 1 order by title asc")
    fun getAllRead(): LiveData<List<BookmarkEntry>>

    @Query("select * from bookmarks where favorite = 1 order by title asc")
    fun getAllFavorites(): LiveData<List<BookmarkEntry>>

    @Query("select * from bookmarks where url = :url limit 1")
    fun getByUrl(url: String): LiveData<BookmarkEntry?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmarkEntry: BookmarkEntry)

    @Query("delete from bookmarks WHERE title = :title")
    suspend fun deleteByTitle(title: String)

    @Query("delete from bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}