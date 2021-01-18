package com.tverona.scpanywhere.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.hilt.Assisted
import androidx.hilt.work.WorkerInject
import androidx.work.*
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.downloader.GithubReleaseDownloader
import com.tverona.scpanywhere.repositories.OfflineDataRepository
import com.tverona.scpanywhere.utils.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

class DownloadWorker @WorkerInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val offlineDataRepository: OfflineDataRepository,
    private val githubReleaseDownloader: GithubReleaseDownloader
) : CoroutineWorker(context, workerParams) {
    private val notificationManager =
        context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private var totalSize: Long = 0
    private var totalFiles = 0

    private var doneBytesPerFile = hashMapOf<String, Long>()
    private var doneFiles = 0
    private var donePercent = -1

    override suspend fun doWork(): Result {
        doneFiles = 0
        doneBytesPerFile.clear()
        donePercent = -1

        val storageDir = inputData.getString(KEY_STORAGE_DIR)
        val urls = inputData.getStringArray(KEY_URLS)
        val names = inputData.getStringArray(KEY_FILE_NAMES)
        totalSize = inputData.getLong(KEY_TOTAL_SIZE, 0)

        if (storageDir == null || urls == null || names == null || urls.size != names.size) {
            return Result.failure()
        }
        totalFiles = urls.size

        // Mark the Worker as important
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel()
        }
        setForeground(ForegroundInfo(NOTIFICATION_ID, buildNotification(0, 0, 0)))

        return try {
            // Clean up any temporary files
            offlineDataRepository.cleanupTempFiles()
            downloadAll(storageDir, urls, names)
            logv("downloadAll succeeded")
            Result.success()
        } catch (e: Exception) {
            loge("Error downloading files", e)
            notificationManager.cancel(NOTIFICATION_ID)
            Result.failure()
        }
    }

    /**
     * Download all files specified by [urls] with file name [names] to [storageDir]
     */
    private suspend fun downloadAll(storageDir: String, urls: Array<String>, names: Array<String>) {
        coroutineScope {
            urls.forEachIndexed { index, url ->
                this.launch {
                    try {
                        // Download the asset
                        download(storageDir, url, names[index])
                    } catch (e: Exception) {
                        loge(
                            "Failed to download asset: $url",
                            e
                        )
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Download file from [url] with file name [name] to [storageDir]
     */
    private suspend fun download(storageDir: String, url: String, name: String) =
        withContext(Dispatchers.IO) {
            val fileDestPath =
                storageDir + File.separator + name
            val fileTempPath =
                storageDir + File.separator + name + OfflineDataRepository.tmpExt

            logv("Downloading $name to file $fileTempPath")
            val file = File(fileTempPath)
            val fileDest = File(fileDestPath)

            try {
                // Delete destination first to save storage space, unless it's the base asset;
                // then keep it since it's relatively small to allow continuing to browse in the background
                if (!name.contains(
                        OfflineDataRepository.baseAssetName,
                        ignoreCase = true
                    )
                ) {
                    fileDest.truncateAndDelete()
                }

                var lastProgressPercent = 0
                githubReleaseDownloader.downloadReleaseAsset(url, file) { progress, total ->
                    if (isActive) {
                        var percent = 0
                        if (total > 0) {
                            percent = ((progress.toFloat() / total) * 100).toInt()
                        }

                        // Don't bother to update if percent progress is not changing
                        if (lastProgressPercent != percent) {
                            lastProgressPercent = percent
                            updateNotificationProgress(name, progress, false)

                            val progressData = workDataOf(
                                KEY_PROGRESS_NAME to name,
                                KEY_PROGRESS_IS_PROGRESS to true,
                                KEY_PROGRESS_CURRENT_SIZE to progress,
                                KEY_PROGRESS_TOTAL_SIZE to total
                            )
                            setProgressAsync(progressData)
                        }
                    }
                }

                // Rename to final path
                logv("Renaming file ${file.absolutePath} to ${fileDest.absolutePath}")
                val ret = file.rename(fileDest)
                if (!ret) {
                    throw IOException("Unable to move file from ${file.absolutePath} to ${fileDest.absolutePath}")
                }

                updateNotificationProgress(name, 0, true)
                val progressData = workDataOf(
                    KEY_PROGRESS_NAME to name,
                    KEY_PROGRESS_IS_DONE to true
                )
                setProgress(progressData)
            } catch (e: Exception) {
                loge("Error downloading asset $name", e)

                val progressData = workDataOf(
                    KEY_PROGRESS_NAME to name,
                    KEY_PROGRESS_IS_ERROR to true
                )
                setProgress(progressData)

                try {
                    file.truncateAndDelete()
                    logv("Deleted file $fileTempPath")
                } catch (e: Exception) {
                    loge("Failed to delete file $fileTempPath on onStopped")
                }

                throw e
            }
        }

    /**
     * Update notification for file name [name] with total processed bytes [fileDoneBytes] and whether it is done specified by [isDone]
     */
    @Synchronized
    private fun updateNotificationProgress(name: String, fileDoneBytes: Long, isDone: Boolean) {
        if (isDone) {
            doneFiles++
        } else {
            doneBytesPerFile[name] = fileDoneBytes
        }

        val doneBytes = doneBytesPerFile.values.sum()
        var percent = 0
        if (totalSize > 0) {
            percent = ((doneBytes.toFloat() / totalSize) * 100).toInt()
        }
        if (donePercent != percent || isDone) {
            // Only bother updating if percentage changes or file is done
            donePercent = percent
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(doneFiles, percent, doneBytes)
            )
        }
    }

    /**
     * Build notification with total done files count of [doneFiles], total percent complete of [percent] and total done bytes of [doneBytes]
     */
    private fun buildNotification(doneFiles: Int, percent: Int, doneBytes: Long): Notification {
        val intent = WorkManager.getInstance(context)
            .createCancelPendingIntent(id)

        val title =
            context.getString(
                R.string.download_notification_title,
                doneFiles,
                totalFiles,
                StringFormatter.fileSize(doneBytes),
                StringFormatter.fileSize(totalSize),
                percent
            )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.baseline_clear_24,
                context.getString(android.R.string.cancel),
                intent
            )
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        // Create the NotificationChannel
        val name = context.getString(R.string.download_channel_name)
        val descriptionText = context.getString(R.string.download_channel_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance)
        channel.description = descriptionText

        // Register the channel with the system
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val DOWNLOAD_WORKER_TAG = "DOWNLOAD_WORKER_TAG"

        const val KEY_STORAGE_DIR = "KEY_STORAGE_DIR"
        const val KEY_URLS = "KEY_URLS"
        const val KEY_FILE_NAMES = "KEY_FILE_NAMES"
        const val KEY_TOTAL_SIZE = "KEY_TOTAL_SIZE"

        const val KEY_PROGRESS_NAME = "KEY_PROGRESS_NAME"
        const val KEY_PROGRESS_IS_PROGRESS = "KEY_PROGRESS_IS_PROGRESS"
        const val KEY_PROGRESS_IS_DONE = "KEY_PROGRESS_IS_DONE"
        const val KEY_PROGRESS_IS_ERROR = "KEY_PROGRESS_IS_ERROR"
        const val KEY_PROGRESS_CURRENT_SIZE = "KEY_PROGRESS_CURRENT_SIZE"
        const val KEY_PROGRESS_TOTAL_SIZE = "KEY_PROGRESS_TOTAL_SIZE"

        val NOTIFICATION_ID = "DOWNLOAD_NOTIFICATION_ID".hashCode()
        const val CHANNEL_ID = "DOWNLOAD_CHANNEL_ID"
    }
}