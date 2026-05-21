package com.rokid.cxrmsamples.activities.mediaFile

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

class WifiHostUtils2 {
    companion object {
        private const val TAG = "WifiHostUtils2"
        val Instance by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            WifiHostUtils2()
        }
    }

    private var observeNetworkCallback: ConnectivityManager.NetworkCallback? = null

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

        observeNetworkCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }

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
            val isStillDisabled = !wifiManager.isWifiEnabled
            if (!enabled && isStillDisabled) {
                Log.e(TAG, "Failed to enable Wi-Fi")
                listener?.onFailed("Wi-Fi disabled")
                return
            }
        }

        val config = buildWifiConfig(ssid, password, securityType, listener) ?: return
        val existingNetworkId = findExistingNetworkId(wifiManager, config.SSID)
        val networkId = existingNetworkId ?: wifiManager.addNetwork(config)
        if (networkId == -1) {
            Log.e(TAG, "Android 10+ restricts addNetwork for: $targetSsid")
            listener?.onFailed("Android 10+ restricts direct Wi-Fi connection")
            return
        }

        val enabled = wifiManager.enableNetwork(networkId, true)
        val reconnected = wifiManager.reconnect()
        if (!enabled || !reconnected) {
            Log.e(TAG, "Failed to enable/reconnect Wi-Fi: $targetSsid")
            listener?.onFailed("Failed to enable Wi-Fi network")
            return
        }

        Log.w(TAG, "Android 10+ may restrict direct Wi-Fi connection")

        observeConnectedWifi(wifiManager, connectivityManager, targetSsid, ip, listener)
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
                    config.wepKeys[0] = "\"$password\""
                    config.wepTxKeyIndex = 0
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                    config
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

        val request = android.net.NetworkRequest.Builder()
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
            listener?.onConnected(ip)
        }
    }

    private fun normalizeSsid(ssid: String?): String {
        if (ssid.isNullOrBlank()) return ""
        return ssid.trim().trim('"')
    }
}