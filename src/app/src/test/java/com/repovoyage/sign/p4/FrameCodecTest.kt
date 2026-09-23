package com.repovoyage.sign.p4

import com.repovoyage.sign.usb.FrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * P4 线协议分帧验收（API.md §9.2 / ARCHITECTURE.md §2.8.2）：
 * uint32 大端 headerLen + UTF-8 JSON header + payload；接收端循环读满；
 * 长度校验失败即断开；上限：JSON header ≤ 16 KiB、图像 payload ≤ 32 MiB、
 * 控制消息 payload ≤ 64 KiB。
 */
class FrameCodecTest {

    /** 编码一条消息（header + payload） */
    private fun encode(header: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val json = header.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(
            (json.size ushr 24).toByte(), (json.size ushr 16).toByte(),
            (json.size ushr 8).toByte(), json.size.toByte(),
        ))
        out.write(json)
        out.write(payload)
        return out.toByteArray()
    }

    private fun decode(vararg messages: ByteArray): FrameCodec.Message? {
        val input = ByteArrayInputStream(messages.reduce { a, b -> a + b })
        // JVM 无 SystemClock：注入恒 0 时钟（deadline 检查恒不触发）
        return FrameCodec(input, monoMs = { 0L }).readMessage()
    }

    @Test
    fun `正常读满 header 与 payload`() {
        val header = """{"type":"HEARTBEAT","t":123,"payloadLen":3}"""
        val msg = decode(encode(header, byteArrayOf(1, 2, 3)))
        assertNotNull(msg)
        assertEquals("HEARTBEAT", msg!!.header.getString("type"))
        assertEquals(123L, msg.header.getLong("t"))
        assertArrayEquals(byteArrayOf(1, 2, 3), msg.payload)
    }

    @Test
    fun `零 payload 控制消息`() {
        val msg = decode(encode("""{"type":"AUTH_REQ","token":"x"}"""))
        assertNotNull(msg)
        assertEquals(0, msg!!.payload.size)
    }

    @Test
    fun `分片到达也能读满`() {
        val bytes = encode("""{"type":"FRAME","payloadLen":4}""", byteArrayOf(9, 8, 7, 6))
        // 拆三片喂入，循环读满语义
        val msg = decode(
            bytes.copyOfRange(0, 3),
            bytes.copyOfRange(3, 10),
            bytes.copyOfRange(10, bytes.size),
        )
        assertNotNull(msg)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), msg!!.payload)
    }

    @Test
    fun `流结束返回 null`() {
        assertNull(decode(ByteArray(0)))
    }

    @Test
    fun `header 声明长度与实际不符即断开`() {
        // 声明 payloadLen=10 但只带 3 字节 → 流耗尽仍不满 → 断开（null）
        assertNull(decode(encode("""{"type":"FRAME","payloadLen":10}""", byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `JSON 非法即断开`() {
        val bad = "{not-json".toByteArray(Charsets.UTF_8)
        val bytes = byteArrayOf(0, 0, 0, bad.size.toByte()) + bad
        assertNull(decode(bytes))
    }

    @Test
    fun `header 超过上限即断开`() {
        val huge = """{"type":"X","pad":"${"a".repeat(17 * 1024)}"}"""
        assertNull(decode(encode(huge)))
    }

    @Test
    fun `payload 超过上限即断开`() {
        // 控制消息 payloadLen 声明 64KiB+1，超上限
        assertNull(decode(encode("""{"type":"AUTH_REQ","payloadLen":65537}""")))
    }

    @Test
    fun `写方向编码可被读回`() {
        val header = org.json.JSONObject().put("type", "FRAME").put("payloadLen", 2)
        val bytes = FrameCodec.encode(header, byteArrayOf(0x11, 0x22))
        val msg = decode(bytes)
        assertNotNull(msg)
        assertEquals("FRAME", msg!!.header.getString("type"))
        assertArrayEquals(byteArrayOf(0x11, 0x22), msg.payload)
    }
}
