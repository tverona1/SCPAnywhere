package com.tverona.scpanywhere.utils

import android.content.Context
import com.tverona.scpanywhere.R

/**
 * Specific regex helper functions
 */
class RegexUtils {
    companion object {
        val seriesUrlPattern = """www\.scpwiki\.com/(scp-series(?:-(\d+))?)""".toRegex()
        val scpUrlPattern = """www\.scpwiki\.com/(scp-(\d+))$""".toRegex()
        val seriesNamePattern = """scp-series(?:-(\d+))?""".toRegex()
        val scpTitlePattern = """^(SCP-\d+) - (.+)""".toRegex()

        fun normalizeUrl(context: Context, url: String): String {
            return url.removePrefix(context.getString(R.string.local_url_prefix))
                .removePrefix("http://").removePrefix("https://").removeSuffix("/index.html")
        }

        fun scpNumToUrl(num: Int): String {
            return "www.scpwiki.com/scp-$num"
        }
    }
}