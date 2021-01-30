package com.tverona.scpanywhere.pathhandlers

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import androidx.webkit.WebViewAssetLoader
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logv
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Online path handler responsible for processing javascripts in online mode
 */
class OnlinePathHandlerJavascript @Inject constructor(
    private val context: Context,
) : WebViewAssetLoader.PathHandler {
    private val client = OkHttpClient()

    @WorkerThread
    override fun handle(path: String): WebResourceResponse? {
        try {
            for (entry in replace) {
                if (!path.endsWith(entry.key)) {
                    continue
                }

                logv("loading script: $path")
                val request = Request.Builder().get()
                    .url(path)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        val script = entry.value(response.body!!.string())
                        return WebResourceResponse(
                            "application/javascript",
                            "UTF-8",
                            script.byteInputStream()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            loge(
                "Error opening the requested path: $path",
                e
            )
        }

        return null
    }

    val replace = mapOf(
        // This script extracts domain name from url path, based on redirected url.
        // Since in online mode we let JSoup redirect for us, the url is the base url.
        // We fix up the script to compensate for this.
        "/html-block-iframe.js" to { script: String ->
            script.replace(
                "url_array[6]",
                "\"${context.getString(R.string.base_path)}\""
            )
        }
    )
}
