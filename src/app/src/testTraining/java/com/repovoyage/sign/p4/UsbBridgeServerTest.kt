package com.repovoyage.sign.p4

import com.repovoyage.sign.usb.FrameCodec
import com.repovoyage.sign.usb.FrameSendPool
import com.repovoyage.sign.usb.UsbBridgeServer
import com.repovoyage.sign.video.FramePlane
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * P4 USB 桥接线验收（API.md §9 / ARCHITECTURE.md §2.8.2）：真实 TCP 回环
 * 端到端——AUTH 握手、SESSION_CONFIG/CONFIG_ACK、FRAME 透传、背压中断 →
 * GAP_EVENT + END INCOMPLETE、用户停止 → END COMPLETE、BAD_TOKEN 断开后
 * 恢复可连接。token 不入日志。
 */
class UsbBridgeServerTest {

    /** JVM 单测无 SystemClock，用 nanoTime 充当 mono 时钟 */
    private val clock = { System.nanoTime() / 1_000_000 }

    private val config = JSONObject()
        .put("pixelFormat", "I420")
        .put("width", 4)
        .put("height", 4)
        .put("captureFps", 30)
        .put("preprocessVersion", "i420-1")

    private fun pool(maxFrames: Int = 60, maxBytes: Long = 1024 * 1024) =
        FrameSendPool(maxFrames, maxBytes, monoMs = clock)

    private fun server(pool: FrameSendPool) =
        UsbBridgeServer(token = "pair-token", sessionConfig = config, pool = pool, port = 0, monoMs = clock)

    /** 4×4 测试图：Y 1..16，U 17..20，V 21..24 */
    private val y = ByteArray(16) { (it + 1).toByte() }
    private val u = ByteArray(4) { (17 + it).toByte() }
    private val v = ByteArray(4) { (21 + it).toByte() }

    private fun planes() = listOf(
        FramePlane(ByteBuffer.wrap(y), 4, 1),
        FramePlane(ByteBuffer.wrap(u), 2, 1),
        FramePlane(ByteBuffer.wrap(v), 2, 1),
    )

    /** 连接并完成 AUTH/SESSION_CONFIG/CONFIG_ACK 握手，返回客户端 */
    private fun handshaken(server: UsbBridgeServer): Pair<Socket, FrameCodec> {
        val client = Socket("127.0.0.1", server.boundPort)
        client.soTimeout = 5_000
        val codec = FrameCodec(client.getInputStream(), clock)
        client.getOutputStream().apply {
            write(FrameCodec.encode(
                JSONObject().put("type", "AUTH_REQ").put("token", "pair-token").put("clientInfo", "pc-test"),
            ))
            flush()
        }
        val auth = codec.readMessage()
        assertTrue(auth != null)
        assertEquals("AUTH_RESULT", auth!!.header.getString("type"))
        assertTrue(auth.header.getBoolean("ok"))
        assertEquals(server.sessionId, auth.header.getString("sessionId"))
        val cfg = codec.readMessage()
        assertTrue(cfg != null)
        assertEquals("SESSION_CONFIG", cfg!!.header.getString("type"))
        assertEquals(4, cfg.header.getInt("width"))
        client.getOutputStream().apply {
            write(FrameCodec.encode(JSONObject().put("type", "CONFIG_ACK").put("accepted", true)))
            flush()
        }
        return client to codec
    }

    @Test
    fun `AUTH 握手后按协议传出 FRAME 且停止后完整收尾`() {
        val s = server(pool())
        s.start()
        val (client, codec) = handshaken(s)

        assertTrue(s.offer(planes(), width = 4, height = 4,
            cropLeft = 0, cropTop = 0, cropRight = 4, cropBottom = 4,
            ptsUs = 1_000, streamGeneration = 3))
        assertTrue(s.offer(planes(), width = 4, height = 4,
            cropLeft = 0, cropTop = 0, cropRight = 4, cropBottom = 4,
            ptsUs = 2_000, streamGeneration = 3))

        for ((expectIndex, expectPts) in listOf(0L to 1_000L, 1L to 2_000L)) {
            val frame = codec.readMessage()
            assertTrue(frame != null)
            assertEquals("FRAME", frame!!.header.getString("type"))
            assertEquals(1, frame.header.getInt("proto"))
            assertEquals(s.sessionId, frame.header.getString("sessionId"))
            assertEquals(3L, frame.header.getLong("streamGeneration"))
            assertEquals(expectIndex, frame.header.getLong("frameIndex"))
            assertEquals(expectPts, frame.header.getLong("ptsUs"))
            assertEquals("us", frame.header.getString("ptsUnit"))
            assertEquals(4, frame.header.getInt("width"))
            assertEquals(4, frame.header.getInt("height"))
            assertEquals("I420", frame.header.getString("pixelFormat"))
            assertEquals(24L, frame.header.getLong("payloadLen"))
            assertArrayEquals(y + u + v, frame.payload)
        }

        s.stop()
        val end = codec.readMessage()
        assertTrue(end != null)
        assertEquals("END", end!!.header.getString("type"))
        assertEquals("COMPLETE", end.header.getString("status"))
        assertEquals(1L, end.header.getLong("lastFrameIndex"))
        assertEquals(1_000L, end.header.getLong("validStartPtsUs"))
        assertEquals(2_000L, end.header.getLong("validEndPtsUs"))
        // 收尾后连接关闭
        assertNull(codec.readMessage())
        client.close()
    }

