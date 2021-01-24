package com.tverona.scpanywhere.downloader

import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logv
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.Buffer
import okio.appendingSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Custom coroutine dispatcher for blocking calls
 */
val OK_IO = newFixedThreadPoolContext(5, "OK_IO")

/**
 * Invokes OkHttp Call and saves successful result to [output].
 * If [resumeIfExists] is specified, then download will be resumed (file appended to) rather than downloaded from scratch.
 * Optional [bufferSize] can be specified.
 *
 * Warning: Dispatcher in [blockingDispatcher] executes blocking calls
 * [progress] callback returns downloaded bytes and total bytes, but total not always available
 */
@ExperimentalCoroutinesApi
suspend fun Call.downloadAndSaveTo(
    output: File,
    resumeIfExists: Boolean,
    bufferSize: Long = DEFAULT_BUFFER_SIZE.toLong(),
    blockingDispatcher: CoroutineDispatcher = OK_IO,
    progress: ((downloaded: Long, total: Long) -> Unit)? = null
): File = withContext(blockingDispatcher) {
    suspendCancellableCoroutine<File> { cont ->
        cont.invokeOnCancellation {
            cancel()
        }

        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                loge("downloadAndSaveTo OnFailure", e)
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    cont.resumeWithException(IOException("Unexpected HTTP code: ${response.code}, body: ${response.body?.string()}"))
                    response.close()
                    return
                }
                try {
                    val body = response.body
                    if (body == null) {
                        cont.resumeWithException(IllegalStateException("Body is null"))
                        return
                    }
                    val resumeLength = if (resumeIfExists) output.length() else 0L
                    val contentLength = body.contentLength() + resumeLength
                    val buffer = Buffer()
                    var finished = false
                    output.sink(append = resumeIfExists).buffer().use { out ->
                        body.source().use { source ->
                            var totalLength = resumeLength
                            while (cont.isActive) {
                                val read = source.read(buffer, bufferSize)
                                if (read == -1L) {
                                    finished = true
                                    break
                                }
                                out.write(buffer, read)
                                out.flush()
                                totalLength += read
                                progress?.invoke(totalLength, contentLength)
                            }
                        }
                    }
                    if (finished) {
                        cont.resume(output, onCancellation = {
                            cancel()
                        })
                    } else {
                        cont.resumeWithException(IOException("Download cancelled"))
                    }
                } catch (e: Exception) {
                    loge("Download exception for ${output.path}", e)
                    cont.resumeWithException(e)
                } finally {
                    response.close()
                }
            }
        })
    }
}