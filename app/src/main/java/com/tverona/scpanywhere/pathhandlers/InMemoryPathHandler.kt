package com.tverona.scpanywhere.pathhandlers

import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import androidx.webkit.WebViewAssetLoader
import com.tverona.scpanywhere.utils.logw
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Path handler that contains in-memory resources
 */
class InMemoryPathHandler : WebViewAssetLoader.PathHandler {
    private val entries = HashMap<String, InMemoryResourceEntry>()

    @WorkerThread
    override fun handle(path: String): WebResourceResponse {
        return if (entries.containsKey(path)) {
            val entry = entries[path]!!
            val inputStream = ByteArrayInputStream(entry.body.toByteArray(StandardCharsets.UTF_8))
            WebResourceResponse(entry.mimeType, null, inputStream)
        } else {
            logw("Cannot find in memory resource path: $path")
            WebResourceResponse(null, null, null)
        }
    }

    fun addEntry(entry: InMemoryResourceEntry) {
        entries[entry.path] = entry
    }
}