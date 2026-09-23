package com.repovoyage.sign.camera

import android.net.ConnectivityManager
import android.net.Network

/**
 * 进程网络绑定（照 Demo CameraWifiProcessNetworkBinder 抄录）。
 * 不打日志：networkHandle/SSID 均可间接标识相机热点，属安全红线。
 */
internal object ProcessNetworkBinder {

    fun bind(connectivityManager: ConnectivityManager, network: Network): Boolean =
        runCatching { connectivityManager.bindProcessToNetwork(network) }.getOrDefault(false)

    fun unbind(connectivityManager: ConnectivityManager) {
        runCatching { connectivityManager.bindProcessToNetwork(null) }
    }
}
