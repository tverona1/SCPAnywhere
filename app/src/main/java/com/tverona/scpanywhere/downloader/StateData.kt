package com.tverona.scpanywhere.downloader

/**
 * Object representing state of async downloading & other IO operations
 */
data class StateData<out T>(val status: Status, val data: T?, val error: Throwable?) {
    enum class Status {
        SUCCESS,
        ERROR,
        UPDATE
    }

    companion object {

        fun <T> success(data: T?): StateData<T> {
            return StateData(Status.SUCCESS, data, null)
        }

        fun <T> error(data: T?, err: Throwable?): StateData<T> {
            return StateData(Status.ERROR, data, err)
        }

        fun <T> update(data: T?): StateData<T> {
            return StateData(Status.UPDATE, data, null)
        }
    }
}