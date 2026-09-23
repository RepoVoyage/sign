package com.repovoyage.sign.p4

import com.repovoyage.sign.usb.BridgeSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 线协议状态机验收（API.md §9.1/§9.3 / ARCHITECTURE.md §2.8.2）：
 * 首条消息必须 AUTH_REQ；认证 5 秒窗口；token 校验；SESSION_CONFIG→CONFIG_ACK
 * 协商；STREAMING 阶段心跳 2s/超时 6s；错序或非预期消息即断开。
 */
class BridgeSessionTest {

    private val session = BridgeSession(
        expectedToken = "pair-token",
        sessionId = "sid-1",
        sessionConfig = JSONObject()
            .put("pixelFormat", "I420")
            .put("width", 640)
            .put("height", 384)
            .put("captureFps", 30),
    )

    private fun json(type: String, vararg pairs: Pair<String, Any>) =
        JSONObject().put("type", type).apply { pairs.forEach { (k, v) -> put(k, v) } }

    private fun authReq(token: String = "pair-token") = json("AUTH_REQ", "token" to token)

    /** 走完握手进 STREAMING */
    private fun handshaken(): BridgeSession {
        session.onMessage(authReq(), now = 0)
        session.onMessage(json("CONFIG_ACK", "accepted" to true), now = 1)
        return session
    }

    @Test
    fun `首条消息非 AUTH_REQ 即断开`() {
        val out = session.onMessage(json("HEARTBEAT", "t" to 0), now = 0)
        assertTrue(out.close)
        assertTrue(out.sends.isEmpty())
    }

    @Test
    fun `token 正确回 AUTH_RESULT 与 SESSION_CONFIG`() {
        val out = session.onMessage(authReq(), now = 0)
        assertFalse(out.close)
        assertEquals(2, out.sends.size)
        val authResult = out.sends[0]
        assertEquals("AUTH_RESULT", authResult.getString("type"))
        assertEquals(1, authResult.getInt("proto"))
        assertTrue(authResult.getBoolean("ok"))
        assertEquals("sid-1", authResult.getString("sessionId"))
        assertEquals("SESSION_CONFIG", out.sends[1].getString("type"))
        assertEquals(BridgeSession.Phase.AWAIT_CONFIG_ACK, session.phase)
    }

    @Test
    fun `token 错误回 BAD_TOKEN 并断开`() {
        val out = session.onMessage(authReq(token = "wrong"), now = 0)
        assertEquals("AUTH_RESULT", out.sends.single().getString("type"))
        assertEquals(1, out.sends.single().getInt("proto"))
        assertFalse(out.sends.single().getBoolean("ok"))
        assertEquals("BAD_TOKEN", out.sends.single().getString("reason"))
        assertTrue(out.close)
    }

    @Test
    fun `连接后 5 秒无 AUTH_REQ 即断开`() {
        // 认证窗口只覆盖 AUTH 阶段：PC 迟迟不发 AUTH_REQ
        val out = session.onTick(now = 5_001)
        assertTrue(out.close)
    }

    @Test
    fun `认证已完成则等 CONFIG_ACK 走 6 秒空闲超时`() {
        session.onMessage(authReq(), now = 0)
        assertFalse(session.onTick(now = 5_001).close)      // 认证窗口已过但认证完成
        assertTrue(session.onTick(now = 6_001).close)       // idle 超时
    }

    @Test
    fun `CONFIG_ACK accepted 进 STREAMING`() {
        session.onMessage(authReq(), now = 0)
        val out = session.onMessage(json("CONFIG_ACK", "accepted" to true), now = 1)
        assertFalse(out.close)
        assertEquals(BridgeSession.Phase.STREAMING, session.phase)
    }

    @Test
    fun `CONFIG_ACK rejected 即断开`() {
        session.onMessage(authReq(), now = 0)
        val out = session.onMessage(json("CONFIG_ACK", "accepted" to false), now = 1)
        assertTrue(out.close)
    }

    @Test
    fun `STREAMING 中 6 秒无消息即断开`() {
        handshaken()
        assertFalse(session.onTick(now = 6_000).close)     // 恰好 6s 未超
        assertTrue(session.onTick(now = 6_001).close)
    }

    @Test
    fun `心跳刷新活跃时间`() {
        handshaken()
        session.onMessage(json("HEARTBEAT", "t" to 3_000), now = 3_000)
        assertFalse(session.onTick(now = 8_999).close)     // 距最近消息 < 6s
        assertTrue(session.onTick(now = 9_000).close)      // 恰 6s 无消息即断开
    }

    @Test
    fun `空闲 2 秒主动发心跳`() {
        handshaken()
        val out = session.onTick(now = 2_001)     // 距最后流量（CONFIG_ACK, now=1）满 2s
        assertFalse(out.close)
        assertEquals("HEARTBEAT", out.sends.single().getString("type"))
        assertTrue(out.sends.single().has("t"))
    }

    @Test
    fun `客户端消息刷新心跳计时避免立即补发`() {
        handshaken()
        // 慢握手等价场景：距会话起点远超 2s，但客户端消息刚到 → 通道活跃，不补发
        session.onMessage(json("HEARTBEAT", "t" to 10_000), now = 10_000)
        val out = session.onTick(now = 10_001)
        assertFalse(out.close)
        assertTrue(out.sends.isEmpty())
    }

    @Test
    fun `STREAMING 收到非预期消息即断开`() {
        handshaken()
        // FRAME 是手机→PC 方向，PC 发 FRAME 属错序
        assertTrue(session.onMessage(json("FRAME"), now = 1).close)
    }

    @Test
    fun `已关闭会话不再处理消息`() {
        session.onMessage(json("HEARTBEAT"), now = 0)      // 非法首条 → 关闭
        val out = session.onMessage(authReq(), now = 1)
        assertTrue(out.close)
        assertTrue(out.sends.isEmpty())
    }
}
