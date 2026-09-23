package com.repovoyage.sign.p7

import com.repovoyage.sign.net.CloudNetworkManager
import com.repovoyage.sign.net.CloudNetworkProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2.4.7 蜂窝网络持有者状态机验收：请求幂等、客户端按有效网络复用、
 * 网络切换/丢失废弃客户端、release 注销且迟到回调忽略、失败可重试。
 * 句柄为普通对象（抽象层设计使状态机脱离 android.jar 可测）。
 */
class CloudNetworkManagerTest {

    private class FakeProvider : CloudNetworkProvider {
        var requestCount = 0
        var unregisterCount = 0
        var clientCount = 0
        var listener: CloudNetworkProvider.Listener? = null

        override fun request(listener: CloudNetworkProvider.Listener) {
            requestCount++
            this.listener = listener
        }

        override fun unregister(listener: CloudNetworkProvider.Listener) {
            unregisterCount++
        }

        override fun boundClient(handle: Any): OkHttpClient {
            clientCount++
            return OkHttpClient()
        }
    }

    private val provider = FakeProvider()
    private val manager = CloudNetworkManager(provider)
    private val listener get() = provider.listener!!

    @Test
    fun `acquire 发起请求且回调可用后客户端就绪`() {
        manager.acquire()
        assertEquals(1, provider.requestCount)
        assertNull(manager.clientOrNull())          // 回调前未就绪，不轮询
        listener.onAvailable("net-1")
        assertNotNull(manager.clientOrNull())
        assertTrue(manager.isReady)
        assertEquals(1, provider.clientCount)
    }

    @Test
    fun `acquire 幂等不重复发起请求`() {
        manager.acquire()
        manager.acquire()
        listener.onAvailable("net-1")
        manager.acquire()
        assertEquals(1, provider.requestCount)
        assertEquals(1, provider.clientCount)       // 同网络重复回调也不重建
        listener.onAvailable("net-1")
        assertEquals(1, provider.clientCount)
    }

    @Test
    fun `网络切换废弃旧客户端并复用新客户端`() {
        manager.acquire()
        listener.onAvailable("net-1")
        val first = manager.clientOrNull()
        listener.onAvailable("net-2")
        val second = manager.clientOrNull()
        assertNotNull(second)
        assertNotSame(first, second)
        assertEquals(2, provider.clientCount)       // 按有效网络复用，不每句新建
        assertEquals(1, provider.requestCount)      // 切换不重新发起请求
    }

    @Test
    fun `网络丢失废弃客户端但保持注册可恢复`() {
        manager.acquire()
        listener.onAvailable("net-1")
        listener.onLost("net-1")
        assertNull(manager.clientOrNull())
        assertEquals(1, provider.requestCount)      // 未注销：蜂窝恢复可再回调
        listener.onAvailable("net-1")
        assertNotNull(manager.clientOrNull())
    }

    @Test
    fun `非当前网络的丢失回调不影响客户端`() {
        manager.acquire()
        listener.onAvailable("net-2")
        listener.onLost("net-1")                    // 旧网络的迟到 onLost
        assertNotNull(manager.clientOrNull())
    }

    @Test
    fun `release 注销请求且迟到回调被忽略`() {
        manager.acquire()
        listener.onAvailable("net-1")
        manager.release()
        assertEquals(1, provider.unregisterCount)
        assertNull(manager.clientOrNull())
        listener.onAvailable("net-2")               // 注销后的迟到回调
        assertNull(manager.clientOrNull())
        assertEquals(1, provider.clientCount)
    }

    @Test
    fun `请求失败复位后下次可重试`() {
        manager.acquire()
        listener.onUnavailable()
        assertNull(manager.clientOrNull())
        manager.acquire()                           // 下次启用云端时重试
        assertEquals(2, provider.requestCount)
    }
}
