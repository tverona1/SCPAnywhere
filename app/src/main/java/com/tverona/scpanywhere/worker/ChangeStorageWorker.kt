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
import com.tverona.scpanywhere.repositories.OfflineDataRepository
import com.tverona.scpanywhere.utils.*
import kotlinx.coroutines.*
import java.io.File

class ChangeStorageWorker @WorkerInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val offlineDataRepository: OfflineDataRepository
) : CoroutineWorker(context, workerParams) {
    private val notificationManager =
        context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Change storage location
     */
    override suspend fun doWork(): Result {
        val sourcePath = inputData.getString(KEY_SOURCE_PATH)
        val destPath = inputData.getString(KEY_DEST_PATH)
        if (null == sourcePath || null == destPath) {
            return Result.failure()
        }

        logv("Changing storage path from $sourcePath to $destPath")

        // Mark the Worker as important
        setForeground(createForegroundInfo())

        return try {
            changeStorageLocation(sourcePath, destPath)
            Result.success()
        } catch (e: Exception) {
            loge("Error change storage path from $sourcePath to $destPath", e)
            notificationManager.cancel(NOTIFICATION_ID)
            Result.failure()
        }
    }

    /**
     * Change storage location from [sourcePath] to [destPath], moving any downloaded data.
     */
    private suspend fun changeStorageLocation(sourcePath: String, destPath: String) {
        withContext(Dispatchers.IO) {
            // Clean up any temporary files
            offlineDataRepository.cleanupTempFiles()

            val destTempFiles = mutableListOf<File>()
            val sourceFiles =
                offlineDataRepository.getFiles(sourcePath, OfflineDataRepository.zipExt)

            try {
                sourceFiles.forEachIndexed { index, localAssetMetadata ->
                    val sourceFile = File(localAssetMetadata.path)
                    val nameWithoutExt = sourceFile.nameWithoutExtension
                    val destTempFile =
                        File(destPath + File.separator + nameWithoutExt + OfflineDataRepository.tmpExt)

                    logv(
                        "Copying ${sourceFile.absolutePath} to ${destTempFile.absolutePath} ${
                            StringFormatter.fileSize(
                                localAssetMetadata.size
                            )
                        }"
                    )

                    if (!isActive) {
                        throw java.io.IOException("Change storage path cancelled")
                    }

                    val progressData = workDataOf(
                        KEY_PROGRESS_CURRENT_FILENAME to sourceFile.nameWithoutExtension,
                        KEY_PROGRESS_CURRENT_FILE to index,
                        KEY_PROGRESS_TOTAL_FILES to sourceFiles.size
                    )
                    setProgress(progressData)
                    updateNotification(
                        sourceFile.nameWithoutExtension,
                        index,
                        sourceFiles.size
                    )

                    sourceFile.copyTo(destTempFile)
                    logv(
                        "Finished copying ${sourceFile.absolutePath} to ${destTempFile.absolutePath} ${
                            StringFormatter.fileSize(
                                localAssetMetadata.size
                            )
                        }"
                    )
                    destTempFiles.add(destTempFile)
                }

                destTempFiles.forEach {
                    val destFile =
                        File(destPath + File.separator + it.nameWithoutExtension + OfflineDataRepository.zipExt)
                    logv("Renaming ${it.absolutePath} to ${destFile.name}")
                    it.rename(destFile)
                }

                sourceFiles.forEach {
                    logv("Deleting ${it.path}")
                    val sourceFile = File(it.path)
                    sourceFile.truncateAndDelete()
                }

                // Update storage location
                offlineDataRepository.setStorageLocation(destPath)
            } catch (e: Exception) {
                loge("Error moving files: ${e.message}", e)
            } finally {
                withContext(NonCancellable) {
                    // Clean up any temporary files
                    offlineDataRepository.cleanupTempFiles()
                }
            }

            // Reload offline repository
            offlineDataRepository.load()
        }
    }

    /**
     * Build notification for current file name [name] and index [currentFile] out of total files [totalFiles]
     */
    private fun buildNotification(
        name: String,
        currentFile: Int,
        totalFiles: Int,
    ): Notification {
        val intent = WorkManager.getInstance(context)
            .createCancelPendingIntent(id)

        val title =
            context.getString(R.string.changestorage_notification_title, name, currentFile, totalFiles)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setProgress(totalFiles, currentFile, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.baseline_clear_24,
                context.getString(android.R.string.cancel),
                intent
            )
            .build()
    }

    /**
     * Update notification with progress
     */
    private fun updateNotification(name: String, currentFile: Int, totalFiles: Int) {
        val notification = buildNotification(name, currentFile, totalFiles)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel()
        }
        val notification = buildNotification("", 0, 0)
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        // Create the NotificationChannel
        val name = context.getString(R.string.changestorage_channel_name)
        val descriptionText = context.getString(R.string.changestorage_channel_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance)
        channel.description = descriptionText

        // Register the channel with the system
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANGESTORAGE_WORKER_TAG = "CHANGESTORAGE_WORKER_TAG"

        const val KEY_SOURCE_PATH = "KEY_SOUCRE_PATH"
        const val KEY_DEST_PATH = "KEY_DEST_PATH"
        const val KEY_PROGRESS_CURRENT_FILENAME = "KEY_PROGRESS_CURRENT_FILENAME"
        const val KEY_PROGRESS_CURRENT_FILE = "KEY_PROGRESS_CURRENT_FILE"
        const val KEY_PROGRESS_TOTAL_FILES = "KEY_PROGRESS_TOTAL_FILES"

        val NOTIFICATION_ID = "CHANGESTORAGE_NOTIFICATION_ID".hashCode()
        const val CHANNEL_ID = "CHANGESTORAGE_CHANNEL_ID"
    }
}