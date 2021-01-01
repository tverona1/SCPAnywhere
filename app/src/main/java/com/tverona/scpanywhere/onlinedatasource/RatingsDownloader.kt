package com.tverona.scpanywhere.onlinedatasource

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logv
import org.jsoup.Jsoup
import javax.inject.Inject

/**
 * Downloads pre-computed ratings data
 */
class RatingsDownloader @Inject constructor(
    private val context: Context
) {
    @JsonClass(generateAdapter = true)
    data class EntryRatings(
        val ratings: Map<String, Int>?
    )

    fun download(): EntryRatings? {
        val url = context.getString(R.string.github_repo_ratings_asset)
        try {
            logv("Getting ratings asset: $url")
            val json = Jsoup.connect(url).ignoreContentType(true).timeout(20000).get().body().text()
            val moshi = Moshi.Builder().build()
            val mapType = Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                Int::class.javaObjectType
            )
            val jsonAdapter =
                moshi.adapter<Map<String, Int>>(mapType)
            val ratings = jsonAdapter.fromJson(json)
            if (ratings != null) {
                logv("Got ${ratings.size} rating entries")
            }
            return EntryRatings(ratings = jsonAdapter.fromJson(json))
        } catch (e: Exception) {
            loge("Error processing ratings from url: $url", e)
        }

        return null
    }
}