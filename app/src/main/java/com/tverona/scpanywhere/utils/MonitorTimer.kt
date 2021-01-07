package com.tverona.scpanywhere.utils

import androidx.lifecycle.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lifecycle aware monitor timer
 */
class MonitorTimer(private val lifecycleOwner: LifecycleOwner) {
    // Timer action
    private enum class TimerAction {
        START,
        PULSE,
        STOP,
    }

    private data class Timer(val action: TimerAction, val url: String?)

    // Timer live data
    private var timer = MutableLiveData<Timer>()

    private var lastUrl: String? = null
    private val stopWatch = StopWatch()
    private var onElapsed: ((String, Long) -> Unit)? = null

    fun start(url: String, onElapsed: ((String, Long) -> Unit)? = null) {
        timer.value = Timer(TimerAction.START, url)
        this.onElapsed = onElapsed
    }

    fun stop() {
        timer.value = Timer(TimerAction.STOP, null)
    }

    init {
        // Background coroutine to periodically pulse the timer
        lifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(60000L)
                timer.value = Timer(TimerAction.PULSE, null)
            }
        }

        // Observe the timer:
        // On start, save off the url & start the stop watch.
        // On pulse, persist current elapsed timed & reset the stop watch.
        // On stop, persist current elapsed time & stop the stop watch.
        timer.observe(lifecycleOwner) {
            when (it.action) {
                TimerAction.START -> {
                    stopWatch.start()
                    lastUrl = it.url
                }
                TimerAction.PULSE -> {
                    val elapsedSecs = stopWatch.stop() / 1000
                    stopWatch.start()

                    if (lastUrl != null) {
                        onElapsed?.invoke(lastUrl!!, elapsedSecs)
                    }
                }
                TimerAction.STOP -> {
                    val elapsedSecs = stopWatch.stop() / 1000
                    if (lastUrl != null) {
                        onElapsed?.invoke(lastUrl!!, elapsedSecs)
                    }
                    lastUrl = null
                }
            }
        }
    }
}