package com.tverona.scpanywhere.pathhandlers

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader

/**
 * Resource loader for web view. Delegates to the registered path handlers. Instantiated via Builder pattern.
 */
class WebViewResourceLoader internal constructor(private val matchers: List<PathMatcher>) {
    internal class PathMatcher(
        val domain: String,
        val path: String,
        val handler: WebViewAssetLoader.PathHandler,
        val handleFullPath: Boolean,
        val allowHttp: Boolean
    ) {
        /**
         * Match uri to handler
         */
        fun match(uri: Uri): WebViewAssetLoader.PathHandler? {
            // Only match HTTP_SCHEME if caller enabled HTTP matches.
            if (uri.scheme.equals(HTTP_SCHEME) && !allowHttp) {
                return null
            }
            // Don't match non-HTTP(S) schemes.
            if (!uri.scheme.equals(HTTP_SCHEME) && !uri.scheme.equals(HTTPS_SCHEME)) {
                return null
            }
            if (!uri.authority.equals(domain)) {
                return null
            }
            if (!uri.path!!.startsWith(path)) {
                return null
            }
            return handler
        }

        /**
         * Gets path to pass to handler
         */
        fun getPath(url: Uri): String {
            if (handleFullPath) {
                return url.toString()
            } else {
                return url.path!!.replaceFirst(this.path, "")
            }
        }

        companion object {
            const val HTTP_SCHEME = "http"
            const val HTTPS_SCHEME = "https"
        }
    }

    class Builder {
        private val matcherList: MutableList<PathMatcher> = mutableListOf()

        /**
         * Adds a path handler
         */
        fun addPathHandler(
            domain: String,
            path: String,
            handler: WebViewAssetLoader.PathHandler,
            handleFullPath: Boolean = false,
            allowHttp: Boolean = true
        ): Builder {
            matcherList.add(PathMatcher(domain, path, handler, handleFullPath, allowHttp))
            return this
        }

        fun build(): WebViewResourceLoader {
            return WebViewResourceLoader(matcherList)
        }
    }

    /**
     * Invoked by web view client to intercept requests
     */
    fun shouldInterceptRequest(url: Uri): WebResourceResponse? {
        for (matcher in matchers) {
            val handler = matcher.match(url) ?: continue
            return handler.handle(matcher.getPath(url)) ?: continue
        }
        return null
    }
}