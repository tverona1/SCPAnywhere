package com.tverona.scpanywhere.onlinedatasource

import android.content.Context
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logv
import com.tverona.scpanywhere.viewmodels.UrlEntry
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import javax.inject.Inject

/**
 * Downloads SCP entries from scp-series pages
 */
class ScpListDownloader @Inject constructor(
    private val context: Context
) {
    /**
     * Process a scp-series page at [url] with series number [seriesNum], returning a list of SCP entries
     */
    private fun processSeries(url: String, seriesNum: Int): List<UrlEntry>? {
        val scpList = arrayListOf<UrlEntry>()

        val series = "scp-series-%d".format(seriesNum)
        val baseUrl = context.getString(R.string.base_path)

        try {
            val doc = Jsoup.connect(url).timeout(20000).get()
            logv("Processing series url $url")

            // Compensate for non-standard url names
            nonStandardUrlEntries.forEach {
                doc.select("a[href='${it.key}']")?.attr("href", it.value)
            }

            doc.select("div#page-content li").forEach { elem ->
                val a = elem.getElementsByAttributeValueMatching("href", "scp-\\d+").first()
                if (null != a && !a.hasClass("newpage")) {
                    val link = a.attr("href")
                    val nameMatch = "scp-\\d+".toRegex().find(link)
                    var name = "Unknown"
                    if (null != nameMatch && nameMatch.groups.isNotEmpty()) {
                        name = nameMatch.groups[0]?.value.toString()
                    }
                    val e = elem.clone()
                    e.select("a").forEach { a2 ->
                        a2.replaceWith(TextNode(a2.text()))
                    }
                    val scpUrl = baseUrl + link.removePrefix("..")
                    val title = e.html()
                    val scpEntry =
                        UrlEntry(
                            title = title,
                            url = scpUrl,
                            series = series,
                            name = name,
                            rating = null
                        )
                    scpList.add(scpEntry)
                }
            }

            logv("Got ${scpList.size} scp entries for series $seriesNum")
        } catch (e: HttpStatusException) {
            logv("Series not found: $url")
            return null
        } catch (e: Exception) {
            loge("Error processing series url $url", e)
            return null
        }

        return scpList
    }

    /**
     * Gets the number of series edition tales
     */
    fun getTalesNum(): Int {
        var talesNum = 0
        val talesPattern = "http://www.scpwiki.com/scp-series-%d-tales-edition"

        while (true) {
            val url = talesPattern.format(talesNum + 1)

            try {
                Jsoup.connect(url).timeout(20000).get()
                talesNum++
            } catch (e: HttpStatusException) {
                logv("Tales not found: $url")
                break
            } catch (e: Exception) {
                loge("Error processing tales url $url", e)
                break
            }
        }

        logv("Tales num: $talesNum")
        return talesNum
    }

    fun download(): List<UrlEntry> {
        val scpEntries = arrayListOf<UrlEntry>()
        var seriesNum = 1
        val initSeries = "http://www.scpwiki.com/scp-series"
        val seriesPattern = "http://www.scpwiki.com/scp-series-%d"
        while (true) {
            val url = if (seriesNum == 1) initSeries else seriesPattern.format(seriesNum)
            val seriesList = processSeries(url, seriesNum) ?: break
            scpEntries += seriesList
            seriesNum++
        }

        return scpEntries
    }

    companion object {
        val nonStandardUrlEntries = mapOf(
            "/1231-warning" to "/scp-1231"
        )
    }
}