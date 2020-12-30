package com.tverona.scpanywhere.utils

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.InputStream

/**
 * Deserialize json to list
 */
class JsonList {
    companion object {

        inline fun <reified T> loadJsonList(inputStream: InputStream): List<T> {
            val json =
                inputStream.bufferedReader().use {
                    it.readText()
                }

            val listType = Types.newParameterizedType(List::class.java, T::class.java)
            val moshi = Moshi.Builder().build()
            val adapter: JsonAdapter<List<T>> = moshi.adapter(listType)
            val result = adapter.fromJson(json)
            return result ?: listOf<T>()
        }
    }
}