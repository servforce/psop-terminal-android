package com.rokid.cxrmsamples.network

import android.content.Context
import android.content.SharedPreferences

object PsopConfig {

    private const val PREF_NAME = "psop_config"
    private const val KEY_SERVER_HOST = "server_host"

    // 默认值（首次启动时需在设置页配置实际服务器地址）
    private const val DEFAULT_HOST = "0.0.0.0"
    private const val DEFAULT_PORT = "8001"
    private const val DEFAULT_ASR_PORT = "12302"

    /** 运行时动态计算的地址 */
    var baseUrl: String = "http://$DEFAULT_HOST:$DEFAULT_PORT/api/v1/"
        private set
    var wsUrl: String = "ws://$DEFAULT_HOST:$DEFAULT_PORT/ws"
        private set
    var asrUrl: String = "http://$DEFAULT_HOST:$DEFAULT_ASR_PORT/v1/audio/transcriptions"
        private set

    /**
     * 从 SharedPreferences 加载已保存的服务器地址，
     * 未保存过则使用默认值。App 启动时调用一次即可。
     */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_SERVER_HOST, null)
        if (host.isNullOrBlank()) {
            // 未配置，使用默认值
            applyHost(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_ASR_PORT)
        } else {
            parseAndApply(host)
        }
    }

    /**
     * 保存用户输入的服务器地址，并立即生效。
     * @param host 格式：IP:端口，如 "192.168.1.100:8001"，也可只写 IP（使用默认端口）
     */
    fun save(context: Context, host: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_HOST, host.trim()).apply()
        parseAndApply(host.trim())
    }

    /**
     * 读取已保存的地址字符串（用于设置页显示）
     */
    fun getSavedHost(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_HOST, "") ?: ""
    }

    /**
     * 解析 "host:port" 格式，支持以下输入：
     * - "192.168.1.100"          → 使用默认端口
     * - "192.168.1.100:9000"     → 自定义端口
     * - "http://192.168.1.100:9000" → 自动去掉协议头
     */
    private fun parseAndApply(input: String) {
        val cleaned = input
            .removePrefix("http://")
            .removePrefix("https://")
            .removeSuffix("/")

        val parts = cleaned.split(":")
        val host = parts[0].ifBlank { DEFAULT_HOST }
        val port = parts.getOrNull(1)?.ifBlank { DEFAULT_PORT } ?: DEFAULT_PORT

        applyHost(host, port, DEFAULT_ASR_PORT)
    }

    private fun applyHost(host: String, port: String, asrPort: String) {
        baseUrl = "http://$host:$port/api/v1/"
        wsUrl = "ws://$host:$port/ws"
        asrUrl = "http://$host:$asrPort/v1/audio/transcriptions"
    }
}
