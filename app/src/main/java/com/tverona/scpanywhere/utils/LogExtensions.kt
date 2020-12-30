package com.tverona.scpanywhere.utils

import android.util.Log

/**
 * Helper method for logging (w/ class as tag name)
 */
inline val <reified T> T.TAG: String
    get() = if (T::class.java.simpleName.isNotEmpty()) T::class.java.simpleName else T::class.java.name

inline fun <reified T> T.logv(message: String) = Log.v(TAG, message)
inline fun <reified T> T.logi(message: String) = Log.i(TAG, message)
inline fun <reified T> T.logw(message: String) = Log.w(TAG, message)
inline fun <reified T> T.logd(message: String) = Log.d(TAG, message)
inline fun <reified T> T.loge(message: String) = Log.e(TAG, message)
inline fun <reified T> T.loge(message: String, e: Exception) = Log.e(TAG, message, e)
