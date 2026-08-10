package com.rokid.cxrmsamples.network.api

import com.rokid.cxrmsamples.network.PsopConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    // 生产日志级别用 HEADERS：避免 multipart 上传时全量写 body 日志拖慢上传
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        // connectTimeout 降到 15s：快速失败后走重试，避免 P2P 抢占路由时挂满 30s
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // 整体调用超时 45s：上传链路兜底（对普通短接口无副作用，仅限制最长耗时；
        // 与 ViewModel 层 40s 上传总预算配合，快速失败后走重试）
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private var _apiService: PsopApiService? = null

    /**
     * 获取 API 服务实例。
     * 首次调用时创建，URL 变更后自动重建（通过 resetApiService()）。
     */
    val apiService: PsopApiService
        get() {
            if (_apiService == null) {
                _apiService = buildApiService()
            }
            return _apiService!!
        }

    /**
     * URL 变更后调用，强制下次使用时重建 Retrofit 实例
     */
    fun resetApiService() {
        _apiService = null
    }

    private fun buildApiService(): PsopApiService {
        return Retrofit.Builder()
            .baseUrl(PsopConfig.baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PsopApiService::class.java)
    }
}
