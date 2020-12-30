package com.tverona.scpanywhere.downloader

import okhttp3.Credentials
import okhttp3.Interceptor

/**
 * Basic auth provider for authenticated against sites that require basic authentication
 */
class BasicAuthInterceptor(username: String, password: String) : Interceptor {
    private var credentials: String = Credentials.basic(username, password)

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        var request = chain.request()
        request = request.newBuilder().header("Authorization", credentials).build()
        return chain.proceed(request)
    }
}
