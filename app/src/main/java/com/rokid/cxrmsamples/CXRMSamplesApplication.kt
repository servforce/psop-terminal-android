package com.rokid.cxrmsamples

import android.app.Application
import android.util.Log

/**
 * 全局 Application：安装定向崩溃守卫。
 *
 * 背景（研究员对 client-m 1.0.9 反编译实锤的 SDK bug）：
 * WifiController 在 discoverPeers 失败 reason==2 时，内部 Handler.postDelayed(2000)
 * 无限重试（WifiController$c）；其方法 e() 对 WifiP2pManager 字段不做判空。
 * deinitWifiP2P 会把管理器字段置 null，但**不取消** Handler 队列中的重试 Runnable，
 * 且复位了内部守卫标志——deinit 后 2 秒内重试 Runnable 到期，e() 内访问 null 管理器
 * 抛 NullPointerException，进程闪退。
 *
 * 守卫策略（方案 A，用户确认）：
 * - 仅吞掉"该 SDK bug 特征"的 NPE，进程存活；
 * - 其余任何异常原样转交系统默认 handler，崩溃行为完全不变；
 * - 不加拆除延时、不改任何现有 P2P 逻辑。
 */
class CXRMSamplesApplication : Application() {

    companion object {
        private const val TAG = "CxrCrashGuard"

        /** SDK bug 特征：崩溃帧所在类（反编译实锤） */
        private const val WIFI_CONTROLLER_CLASS = "com.rokid.cxr.client.extend.controllers.WifiController"
    }

    override fun onCreate() {
        super.onCreate()
        // 取系统默认 handler（KillApplicationHandler），未命中特征时原样转交
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isSdkWifiControllerNpe(throwable)) {
                // 命中 SDK bug 特征：打完整堆栈后吞掉，不向默认 handler 转发，进程存活
                Log.w(TAG, "swallowed SDK WifiController NPE (known deinit race), thread=${thread.name}", throwable)
            } else {
                // 未命中：保持系统崩溃行为不变
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        Log.i(TAG, "crash guard installed")
    }

    /**
     * 判断异常是否命中 SDK WifiController deinit 竞态 NPE 特征。
     *
     * 匹配条件（依据反编译结论）：
     * 1) 异常本身是 NullPointerException；
     * 2) 堆栈中存在任一帧满足：className == WifiController 全限定名，
     *    且 methodName == "e"（无判空访问管理器的混淆方法），
     *    或整条堆栈字符串中含 "discoverPeers"（重试 Runnable 由 discoverPeers
     *    失败触发，堆栈会携带该调用链特征，作为方法名混淆变化时的兜底判据）。
     *
     * 异常链：若 e 本身不匹配，沿 cause 链逐层判断（防止异常被包装后漏判），
     * 带环路保护避免 cause 自引用死循环。
     */
    private fun isSdkWifiControllerNpe(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 10) {
            if (current is NullPointerException && matchesWifiControllerFrames(current)) {
                return true
            }
            val next = current.cause
            current = if (next === current) null else next
            depth++
        }
        return false
    }

    /** 单个异常的堆栈帧特征匹配 */
    private fun matchesWifiControllerFrames(throwable: Throwable): Boolean {
        val stackText = Log.getStackTraceString(throwable)
        for (frame in throwable.stackTrace) {
            if (frame.className == WIFI_CONTROLLER_CLASS) {
                // 判据 1：混淆方法名 e（无判空访问 WifiP2pManager 字段的方法）
                if (frame.methodName == "e") return true
                // 判据 2：该帧所在的 WifiController 调用链含 discoverPeers
                if (frame.methodName.contains("discoverPeers")) return true
            }
        }
        // 兜底：堆栈全文含 discoverPeers（重试 Runnable WifiController$c 由 discoverPeers
        // 失败回调 postDelayed 触发，方法名混淆后仍会保留公开 API 名 discoverPeers）
        return stackText.contains("discoverPeers") && stackText.contains(WIFI_CONTROLLER_CLASS)
    }
}
