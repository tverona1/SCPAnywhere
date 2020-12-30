package com.tverona.scpanywhere.viewmodels

import com.squareup.moshi.JsonClass

/**
 * Represents a url entry - title, url, and optional rating, series & name / number
 */
@JsonClass(generateAdapter = true)
data class UrlEntry(
    val title: String,
    var url: String,
    val rating: Int?,
    val series: String?,
    val name: String?,
    @Transient
    var prev: UrlEntry? = null,
    @Transient
    var next: UrlEntry? = null
) {
    val num: Int
        get() = name?.removePrefix("scp-")?.toIntOrNull() ?: 0

    companion object {
        fun linkEntries(entries: Iterable<UrlEntry>): List<UrlEntry> {
            val output = entries.sortedBy { it.num }

            var prev: UrlEntry? = null
            output.forEach {
                if (null != prev) {
                    prev!!.next = it
                    it.prev = prev
                }
                prev = it
            }

            return output
        }

        fun formatRating(rating: Int?): String {
            // todo - don't hardcode
            return if (null == rating) {
                "Rating: ?"
            } else {
                "Rating: $rating"
            }
        }
    }
}
