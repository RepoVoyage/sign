package com.repovoyage.sign.usb

/** 像素格式（API.md §9.3）；紧凑布局，无行距填充 */
enum class PixelFormat(val wireName: String) {
    I420("I420"),
    RGB888("RGB888");

    /** 紧凑布局字节数（PC 侧校验口径一致） */
    fun payloadBytes(width: Int, height: Int): Long = when (this) {
        I420 -> width.toLong() * height * 3 / 2
        RGB888 -> width.toLong() * height * 3
    }
}

enum class GapReason { LOST_FRAMES, DECODE_RESET, RECONNECT, OVERLOAD }
enum class EndStatus { COMPLETE, INCOMPLETE }

/** 线协议 v1 消息（API.md §9.3）；sessionId 除 AUTH 与 HEARTBEAT 外必填 */
sealed interface WireMessage {
    data class AuthReq(val token: String, val clientInfo: String) : WireMessage
    data class AuthResult(val ok: Boolean, val sessionId: String?, val reason: String?) : WireMessage
    data class SessionConfig(
        val captureSpecVersion: String,
        val pixelFormat: PixelFormat,
        val width: Int,
        val height: Int,
        val captureFps: Int,
        val bufferTargetMs: Int,
        val maxPayloadBytes: Long,
        val preprocessVersion: String,
    ) : WireMessage
    data class ConfigAck(val accepted: Boolean) : WireMessage
    data class Frame(
        val sessionId: String,
        val streamGeneration: Long,
        val frameIndex: Long,
        val ptsUs: Long,
        val width: Int,
        val height: Int,
        val pixelFormat: PixelFormat,
        val preprocessVersion: String,
        val payload: ByteArray,
    ) : WireMessage
    data class GapEvent(
        val sessionId: String,
        val reason: GapReason,
        val lastFrameIndex: Long,
        val startPtsUs: Long,
        val endPtsUs: Long,
    ) : WireMessage
    data class End(
        val sessionId: String,
        val status: EndStatus,
        val reason: String?,
        val lastFrameIndex: Long,
        val validStartPtsUs: Long,
        val validEndPtsUs: Long,
    ) : WireMessage
    data class Heartbeat(val tMonoMs: Long) : WireMessage
}

class WireException(message: String) : RuntimeException(message)

/**
 * 线协议 v1 编解码（API.md §9.2：uint32 大端 headerLen + UTF-8 JSON header + payload）。
 * 长度校验失败、协议版本不符、未知类型、FRAME 声明长度与尺寸计算不符 → 抛 [WireException]。
 */
object WireCodec {
    const val PROTO_VERSION = 1
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_IMAGE_PAYLOAD_BYTES = 32 * 1024 * 1024

    fun encode(msg: WireMessage): ByteArray = TODO("P4")

    fun decode(bytes: ByteArray): WireMessage = TODO("P4")
}
