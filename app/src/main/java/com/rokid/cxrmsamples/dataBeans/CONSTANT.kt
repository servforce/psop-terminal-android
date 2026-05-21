package com.rokid.cxrmsamples.dataBeans

import android.annotation.SuppressLint
import android.os.Build
import com.rokid.cxrmsamples.R

/**
 * 全局常量：SDK 配置与设备连接相关。
 * 文档参考：02SDK 导入、03快速开始（CLIENT_SECRET、getSNResource、授权文件）；
 * 01设备连接（BLUETOOTH_PERMISSIONS、SERVICE_UUID、BLE 扫描与连接）。
 */
object CONSTANT {
    const val BLUETOOTH_PERMISSION_REQUEST = 0x0010

    @SuppressLint("ObsoleteSdkInt")
    val BLUETOOTH_PERMISSIONS = mutableListOf(
        android.Manifest.permission.BLUETOOTH,
        android.Manifest.permission.BLUETOOTH_ADMIN,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(android.Manifest.permission.BLUETOOTH_CONNECT)
            add(android.Manifest.permission.BLUETOOTH_SCAN)
        }
    }.toTypedArray()

    const val SERVICE_UUID = "00009100-0000-1000-8000-00805f9b34fb"


    // Client Secret -- copy from https://ar.rokid.com/ -->Account Center-->Credential information
    const val CLIENT_SECRET = "040dd381-266e-11f1-961e-043f72fdb9c8"
    fun getSNResource() = R.raw.sn_1901092545039492

    const val CUSTOM_CMD = "rk_custom_key"

}