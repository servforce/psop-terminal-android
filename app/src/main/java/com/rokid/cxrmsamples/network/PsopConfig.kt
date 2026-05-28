package com.rokid.cxrmsamples.network

object PsopConfig {
    // 开发阶段指向宿主机（Android 模拟器用 10.0.2.2，真机用局域网 IP）
    var baseUrl: String = "http://10.0.1.158:8001/api/v1/"
    var wsUrl: String = "ws://10.0.1.158:8001/ws"
    var asrUrl: String = "http://10.0.0.20:12302/v1/audio/transcriptions"
}
