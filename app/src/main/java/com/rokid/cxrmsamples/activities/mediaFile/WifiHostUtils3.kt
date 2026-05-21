package com.rokid.cxrmsamples.activities.mediaFile

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.cxr.client.controllers.CxrController

class WifiHostUtils3 {
    companion object {
        private const val TAG = "WifiHostUtils3"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        val Instance by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            WifiHostUtils3()
        }
    }

    private var observeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    @SuppressLint("MissingPermission")
    fun initWifiHot(
        context: Context,
        ssid: String,
        password: String,
        ip: String,
        securityType: Int,
        listener: WifiHostUtils.WifiConnectListener? = null
    ) {
        val appContext = context.applicationContext
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
        if (wifiManager == null) {
            listener?.onFailed("WifiManager unavailable")
            return
        }
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            listener?.onFailed("ConnectivityManager unavailable")
            return
        }

        cleanupInternal(connectivityManager)

        val targetSsid = normalizeSsid(ssid)
        if (targetSsid.isBlank()) {
            listener?.onFailed("SSID is blank")
            return
        }

        if (!wifiManager.isWifiEnabled) {
            val enabled = runCatching {
                wifiManager.isWifiEnabled = true
                true
            }.getOrDefault(false)
            if (!enabled && !wifiManager.isWifiEnabled) {
                Log.e(TAG, "Failed to enable Wi-Fi")
                listener?.onFailed("Wi-Fi disabled")
                return
            }
        }

        val config = buildWifiConfig(targetSsid, password, securityType, listener) ?: return
        val existingNetworkId = findExistingNetworkId(wifiManager, config.SSID)
        val networkId = existingNetworkId ?: wifiManager.addNetwork(config)
        if (networkId == -1) {
            Log.e(TAG, "Failed to add Wi-Fi network: $targetSsid")
            listener?.onFailed("Failed to add Wi-Fi network")
            return
        }

        val enabled = wifiManager.enableNetwork(networkId, true)
        val reconnected = wifiManager.reconnect()
        if (!enabled || !reconnected) {
            Log.e(TAG, "Failed to enable/reconnect Wi-Fi: $targetSsid")
            listener?.onFailed("Failed to enable Wi-Fi network")
            return
        }

        observeConnectedWifi(wifiManager, connectivityManager, targetSsid, ip, listener)
        scheduleTimeout(targetSsid, listener)
    }

    fun cleanup(context: Context? = null) {
        val appContext = context?.applicationContext
        val connectivityManager = appContext?.getSystemService(ConnectivityManager::class.java)
        cleanupInternal(connectivityManager)
    }

    private fun cleanupInternal(connectivityManager: ConnectivityManager?) {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        observeNetworkCallback?.let { callback ->
            if (connectivityManager != null) {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }
        observeNetworkCallback = null
    }

    private fun scheduleTimeout(targetSsid: String, listener: WifiHostUtils.WifiConnectListener?) {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            Log.e(TAG, "Wi-Fi connect timeout: $targetSsid")
            listener?.onFailed("Connect timeout")
        }
        timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, CONNECT_TIMEOUT_MS)
    }

    private fun buildWifiConfig(
        ssid: String,
        password: String,
        securityType: Int,
        listener: WifiHostUtils.WifiConnectListener?
    ): WifiConfiguration? {
        val config = WifiConfiguration()
        config.SSID = "\"$ssid\""
        if (password.isBlank()) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            return config
        }

        return try {
            when (securityType) {
                0 -> {
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    config
                }
                1 -> {
                    config.hiddenSSID = true
                    config.wepKeys[0] = "\"$password\""
                    config.wepTxKeyIndex = 0
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                    config
                }
                2 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE)
                        config.preSharedKey = "\"$password\""
                        config.hiddenSSID = true
                        config
                    } else {
                        config.preSharedKey = "\"$password\""
                        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        config
                    }
                }
                else -> {
                    config.preSharedKey = "\"$password\""
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    config
                }
            }
        } catch (ex: IllegalArgumentException) {
            Log.e(TAG, "Invalid Wi-Fi credentials: ${ex.message}")
            listener?.onFailed("Invalid Wi-Fi credentials")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun findExistingNetworkId(wifiManager: WifiManager, quotedSsid: String?): Int? {
        if (quotedSsid.isNullOrBlank()) return null
        return runCatching {
            wifiManager.configuredNetworks
                ?.firstOrNull { normalizeSsid(it.SSID) == normalizeSsid(quotedSsid) }
                ?.networkId
        }.getOrNull()
    }

    private fun observeConnectedWifi(
        wifiManager: WifiManager,
        connectivityManager: ConnectivityManager,
        targetSsid: String,
        ip: String,
        listener: WifiHostUtils.WifiConnectListener?
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
                    Log.i(TAG, "Wi-Fi connected: $connectedSsid")
                    connectivityManager.bindProcessToNetwork(network)
                    alreadyReported = true
                    timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
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

        val currentSsid = normalizeSsid(wifiManager.connectionInfo?.ssid)
        if (!alreadyReported && currentSsid.isNotBlank() && currentSsid == target) {
            Log.i(TAG, "Wi-Fi already connected: $currentSsid")
            alreadyReported = true
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            listener?.onConnected(ip)
        }
    }

    private fun normalizeSsid(ssid: String?): String {
        if (ssid.isNullOrBlank()) return ""
        return ssid.trim().trim('"')
    }
}