package com.tverona.scpanywhere.onlinedatasource

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logv
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Downloads pre-computed ratings data
 */
class RatingsDownloader @Inject constructor(
    private val context: Context,
    private val client: OkHttpClient
) {
    @JsonClass(generateAdapter = true)
    data class EntryRatings(
        val ratings: Map<String, Int>?
    )

    fun download(): EntryRatings? {
        val url = context.getString(R.string.github_repo_ratings_asset)
        try {
            logv("Getting ratings asset: $url")

            val request = Request.Builder().get()
                .url(url)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.body != null) {
                    val moshi = Moshi.Builder().build()
                    val mapType = Types.newParameterizedType(
                        Map::class.java,
                        String::class.java,
                        Int::class.javaObjectType
                    )
                    val jsonAdapter =
                        moshi.adapter<Map<String, Int>>(mapType)
                    val ratings = jsonAdapter.fromJson(response.body!!.source())
                    if (ratings != null) {
                        logv("Got ${ratings.size} rating entries")
                    }

                    return EntryRatings(ratings = ratings)
                }
            }
        } catch (e: Exception) {
            loge("Error processing ratings from url: $url", e)
        }

        return null
    }
}