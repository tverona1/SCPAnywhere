package com.tverona.scpanywhere.onlinedatasource


import android.content.Context
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logv
import com.tverona.scpanywhere.viewmodels.UrlEntry
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import javax.inject.Inject

/**
 * Downloads list of tales from 'tale' page tags
 */
class TaleListDownloader @Inject constructor(
    private val context: Context,
    private val client: OkHttpClient
) {

    private fun processTaleTitles(url: String): List<UrlEntry> {
        val urlEntries = arrayListOf<UrlEntry>()
        val baseUrl = context.getString(R.string.base_path)

        try {
            val doc = Jsoup.connect(url).timeout(20000).get()
            logv("Processing tale titles: $url")

            doc.select("div.pages-list div.pages-list-item div.title a[href]").forEach { elem ->
                val urlEntry = UrlEntry(
                    title = elem.text(),
                    url = baseUrl + elem.attr("href"),
                    rating = null,
                    series = null,
                    name = null
                )
                urlEntries.add(urlEntry)
            }
        } catch (e: Exception) {
            loge("Error processing url $url", e)
        }

        logv("Got ${urlEntries.size} tale entries")
        return urlEntries
    }

    fun download(): List<UrlEntry> {
        val url = "http://www.scpwiki.com/system:page-tags/tag/tale#pages"
        return processTaleTitles(url)
    }
}