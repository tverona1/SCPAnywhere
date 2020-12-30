package com.tverona.scpanywhere.utils

/**
 * A stop watch :)
 */
class StopWatch {
    private var startTime: Long = 0L

    fun reset() {
        startTime = 0L
    }

    fun start() {
        startTime = System.currentTimeMillis()
    }

    fun stop(): Long {
        if (startTime == 0L) {
            return 0
        }

        val elapsed = System.currentTimeMillis() - startTime
        startTime = 0
        return elapsed
    }
}
