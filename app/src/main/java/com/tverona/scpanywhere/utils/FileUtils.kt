package com.tverona.scpanywhere.utils

import kotlinx.coroutines.*
import okio.Buffer
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Extension method to coroutine copy to [output] file with optional [bufferSize] buffer size, [blockingDispatcher] blocking dispatcher (default is Dispatchers.IO)
 * and [progress] call back to monitor progress.
 */
suspend fun File.copyTo(
    output: File, bufferSize: Long = DEFAULT_BUFFER_SIZE.toLong(),
    blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    progress: ((downloaded: Long, total: Long) -> Unit)? = null
) {
    val input = this
    withContext(blockingDispatcher) {
        suspendCancellableCoroutine<File> { cont ->

            cont.invokeOnCancellation {
                cancel()
            }

            val inputLength = input.length()
            val buffer = Buffer()
            var finished = false

            try {
                output.sink().buffer().use { out ->
                    input.source().use { source ->
                        var totalLength = 0L
                        while (cont.isActive) {
                            val read = source.read(buffer, bufferSize)
                            if (read == -1L) {
                                finished = true
                                break
                            }
                            out.write(buffer, read)
                            out.flush()
                            totalLength += read
                            progress?.invoke(totalLength, inputLength)
                        }
                    }
                }
                if (finished) {
                    cont.resume(output, onCancellation = {
                        cancel()
                    })
                } else {
                    cont.resumeWithException(IOException("Copy cancelled"))
                }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }
}

/**
 * Rename extension method Truncate and delete
 */
fun File.rename(dest: File): Boolean {
    dest.truncateAndDelete()
    return this.renameTo(dest)
}

fun File.truncateAndDelete(): Boolean {
    if (exists()) {
        logv("Deleting file ${absolutePath}")
        try {
            // For some reason, volume's usable space does not update just by deleting, so truncate file first.
            writeText("")
        } catch (e: Exception) {
        }
        return delete()
    }
    return false
}