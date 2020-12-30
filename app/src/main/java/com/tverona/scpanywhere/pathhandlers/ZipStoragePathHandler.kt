package com.tverona.scpanywhere.pathhandlers

import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import androidx.webkit.WebViewAssetLoader
import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logw
import com.tverona.scpanywhere.zipresource.ZipResourceFile
import java.io.FileNotFoundException
import java.net.URLConnection

/**
 * Path handler for zip resource files, used in offline mode
 */
class ZipStoragePathHandler(
    zipResourceFile: ZipResourceFile
) : WebViewAssetLoader.PathHandler {
    private var mZipResourceFile: ZipResourceFile = zipResourceFile

    /**
     * Returns resource for given [path]
     */
    @WorkerThread
    override fun handle(path: String): WebResourceResponse {
        try {
            val mimeType = guessMimeType(path)
            val inputStream = mZipResourceFile.getInputStream(path)
            if (null == inputStream) {
                logw("File not found: $path, mime type: $mimeType")
            }

            return WebResourceResponse(mimeType, null, inputStream)
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

    private fun guessMimeType(filePath: String): String? {
        val mimeType: String? = URLConnection.guessContentTypeFromName(filePath)
        return mimeType ?: "text/plain"
    }
}