    @Test
    fun `背压超限中断采集段并发 GAP_EVENT 与 END INCOMPLETE`() {
        val s = server(pool(maxBytes = 8))   // 8 字节预算：24 字节帧必超限
        s.start()
        val (client, codec) = handshaken(s)

        // 帧被拒收（不静默丢帧），采集段中断
        assertFalse(s.offer(planes(), width = 4, height = 4,
            cropLeft = 0, cropTop = 0, cropRight = 4, cropBottom = 4,
            ptsUs = 1_000, streamGeneration = 1))

        val gap = codec.readMessage()
        assertTrue(gap != null)
        assertEquals("GAP_EVENT", gap!!.header.getString("type"))
        assertEquals("OVERLOAD", gap.header.getString("reason"))
        assertEquals(-1L, gap.header.getLong("lastFrameIndex"))
        val end = codec.readMessage()
        assertTrue(end != null)
        assertEquals("END", end!!.header.getString("type"))
        assertEquals("INCOMPLETE", end.header.getString("status"))
        assertEquals("OVERLOAD", end.header.getString("reason"))
        assertNull(codec.readMessage())
        client.close()
        s.stop()
    }

    @Test
    fun `解码链连续性失效中断采集段并发 GAP_EVENT 与 INCOMPLETE`() {
        val s = server(pool())
        s.start()
        val (client, codec) = handshaken(s)

        // 相机断电重连/解码重建 → sink onGap → reportGap：中断段，PC 重连续段
        s.reportGap("RECONNECT")

        val gap = codec.readMessage()
        assertTrue(gap != null)
        assertEquals("GAP_EVENT", gap!!.header.getString("type"))
        assertEquals("RECONNECT", gap.header.getString("reason"))
        val end = codec.readMessage()
        assertTrue(end != null)
        assertEquals("INCOMPLETE", end!!.header.getString("status"))
        assertEquals("RECONNECT", end.header.getString("reason"))
        assertNull(codec.readMessage())
        client.close()
        s.stop()
    }

    @Test
    fun `token 错误回 BAD_TOKEN 并关闭且恢复可连接`() {
        val s = server(pool())
        s.start()

        val c1 = Socket("127.0.0.1", s.boundPort)
        c1.soTimeout = 5_000
        val codec1 = FrameCodec(c1.getInputStream(), clock)
        c1.getOutputStream().apply {
            write(FrameCodec.encode(JSONObject().put("type", "AUTH_REQ").put("token", "wrong")))
            flush()
        }
        val result = codec1.readMessage()
        assertTrue(result != null)
        assertEquals("AUTH_RESULT", result!!.header.getString("type"))
        assertFalse(result.header.getBoolean("ok"))
        assertEquals("BAD_TOKEN", result.header.getString("reason"))
        assertNull(codec1.readMessage())   // 断开
        c1.close()

        // 恢复可连接状态：新客户端用正确 token 能完成握手
        val (c2, codec2) = handshaken(s)
        val heart = JSONObject().put("type", "HEARTBEAT").put("t", 1)
        c2.getOutputStream().apply { write(FrameCodec.encode(heart)); flush() }
        // 心跳不回复（回复型心跳要等 2s 空闲；800ms 静默窗口内不应有任何消息）
        c2.soTimeout = 400   // 静默窗口须显著小于手机 2s 心跳间隔，慢机下 800ms 会被心跳追上（偶发红）
        assertNull(try { codec2.readMessage() } catch (_: SocketTimeoutException) { null })
        c2.close()
        s.stop()
    }
}
