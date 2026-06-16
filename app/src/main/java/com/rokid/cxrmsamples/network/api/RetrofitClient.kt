package com.rokid.cxrmsamples.network.api

import com.rokid.cxrmsamples.network.PsopConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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
