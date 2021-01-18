package com.tverona.scpanywhere.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationCompat
import androidx.hilt.Assisted
import androidx.hilt.work.WorkerInject
import androidx.work.*
import com.google.common.util.concurrent.ListenableFuture
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.utils.*
import com.tverona.scpanywhere.repositories.SpeechContent
import kotlinx.coroutines.*

class SpeechProviderWorker @WorkerInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val textToSpeechProvider: TextToSpeechProvider,
    private val speechContent: SpeechContent
) : ListenableWorker(context, workerParams) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var totalUtterances = 0
    private var title: String? = null

    /**
     * Read aloud
     */
    override fun startWork(): ListenableFuture<Result> {
        return CallbackToFutureAdapter.getFuture { completer ->
            val content = speechContent.content
            if (null == content) {
                logv("Nothing to speak!")
                completer.set(Result.success())
            } else {
                totalUtterances = 0
                val url = inputData.getString(KEY_URL)
                if (speechContent.url.isNullOrEmpty() || !speechContent.url.equals(url, ignoreCase = true)) {
                    speechContent.currentUtteranceId = null
                }
                speechContent.url = url
                title = inputData.getString(KEY_TITLE)

                // Mark the Worker as important
                setForegroundAsync(createForegroundInfo())

                try {
                    speak(content, completer)
                } catch (e: Exception) {
                }
            }
        }
    }

    /**
     * Handle stop request
     */
    override fun onStopped() {
        super.onStopped()
        textToSpeechProvider.stop()
    }

    /**
     * Speak content provided by [content]
     */
    private fun speak(
        content: String,
        completer: CallbackToFutureAdapter.Completer<Result>
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            textToSpeechProvider.speak(
                content,
                speechContent.currentUtteranceId,
                object : TextToSpeechProvider.SpeechProgress {
                    override fun onStart(totalUtterances: Int) {
                        this@SpeechProviderWorker.totalUtterances = totalUtterances
                        updateNotification(speechContent.currentUtteranceId ?: 0)
                    }

                    override fun onUtteranceDone(utteranceId: Int) {
                        logv("onUtteranceDone: $utteranceId")
                        speechContent.currentUtteranceId = utteranceId
                        updateNotification(utteranceId)
                    }

                    override fun onDone() {
                        textToSpeechProvider.stop()
                        speechContent.currentUtteranceId = null
                        completer.set(Result.success())
                    }

                    override fun onError(utteranceId: Int) {
                        textToSpeechProvider.stop()
                        completer.set(Result.success())
                    }
                })
        }
    }

    /**
     * Build notification
     */
    private fun buildNotification(
        progress: Int
    ): Notification {
        val intent = WorkManager.getInstance(context)
            .createCancelPendingIntent(id)

        val title =
            context.getString(
                R.string.speechprovider_notification_title,
                this.title
            )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.baseline_clear_24,
                context.getString(android.R.string.cancel),
                intent
            )

        if (totalUtterances != 0) {
            builder.setProgress(totalUtterances, progress, false)
        }

        return builder.build()
    }

    /**
     * Update notification with progress
     */
    private fun updateNotification(progress: Int) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun createForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel()
        }
        val notification = buildNotification(0)
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        // Create the NotificationChannel
        val name = context.getString(R.string.speechprovider_channel_name)
        val descriptionText = context.getString(R.string.speechprovider_channel_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance)
        channel.description = descriptionText

        // Register the channel with the system
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val SPEECHPROVIDER_WORKER_TAG = "SPEECHPROVIDER_WORKER_TAG"

        const val KEY_TITLE = "KEY_TITLE"
        const val KEY_URL = "KEY_URL"

        val NOTIFICATION_ID = "SPEECHPROVIDER_NOTIFICATION_ID".hashCode()
        const val CHANNEL_ID = "SPEECHPROVIDER_CHANNEL_ID"
    }
}