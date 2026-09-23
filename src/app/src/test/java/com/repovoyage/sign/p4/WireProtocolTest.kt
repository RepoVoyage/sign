package com.repovoyage.sign.p4

import com.repovoyage.sign.usb.EndStatus
import com.repovoyage.sign.usb.GapReason
import com.repovoyage.sign.usb.PixelFormat
import com.repovoyage.sign.usb.WireCodec
import com.repovoyage.sign.usb.WireException
import com.repovoyage.sign.usb.WireMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.nio.ByteBuffer

/**
 * P4 USB 线协议 v1 验收（API.md §9）。纯编解码/校验逻辑全单测；
 * 认证 5s/读满 2s/心跳 2s/6s 的时钟驱动逻辑与真实吞吐在 P4 集成时补。
 * 开工时移除 @Ignore：先红（TODO）→ 实现 → 绿。
 */
@Ignore("P4：待 WireCodec 实现（线协议 v1 见 API.md §9）")
class WireProtocolTest {

    /** 手工构帧：uint32 大端 headerLen + JSON + payload */
    private fun wire(json: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val h = json.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + h.size + payload.size)
            .putInt(h.size).put(h).put(payload).array()
    }

    /** 2×2 I420 = 6 字节 payload 的合法帧 */
    private fun frame() = WireMessage.Frame(
        sessionId = "s-1", streamGeneration = 7, frameIndex = 1234,
        ptsUs = 180_123_456, width = 2, height = 2,
        pixelFormat = PixelFormat.I420, preprocessVersion = "v1",
        payload = ByteArray(6) { (it + 1).toByte() },
    )

    @Test
    fun `FRAME 编解码 roundtrip 字段与 payload 完整`() {
        val f = WireCodec.decode(WireCodec.encode(frame())) as WireMessage.Frame
        assertEquals("s-1", f.sessionId)
        assertEquals(7L, f.streamGeneration)
        assertEquals(1234L, f.frameIndex)
        assertEquals(180_123_456L, f.ptsUs)
        assertEquals(2, f.width)
        assertEquals(2, f.height)
        assertEquals(PixelFormat.I420, f.pixelFormat)
        assertEquals("v1", f.preprocessVersion)
        assertArrayEquals(ByteArray(6) { (it + 1).toByte() }, f.payload)
    }

    @Test
    fun `控制消息编解码 roundtrip`() {
        val msgs = listOf(
            WireMessage.AuthReq("token-x", "pc-training/0.1"),
            WireMessage.AuthResult(ok = true, sessionId = "s-1", reason = null),
            WireMessage.AuthResult(ok = false, sessionId = null, reason = "BAD_TOKEN"),
            WireMessage.SessionConfig("spec-1", PixelFormat.I420, 1920, 1080, 30, 2000, 32_000_000L, "pv-1"),
            WireMessage.ConfigAck(accepted = true),
            WireMessage.ConfigAck(accepted = false),
            WireMessage.GapEvent("s-1", GapReason.RECONNECT, 99, 1_000L, 2_000L),
            WireMessage.End("s-1", EndStatus.INCOMPLETE, "LOST_FRAMES", 99, 0L, 2_000L),
            WireMessage.Heartbeat(456),
        )
        for (m in msgs) {
            assertEquals(m, WireCodec.decode(WireCodec.encode(m)))
        }
    }

    @Test
    fun `协议版本不为 1 拒绝`() {
        val bad = wire("""{"type":"HEARTBEAT","proto":2,"t":1}""")
        assertThrows(WireException::class.java) { WireCodec.decode(bad) }
    }

    @Test
    fun `未知消息类型拒绝`() {
        val bad = wire("""{"type":"NOPE","proto":1}""")
        assertThrows(WireException::class.java) { WireCodec.decode(bad) }
    }

    @Test
    fun `缺失 proto 字段拒绝`() {
        val bad = wire("""{"type":"HEARTBEAT","t":1}""")
        assertThrows(WireException::class.java) { WireCodec.decode(bad) }
    }

    @Test
    fun `header 超过 16KiB 拒绝`() {
        val big = ByteArray(17 * 1024)
        val out = ByteBuffer.allocate(4 + big.size).putInt(big.size).put(big).array()
        assertThrows(WireException::class.java) { WireCodec.decode(out) }
    }

    @Test
    fun `headerLen 与实际长度不符拒绝`() {
        val h = "{}".toByteArray()
        val out = ByteBuffer.allocate(4 + h.size).putInt(h.size + 5).put(h).array()
        assertThrows(WireException::class.java) { WireCodec.decode(out) }
    }

    @Test
    fun `图像 payload 超过 32MiB 时编码即拒绝`() {
        val oversized = frame().copy(payload = ByteArray(32 * 1024 * 1024 + 1))
        assertThrows(WireException::class.java) { WireCodec.encode(oversized) }
    }

    @Test
    fun `FRAME 声明长度与实际 payload 不符拒绝`() {
        val json = """
            {"type":"FRAME","proto":1,"sessionId":"s-1","streamGeneration":1,"frameIndex":1,
             "ptsUs":1,"ptsUnit":"us","width":2,"height":2,"pixelFormat":"I420",
             "preprocessVersion":"v","payloadLen":5}
        """.trimIndent().replace("\n", " ")
        assertThrows(WireException::class.java) { WireCodec.decode(wire(json, ByteArray(6))) }
    }

    @Test
    fun `FRAME payload 与格式字节数计算不符拒绝`() {
        // 2×2 I420 应为 6 字节，声明与实际一致地给了 5 → 仍拒绝
        val json = """
            {"type":"FRAME","proto":1,"sessionId":"s-1","streamGeneration":1,"frameIndex":1,
             "ptsUs":1,"ptsUnit":"us","width":2,"height":2,"pixelFormat":"I420",
             "preprocessVersion":"v","payloadLen":5}
        """.trimIndent().replace("\n", " ")
        assertThrows(WireException::class.java) { WireCodec.decode(wire(json, ByteArray(5))) }
    }

    @Test
    fun `心跳无 sessionId 可解码`() {
        assertEquals(
            WireMessage.Heartbeat(789),
            WireCodec.decode(wire("""{"type":"HEARTBEAT","proto":1,"t":789}""")),
        )
    }

    @Test
    fun `RGB888 帧同样通过尺寸校验`() {
        val f = WireMessage.Frame(
            sessionId = "s-1", streamGeneration = 1, frameIndex = 2,
            ptsUs = 9_000, width = 2, height = 2,
            pixelFormat = PixelFormat.RGB888, preprocessVersion = "v",
            payload = ByteArray(12),                       // 2×2×3
        )
        assertEquals(f.frameIndex, (WireCodec.decode(WireCodec.encode(f)) as WireMessage.Frame).frameIndex)
        assertTrue(f.pixelFormat.payloadBytes(2, 2) == 12L)
    }

    @Test
    @Ignore("P4：认证 5s/读满 2s/心跳 2s+6s 为时钟驱动逻辑，集成时以 fake clock 实现")
    fun `认证与读写时间约束在集成层验证`() {
        throw NotImplementedError("P4：AUTH 5s 超时、单条消息 2s 读满、空闲 2s 心跳、6s 无消息断开")
    }
}
