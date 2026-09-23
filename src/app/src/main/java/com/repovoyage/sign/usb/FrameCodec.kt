package com.repovoyage.sign.usb

import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * USB 线协议分帧 codec（API.md §9.2 / ARCHITECTURE.md §2.8.2）：
 * `uint32 大端 headerLen | UTF-8 JSON header | payload`，接收端循环读满。
 *
 * 截止时间从**首字节**起算：收到首字节后 readTimeoutMs 内须读满整条消息（单一
 * 截止时间，不按小片续期）。首字节前无限期阻塞——配合调用方设置的 soTimeout，
 * 无任何字节到达时 SocketTimeoutException 外抛，由调用方决定重试或断开；消息
 * 读到一半的超时唤醒在内部消化，仍受截止时间约束。
 *
 * 校验失败（长度不符/JSON 非法/超上限/EOF 不满/超截止时间）一律返回 null，
 * 由调用方断开连接。上限【初始值】：JSON header ≤ 16 KiB；图像 payload（FRAME）
 * ≤ 32 MiB；控制消息 payload ≤ 64 KiB。
 */
class FrameCodec(
    private val input: InputStream,
    private val monoMs: () -> Long = SystemClock::elapsedRealtime,
) {

    data class Message(val header: JSONObject, val payload: ByteArray)

    /**
     * 读一条完整消息；流正常结束/校验失败/超截止时间返回 null。
     * @param readTimeoutMs 收到首字节后读满整条消息的时限
     */
    fun readMessage(readTimeoutMs: Long = 2_000): Message? {
        val prefix = ByteArray(4)
        var filled = 0
        var startAt = 0L
        while (filled < 4) {
            if (filled > 0 && monoMs() - startAt > readTimeoutMs) return null
            val n = try {
                input.read(prefix, filled, 4 - filled)
            } catch (e: SocketTimeoutException) {
                if (filled == 0) throw e    // 尚无任何字节：空闲唤醒，交调用方处置
                continue                     // 已读部分字节：回到截止时间判定
            }
            if (n < 0) return null
            if (filled == 0 && n > 0) startAt = monoMs()
            filled += n
        }
        val deadlineAt = startAt + readTimeoutMs
        val headerLen = prefix.beUint32()
        if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES) return null
        val header = readFull(headerLen, deadlineAt)
            ?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
            ?: return null
        val payloadLen = header.optLong("payloadLen", 0L)
        if (payloadLen < 0 || payloadLen > payloadCapFor(header)) return null
        val payload = readFull(payloadLen.toInt(), deadlineAt) ?: return null
        return Message(header, payload)
    }

    /** 循环读满 len 字节（首字节已到达，deadlineAt 为整条消息的绝对截止时刻） */
    private fun readFull(len: Int, deadlineAt: Long): ByteArray? {
        if (len == 0) return ByteArray(0)
        val out = ByteArray(len)
        var filled = 0
        while (filled < len) {
            if (monoMs() > deadlineAt) return null
            val n = try {
                input.read(out, filled, len - filled)
            } catch (e: SocketTimeoutException) {
                continue    // soTimeout 唤醒：回到截止时间判定
            }
            if (n < 0) return null
            filled += n
        }
        return out
    }

    private fun ByteArray.beUint32(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)

    companion object {
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_IMAGE_PAYLOAD_BYTES = 32 * 1024 * 1024
        const val MAX_CONTROL_PAYLOAD_BYTES = 64 * 1024

        /** 图像帧按图像 payload 上限，其余消息按控制消息上限 */
        internal fun payloadCapFor(header: JSONObject): Long =
            if (header.optString("type") == "FRAME") MAX_IMAGE_PAYLOAD_BYTES.toLong()
            else MAX_CONTROL_PAYLOAD_BYTES.toLong()

        /** 写方向编码（双向一致分帧） */
        fun encode(header: JSONObject, payload: ByteArray = ByteArray(0)): ByteArray {
            val json = header.toString().toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream(4 + json.size + payload.size)
            out.write(
                byteArrayOf(
                    (json.size ushr 24).toByte(), (json.size ushr 16).toByte(),
                    (json.size ushr 8).toByte(), json.size.toByte(),
                ),
            )
            out.write(json)
            out.write(payload)
            return out.toByteArray()
        }
    }
}
