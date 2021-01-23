package com.tverona.scpanywhere.downloader

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Rfc3339DateJsonAdapter
import com.tverona.scpanywhere.utils.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.io.File
import java.util.*
import javax.inject.Inject

/**
 * Github downloader - used for querying latest release & downloading release assets
 */
class GithubReleaseDownloader @Inject constructor(
    @ApplicationContext val context: Context,
    private val client: OkHttpClient
) {
    /**
     * Represents a Github release asset
     */
    @JsonClass(generateAdapter = true)
    data class GithubAsset(val name: String, val url: String, val size: Long)

    /**
     * Represents a Github release
     */
    @JsonClass(generateAdapter = true)
    data class GithubRelease(
        val tag_name: String,
        val published_at: Date,
        val assets: List<GithubAsset>?
    )

    /**
     * Retrieves latest Github release metadata for repro url [repoUrl]
     */
    suspend fun downloadLatestReleaseMetadata(repoUrl: String): GithubRelease? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().get()
                .url(repoUrl)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code: $response")

                    val jsonRelease = response.body!!.string()
                    logv("Release: $jsonRelease")
                    val moshi =
                        Moshi.Builder().add(Date::class.java, com.squareup.moshi.adapters.Rfc3339DateJsonAdapter().nullSafe())
                            .build()
                    val adapter: JsonAdapter<GithubRelease> =
                        moshi.adapter(GithubRelease::class.java)
                    val githubRelease = adapter.fromJson(jsonRelease)

                    logv("Latest release: Tag: ${githubRelease?.tag_name}, updated at: ${githubRelease?.published_at}, asset count: ${githubRelease?.assets?.size}")
                    return@use githubRelease
                }
            } catch (e: Exception) {
                if (e is IOException && e.message?.contains(
                        "rate limit exceeded",
                        ignoreCase = true
                    ) == true
                ) {
                    // Emit rate limit exceeded as specific exception type
                    logw("Rate limit exceeded for $repoUrl")
                    throw RateLimitExceededException("Rate limited exceeded for $repoUrl")
                }

                loge("Download release error: $e", e)
                throw e
            }
        }

    /**
     * Downloads Github release asset specified by [url] repo to [destFile] file. Callback [progress] can be used to monitor progress.
     */
    suspend fun downloadReleaseAsset(
        url: String,
        destFile: File,
        progress: ((downloaded: Long, total: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val request = url.let {
            Request.Builder().get()
                .url(it)
                .header("Accept", "application/octet-stream")
                .build()
        }
        logv("Downloading $url to file ${destFile.absolutePath}")

        try {
            client.newCall(request)
                .downloadAndSaveTo(output = destFile, progress = progress)

        } catch (e: Exception) {
            loge("Error downloading $url", e)
            if (e is IOException && e.message?.contains(
                    "rate limit exceeded",
                    ignoreCase = true
                ) == true
            ) {
                // Emit rate limit exceeded as specific exception type
                logw("Rate limit exceeded for asset $url")
                throw RateLimitExceededException("Rate limited exceeded for $url")
            }
            throw e
        }
    }
}