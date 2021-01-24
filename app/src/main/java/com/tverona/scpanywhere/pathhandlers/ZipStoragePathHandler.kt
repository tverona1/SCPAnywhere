package com.tverona.scpanywhere.pathhandlers

import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import androidx.webkit.WebViewAssetLoader
import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logw
import com.tverona.scpanywhere.zipresource.ZipResourceFile
import org.jsoup.Jsoup
import java.io.FileNotFoundException
import java.net.URLConnection

/**
 * Path handler for zip resource files, used in offline mode
 */
class ZipStoragePathHandler(
    private val zipResourceFile: ZipResourceFile,
    private val contentOptions: ContentOptions
) : WebViewAssetLoader.PathHandler {

    /**
     * Returns resource for given [path]
     */
    @WorkerThread
    override fun handle(path: String): WebResourceResponse {
        try {
            val mimeType = guessMimeType(path)
            val inputStream = zipResourceFile.getInputStream(path)
            if (null == inputStream) {
                logw("File not found: $path, mime type: $mimeType")
            }

            if (!contentOptions.optionsEnabled() || !mimeType.equals("text/html")) {
                return WebResourceResponse(mimeType, null, inputStream)
            } else {
                try {
                    if (null != inputStream) {
                        val doc = Jsoup.parse(inputStream, null, "")

                        if (contentOptions.expandTabs) {
                            ContentOptions.expandTabs(doc)
                        }

                        if (contentOptions.expandBlocks) {
                            ContentOptions.expandBlocks(doc)
                        }

                        return WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            doc.html().byteInputStream()
                        )
                    }
                } catch (e: Exception) {
                    loge("Error processing content options", e)
                }
            }
        } catch (e: Exception) {
            loge(
                "Error opening the requested path: $path",
                e
            )
        } catch (e: FileNotFoundException) {
            loge(
                "Error opening the requested path: $path",
                e
            )
        }
        return WebResourceResponse(null, null, null)
    }

    private fun guessMimeType(filePath: String): String {
        val mimeType: String? = URLConnection.guessContentTypeFromName(filePath)
        return mimeType ?: "text/plain"
    }
}