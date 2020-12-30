package com.tverona.scpanywhere.pathhandlers

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import androidx.webkit.WebViewAssetLoader
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.utils.RegexUtils
import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logv
import com.tverona.scpanywhere.utils.logw
import com.tverona.scpanywhere.viewmodels.UrlEntry
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup

/**
 * Online path handler is responsible for downloading pages from scp site in online mode
 */
class OnlinePathHandler(
    private val context: Context,
    private val urlListByUrl: Map<String, UrlEntry>
) : WebViewAssetLoader.PathHandler {

    private val scpWikiRegEx = Regex("""^http[s]?://(?:www\.)?scp-wiki\.net/""")
    private val scpWikidotRegEx = Regex("""^http[s]?://(?:www\.)?scp-wiki\.wikidot\.com/""")

    /**
     * Filters out non-html extensions (if an extension exists)
     */
    private fun filterUrl(url: String): Boolean {
        val ext = url.substringAfterLast('/').substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext != "html") {
            return false
        }

        return true
    }

    @WorkerThread
    override fun handle(path: String): WebResourceResponse? {
        // Filter non-html pages
        if (!filterUrl(path)) {
            return null
        }

        try {
            logv("loading url: ${path}")
            val doc = Jsoup.connect(path)
                .timeout(20000).get()

            // Only retain main-content (page's main content) and dummy-ondomready-block (used for handling table content).
            // Discard everything else.
            var elementsToKeep = 0
            val domReadyBlock = doc.getElementById("dummy-ondomready-block")
            if (null != domReadyBlock) {
                elementsToKeep++
                doc.body().prependChild(domReadyBlock)
            }
            val mainContent = doc.getElementById("main-content")
            if (null != mainContent) {
                elementsToKeep++
                doc.body().prependChild(mainContent)
            }

            if (null != mainContent) {
                var index = 0
                for (child in doc.body().children()) {
                    if (index > elementsToKeep - 1) {
                        child.remove()
                    }
                    index++
                }
            }

            // Inject a callback to our css override
            doc.head()
                .append("""<link rel="stylesheet" href="/scpanywhere_inject/override.css"></head>""")

            // Remove other elements
            doc.select("#page-options-container").remove()
            doc.select("div.wd-adunit").remove()
            doc.select("div.t_info").remove()
            doc.select("td.t_info").remove()
            doc.select("div.creditButton").remove()
            doc.select("div.creditButtonStandalone").remove()
            doc.select("div#u-credit-view").remove()
            doc.select(".licensebox22").remove()
            doc.select(".info-container").remove()
            doc.select("span[class*=\"btn\"]").remove()

            doc.select("script").forEach { elem ->
                val elemText = elem.text()
                if (elemText.contains("google_analytics|googletag|OneSignal|doubleclick|quantserve|createAd".toRegex()) ||
                    elem.hasAttr("src") && (elem.attr("src")
                        .contains("onesignal") || elem.attr("src").contains("nitropay"))
                ) {
                    elem.remove()
                }
            }

            // Fix up the page-tags to look a bit better
            val tags = doc.select("div.page-tags").first()
            if (null != tags) {
                val span = tags.select("span").first()
                if (null != span) {
                    span.unwrap()
                    tags.prepend("Tags:")
                    tags.prepend("<hr>")
                }
            }

            // Replace equivalent URLs with the base url
            doc.select("a[href*=\"scp-wiki.net/\"]").forEach { elem ->
                val link = elem.attr("href")
                val replacedLink =
                    link.replace(scpWikiRegEx, "http://${context.getString(R.string.base_path)}/")
                elem.attr("href", replacedLink)
            }

            doc.select("a[href*=\"scp-wiki.wikidot.com/\"]").forEach { elem ->
                val link = elem.attr("href")
                val replacedLink = link.replace(
                    scpWikidotRegEx,
                    "http://${context.getString(R.string.base_path)}/"
                )
                elem.attr("href", replacedLink)
            }

            // Fix up prev / next scp with correct entries
            val curScpNum = RegexUtils.scpUrlPattern.find(
                RegexUtils.normalizeUrl(
                    context,
                    path
                )
            )?.groups?.get(2)?.value?.toIntOrNull()
            if (null != curScpNum) {
                val scpEntry = urlListByUrl.get(RegexUtils.scpNumToUrl(curScpNum))
                if (scpEntry != null) {
                    doc.select("div.footer-wikiwalk-nav div p a").forEach { elem ->
                        if (elem.hasAttr("href") && elem.attr("href").startsWith("/scp-")) {
                            val scpNum =
                                elem.attr("href").removePrefix("/scp-").toIntOrNull()
                            if (null != scpNum && scpNum < curScpNum && null != scpEntry.prev) {
                                elem.attr("href", "/${scpEntry.prev!!.name}")
                                elem.text(scpEntry.prev!!.name!!.toUpperCase())
                            } else if (null != scpNum && scpNum > curScpNum && null != scpEntry.next) {
                                elem.attr("href", "/${scpEntry.next!!.name}")
                                elem.text(scpEntry.next!!.name!!.toUpperCase())
                            }
                        }
                    }
                }
            }

            return WebResourceResponse("text/html", "UTF-8", doc.html().byteInputStream())
        } catch (e: HttpStatusException) {
            logw("Error loading page: $path: status: ${e.statusCode} message: ${e.message}")

            if (e.statusCode == 404) {
                return WebResourceResponse(null, null, null)
            }
            return null
        } catch (e: Exception) {
            loge(
                "Error opening the requested path: $path",
                e
            )
        }

        // todo: scp-4011: check drop-down carets
        // todo: why does scp-4012 look different in offline mode? picture spacing.
        // todo: E/com.tverona.scpanywhere.ui.WebViewFragment$loadWebView$2: Error loading page http://scp-wiki.wdfiles.com/local--files/scp-4012/birthcriesoftheuniverse.mp3: net::ERR_FAILED, -1
        return WebResourceResponse(null, null, null)
    }
}

