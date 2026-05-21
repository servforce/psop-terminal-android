package com.rokid.cxrmsamples.activities.mediaFile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log

class WifiHostUtils {
    companion object {
        private const val TAG = "WifiHostUtils"
        val Instance by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            WifiHostUtils()
        }
    }

    interface WifiConnectListener {
        fun onConnected(ipAddress: String)
        fun onFailed(reason: String)
        fun onDisconnected(reason: String)
    }

    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var observeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    fun initWifiHot(
        context: Context,
        name: String,
        password: String,
        ip: String,
        securityType: Int,
        listener: WifiConnectListener? = null
    ) {

        val appContext = context.applicationContext
        val connectivityManager =
            appContext.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            listener?.onFailed("ConnectivityManager unavailable")
            return
        }

        activeNetworkCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        observeNetworkCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }

        val specifier = buildWifiSpecifier(name, password, securityType, listener) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Wi-Fi hotspot connected: $name")
                connectivityManager.bindProcessToNetwork(network)
                listener?.onConnected(ip)
            }

            override fun onUnavailable() {
                Log.e(TAG, "Wi-Fi hotspot unavailable: $name")
                listener?.onFailed("Network unavailable")
            }

            override fun onLost(network: Network) {
                Log.e(TAG, "Wi-Fi hotspot disconnected: $name")
                listener?.onDisconnected("Network lost")
            }
        }

        activeNetworkCallback = callback
        connectivityManager.requestNetwork(request, callback)
        Log.i(TAG, "Requesting Wi-Fi hotspot: $name")

        observeConnectedWifi(appContext, connectivityManager, name, ip, listener)
    }

    private fun buildWifiSpecifier(
        name: String,
        password: String,
        securityType: Int,
        listener: WifiConnectListener?
    ): WifiNetworkSpecifier? {
        val builder = WifiNetworkSpecifier.Builder().setSsid(name)
        if (password.isBlank()) {
            return builder.build()
        }

        return try {
            when (securityType) {
                0 -> builder.build()
                2 -> builder.setWpa3Passphrase(password).build()
                else -> builder.setWpa2Passphrase(password).build()
            }
        } catch (ex: IllegalArgumentException) {
            Log.e(TAG, "Invalid Wi-Fi credentials: ${ex.message}")
            listener?.onFailed("Invalid Wi-Fi credentials")
            null
        }
    }

    private fun observeConnectedWifi(
        context: Context,
        connectivityManager: ConnectivityManager,
        targetSsid: String,
        ip: String,
        listener: WifiConnectListener?
    ) {
        val target = normalizeSsid(targetSsid)
        var alreadyReported = false

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                val connectedSsid = normalizeSsid(wifiInfo?.ssid)
                if (!alreadyReported && connectedSsid.isNotBlank() && connectedSsid == target) {
                    Log.i(TAG, "Wi-Fi connected via system settings: $connectedSsid")
                    connectivityManager.bindProcessToNetwork(network)
                    alreadyReported = true
                    listener?.onConnected(ip)
                }
            }

            override fun onLost(network: Network) {
                if (alreadyReported) {
                    Log.e(TAG, "Wi-Fi disconnected: $target")
                    listener?.onDisconnected("Network lost")
                }
            }
        }

        observeNetworkCallback = callback
        connectivityManager.registerNetworkCallback(request, callback)

        // Fallback: try read current Wi-Fi SSID immediately.
        val wifiManager = context.getSystemService(WifiManager::class.java)
        val currentSsid = normalizeSsid(wifiManager?.connectionInfo?.ssid)
        if (!alreadyReported && currentSsid.isNotBlank() && currentSsid == target) {
            Log.i(TAG, "Wi-Fi already connected: $currentSsid")
            alreadyReported = true
            listener?.onConnected(ip)
        }
    }

    private fun normalizeSsid(ssid: String?): String {
        if (ssid.isNullOrBlank()) return ""
        return ssid.trim().trim('"')
    }
}