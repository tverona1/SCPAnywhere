package com.tverona.scpanywhere.utils

import android.os.Build
import kotlinx.coroutines.*
import okio.Buffer
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

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
 * Rename extension method Truncate, delete and rename
 */
fun File.rename(dest: File): Boolean {
    dest.truncateAndDelete()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return try {
            Files.move(toPath(), dest.toPath())
            true
        } catch (e: Exception) {
            loge("Error moving file $absolutePath", e)
            false
        }
    } else {
        return this.renameTo(dest)
    }
}

fun File.truncateAndDelete(): Boolean {
    if (exists()) {
        logv("Deleting file ${absolutePath}")
        try {
            // For some reason, volume's usable space does not update just by deleting, so truncate file first.
            writeText("")
        } catch (e: Exception) {
            loge("Can't truncate file $absolutePath", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try {
                Files.delete(toPath())
                logv("Deleted ${absolutePath}")
                true
            } catch (e: Exception) {
                loge("Error deleting file $absolutePath", e)
                false
            }
        } else {
            val ret = delete()
            if (!ret) {
                loge("Failed to delete file $absolutePath")
            } else {
                logv("Deleted ${absolutePath}")
            }
            return ret
        }
    }
    return true
}