package com.repovoyage.sign.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress

/**
 * 蜂窝云端网络提供者抽象：真实实现包装 ConnectivityManager（句柄 =
 * android.net.Network）；JVM 单测注入替身（句柄 = 普通对象），管理器
 * 状态机因此可脱离 android.jar 验证。
 */
interface CloudNetworkProvider {

    fun request(listener: Listener)
    fun unregister(listener: Listener)

    /** 构建绑定该网络句柄的 HTTP 客户端（socketFactory + DNS 均走此网络） */
    fun boundClient(handle: Any): OkHttpClient

    interface Listener {
        fun onAvailable(handle: Any)
        fun onLost(handle: Any)
        fun onUnavailable()
    }
}

/**
 * ConnectivityManager 实现：requestNetwork(TRANSPORT_CELLULAR + INTERNET)，
 * 等回调不轮询 allNetworks（§2.4.7 第 1 条）；客户端 socketFactory 绑蜂窝、
 * DNS 委托 `Network.getAllByName`——域名解析不流回相机热点（第 2 条）。
 */
class ConnectivityManagerCloudNetworkProvider(
    private val connectivity: ConnectivityManager,
) : CloudNetworkProvider {

    private val callbacks =
        mutableMapOf<CloudNetworkProvider.Listener, ConnectivityManager.NetworkCallback>()

    override fun request(listener: CloudNetworkProvider.Listener) {
        synchronized(callbacks) {
            if (callbacks.containsKey(listener)) return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = listener.onAvailable(network)
                override fun onLost(network: Network) = listener.onLost(network)
                override fun onUnavailable() = listener.onUnavailable()
            }
            callbacks[listener] = callback
            connectivity.requestNetwork(REQUEST, callback)
        }
    }

    override fun unregister(listener: CloudNetworkProvider.Listener) {
        val callback = synchronized(callbacks) { callbacks.remove(listener) } ?: return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    override fun boundClient(handle: Any): OkHttpClient {
        val network = handle as Network
        return OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            // DNS 委托蜂窝网络解析，域名查询不流回相机热点（§2.4.7 第 2 条）
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    network.getAllByName(hostname).toList()
            })
            .build()
    }

    private companion object {
        val REQUEST: NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
    }
}

/**
 * 云端蜂窝网络持有者（ARCHITECTURE §2.4.7）。进程默认网络绑定相机 Wi-Fi
 * 期间，云端 LLM 请求显式走这里持有的蜂窝网络：
 *
 * - [acquire]：用户启用云端（凭据已配置且管线启动）时请求蜂窝；幂等
 * - 客户端**按有效网络复用**：onAvailable 构建绑定客户端；网络切换/丢失 →
 *   cancelAll 在途请求并废弃旧客户端（第 3 条），不每句新建连接池
 * - [clientOrNull] 为 null = 蜂窝未就绪：调用方回落默认网络（无相机会话时）
 *   或让请求失败降级为 UNAVAILABLE——双网并发不保证所有设备可用（第 5 条），
 *   失败时相机连接不受影响
 * - [release]：退出云端或结束会话时注销请求（第 6 条）
 *
 * 从不改绑整进程网络——相机连接由 P2 的 bindProcessToNetwork 持有，
 * 这里只叠加第二条网络。
 */
class CloudNetworkManager(
    private val provider: CloudNetworkProvider,
) : CloudNetworkProvider.Listener {

    private val lock = Any()
    private var requested = false
    private var client: OkHttpClient? = null
    private var activeHandle: Any? = null

    /** 幂等：已请求/已激活时不重复发起 */
    fun acquire() {
        synchronized(lock) {
            if (requested) return
            requested = true
        }
        provider.request(this)
    }

    fun release() {
        synchronized(lock) {
            if (!requested) return
            requested = false
            discardClientLocked()
            activeHandle = null
        }
        provider.unregister(this)
    }

    /** 绑定蜂窝的复用客户端；未就绪为 null（调用方降级，不阻塞不轮询） */
    fun clientOrNull(): OkHttpClient? = synchronized(lock) { client }

    val isReady: Boolean get() = synchronized(lock) { client != null }

    override fun onAvailable(handle: Any) {
        synchronized(lock) {
            if (!requested) return                       // release 后的迟到回调：忽略
            if (activeHandle == handle && client != null) return
            discardClientLocked()                        // 网络切换：废弃旧客户端
            activeHandle = handle
            client = provider.boundClient(handle)
        }
    }

    override fun onLost(handle: Any) {
        synchronized(lock) {
            if (activeHandle != handle) return
            discardClientLocked()
            activeHandle = null
            // 请求保持注册：系统可能在蜂窝恢复后再次回调 onAvailable
        }
    }

    override fun onUnavailable() {
        synchronized(lock) {
            // 请求本身失败（无蜂窝/系统拒绝）：复位状态，下次启用云端时再试
            requested = false
            discardClientLocked()
            activeHandle = null
        }
    }

    /** 废弃客户端：取消在途请求 + 关闭调度线程 + 清连接池（第 3 条） */
    private fun discardClientLocked() {
        client?.let { c ->
            runCatching {
                c.dispatcher.cancelAll()
                c.dispatcher.executorService.shutdown()
                c.connectionPool.evictAll()
            }
        }
        client = null
    }
}
