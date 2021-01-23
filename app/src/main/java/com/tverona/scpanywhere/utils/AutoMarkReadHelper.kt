package com.tverona.scpanywhere.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.ui.WebViewFragment

/**
 * Helper utility for auto-marking entries as read
 */
class AutoMarkReadHelper(private val context: Context, private val onMarkRead: (WebViewFragment.UrlTitle) -> Unit) {
    private var sharedPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)
    private var autoMarkReadSetting: String?
    private var autoMarkReadTimeSetting: Int

    private var isAutoMarkedRead = false
    private var scrolledToBottom = false
    private var nextItem = false
    private var readTimeSecs: Long = 0
    private var estimatedReadTimeSecs: Long = 0
    private var urlTitle: WebViewFragment.UrlTitle? = null

    fun onNewPage() {
        isAutoMarkedRead = false
        scrolledToBottom = false
        nextItem = false
        readTimeSecs = 0
        estimatedReadTimeSecs = 0
        this.urlTitle = null
    }

    fun onUrlTitle(urlTitle: WebViewFragment.UrlTitle) {
        this.urlTitle = urlTitle
        updateAutoRead()
    }

    fun onScrolledToBottom() {
        scrolledToBottom = true
        updateAutoRead()
    }

    fun onReadTimeElapsed(readTimeSecs: Long) {
        this.readTimeSecs = readTimeSecs
        updateAutoRead()
    }

    fun onNextItem() {
        nextItem = true
        updateAutoRead()
    }

    fun setEstimatedReadTime(estimatedReadTimeSecs: Long) {
        this.estimatedReadTimeSecs = estimatedReadTimeSecs
    }

    fun isEstimatedReadTimeEnabled(): Boolean {
        return when (autoMarkReadTimeSetting) {
            0 ->
                true
            else ->
                false
        }
    }

    fun isReadTimeEnabled(): Boolean {
        return when (autoMarkReadTimeSetting) {
            -1 ->
                false
            else ->
                true
        }

    }

    fun isOnNextItemEnabled(): Boolean {
        return when (autoMarkReadSetting) {
            context.getString(R.string.auto_mark_read_next_scp),
            context.getString(R.string.auto_mark_read_scroll_bottom_or_next_scp),
            context.getString(R.string.auto_mark_read_scroll_bottom_and_next_scp) ->
                true
            else ->
                false
        }
    }

    fun isScrollToBottomEnabled(): Boolean {
        return when (autoMarkReadSetting) {
            context.getString(R.string.auto_mark_read_scroll_bottom),
            context.getString(R.string.auto_mark_read_scroll_bottom_or_next_scp),
            context.getString(R.string.auto_mark_read_scroll_bottom_and_next_scp) ->
                true
            else ->
                false
        }
    }

    private fun updateAutoRead() {
        if (isAutoMarkedRead || urlTitle == null) {
            return
        }

        val readTimeMet = when (autoMarkReadTimeSetting) {
            -1 -> {
                true
            }
            0 -> {
                (readTimeSecs >= estimatedReadTimeSecs)
            }
            else -> {
                readTimeSecs > autoMarkReadTimeSetting * 60
            }
        }

        if (!readTimeMet) {
            return
        }

        val autoMarkRead = when (autoMarkReadSetting) {
            context.getString(R.string.auto_mark_read_scroll_bottom) -> {
                scrolledToBottom
            }
            context.getString(R.string.auto_mark_read_next_scp) -> {
                nextItem
            }
            context.getString(R.string.auto_mark_read_scroll_bottom_or_next_scp) -> {
                scrolledToBottom || nextItem
            }
            context.getString(R.string.auto_mark_read_scroll_bottom_and_next_scp) -> {
                scrolledToBottom && nextItem
            }
            else -> {
                false
            }
        }

        if (autoMarkRead) {
            logv("Auto-marking ${urlTitle!!.url} as read: read time: $readTimeSecs secs, estimated read time: $estimatedReadTimeSecs secs, scolled to bottom: $scrolledToBottom, next item: $nextItem")

            onMarkRead(urlTitle!!)
            isAutoMarkedRead = true
        }
    }

    init {
        autoMarkReadSetting = sharedPreferences.getString(
            context.getString(R.string.auto_mark_read_key),
            context.getString(R.string.auto_mark_read_off)
        )
        autoMarkReadTimeSetting =
            sharedPreferences.getString(context.getString(R.string.auto_mark_read_time_key), "-1")
                ?.toIntOrNull() ?: -1
    }
}