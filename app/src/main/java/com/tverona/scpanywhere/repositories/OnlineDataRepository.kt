package com.tverona.scpanywhere.repositories

import com.tverona.scpanywhere.viewmodels.UrlEntry

interface OnlineDataRepository {
    suspend fun getScpEntries(): List<UrlEntry>
    suspend fun getTaleEntries(): List<UrlEntry>
    suspend fun getTalesNum(): Int
    suspend fun getLastUpdate(): Long
    suspend fun refresh(lastUpdate: Long)
}