package com.tverona.scpanywhere.utils

import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.*
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent

class ConnectivityMonitor(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    private val callback: (Boolean) -> Unit
) : LifecycleObserver {
    private var lastIsConnected: Boolean? = null

    private var connectivityManager =
        context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .build()

    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    @Suppress("unused")
    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() {
        toggleConnectionState()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    @Suppress("unused")
    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onPause() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    private fun toggleConnectionState() {
        val isConnected = isConnected()
        if (lastIsConnected == null || isConnected != lastIsConnected) {
            logv("Connections State changed: connect is $isConnected")
            callback.invoke(isConnected)
            lastIsConnected = isConnected
        }
    }

    private fun isConnected(): Boolean {
        val networkCapabilities = connectivityManager.activeNetwork ?: return false
        val actNw =
            connectivityManager.getNetworkCapabilities(networkCapabilities) ?: return false
        return when {
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            lastInternetConnectionCheck()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            lastInternetConnectionCheck()
        }

        private fun lastInternetConnectionCheck() {
            //connectivityManager.activeNetworkInfo?.isConnectedOrConnecting == true
            toggleConnectionState()
        }
    }
}