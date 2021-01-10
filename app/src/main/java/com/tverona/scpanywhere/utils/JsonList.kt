package com.tverona.scpanywhere.utils

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okio.buffer
import okio.source
import java.io.InputStream

/**
 * Deserialize json to list
 */
class JsonList {
    companion object {

        inline fun <reified T> loadJsonList(inputStream: InputStream): List<T> {
            val listType = Types.newParameterizedType(List::class.java, T::class.java)
            val moshi = Moshi.Builder().build()
            val adapter: JsonAdapter<List<T>> = moshi.adapter(listType)

            inputStream.source().buffer().use {
                val result = adapter.fromJson(it)
                return result ?: listOf<T>()
            }

        }
    }
}