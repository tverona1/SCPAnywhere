package com.tverona.scpanywhere.utils

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Extension method to observe only once
 */
fun <T> LiveData<T>.observeOnce(owner: LifecycleOwner, reactToChange: (T) -> Unit): Observer<T> {
    val wrappedObserver = object : Observer<T> {
        override fun onChanged(data: T) {
            reactToChange(data)
            removeObserver(this)
        }
    }

    observe(owner, wrappedObserver)
    return wrappedObserver
}

/**
 * Extension method to enable await on observable
 */
suspend fun <T> LiveData<T>.await(): T {
    return withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val observer = object : Observer<T> {
                override fun onChanged(value: T) {
                    removeObserver(this)
                    continuation.resume(value, {})
                }
            }

            observeForever(observer)

            continuation.invokeOnCancellation {
                removeObserver(observer)
            }
        }
    }
}

/**
 * Extension to combine two observables into one mediator - both must be fired to fire the mediator
 */
fun <T, K, R> LiveData<T>.combineWith(
    liveData: LiveData<K>,
    block: (T?, K?) -> R
): LiveData<R> {
    var setThis = false
    var setOther = false

    // Only fire the result if *both* live data sources have a value
    val result = MediatorLiveData<R>()

    result.addSource(this) {
        setThis = true
        if (setOther) {
            result.value = block(this.value, liveData.value)
        }
    }
    result.addSource(liveData) {
        setOther = true
        if (setThis) {
            result.value = block(this.value, liveData.value)
        }
    }
    return result
}