package com.tverona.scpanywhere.utils

import android.os.Build
import android.text.Html
import android.text.Spanned
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Various string formatter helpers
 */
class StringFormatter {
    companion object {
        fun fileSize(size: Long): String {
            if (size <= 0) return "0"
            val units = arrayOf("B", "kB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble()))
                .toString() + " " + units[digitGroups]
        }

        fun percent(percent: Int): String {
            return "${percent}%"
        }

        fun htmlText(htmlText: String?): CharSequence? {
            if (htmlText == null) return null
            val result: Spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY)
            } else {
                Html.fromHtml(htmlText)
            }
            return result
        }

        fun dateFromMillis(time: Long): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(time)
        }

        fun fileNameFromCurrentTime(): String {
            return SimpleDateFormat(
                "yyyy-MM-dd_HH-mm",
                Locale.getDefault()
            ).format(Calendar.getInstance().time)
        }

        fun durationFromSec(totalSecs: Long?): Triple<Long, Long, Long> {
            if (totalSecs == null) {
                return Triple(0, 0, 0)
            }
            val hours = totalSecs / 3600
            val mins = (totalSecs % 3600) / 60
            val secs = totalSecs % 60
            return Triple(hours, mins, secs)
        }
    }
}

/**
 * Convert integer to roman numeral representation (good til 3999)
 */
fun Int.toRomanNumeral(): String? {
    fun digit(k: Int, unit: String, five: String, ten: String): String? {
        return when (k) {
            in 1..3 -> unit.repeat(k)
            4 -> unit + five
            in 5..8 -> five + unit.repeat(k - 5)
            9 -> unit + ten
            else -> null
        }
    }
    return when (this) {
        0 -> ""
        in 1..9 -> digit(this, "I", "V", "X")
        in 10..99 -> digit(this / 10, "X", "L", "C") + (this % 10).toRomanNumeral()
        in 100..999 -> digit(this / 100, "C", "D", "M") + (this % 100).toRomanNumeral()
        in 1000..3999 -> "M" + (this - 1000).toRomanNumeral()
        else -> null
    }
}