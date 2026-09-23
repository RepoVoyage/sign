package com.repovoyage.sign.usb

import org.json.JSONObject

/**
 * USB 线协议会话状态机（API.md §9.1/§9.3 / ARCHITECTURE.md §2.8.2）：
 * 首条消息必须 AUTH_REQ，连接接受后 5 秒内完成认证；token 校验通过回 AUTH_RESULT
 * 并发 SESSION_CONFIG（内容由接线层按采集规格注入），等 CONFIG_ACK；accepted 进
 * STREAMING，false 或空闲超时即关闭。STREAMING 阶段空闲每 2 秒发 HEARTBEAT，
 * 6 秒未收到任何完整消息即断开。错序/非预期消息一律断开（FRAME/END 均为
 * 手机→PC 方向，PC 发来即错序）。
 *
 * 纯逻辑（时钟由调用方注入），socket 读写由接线层负责；close 后不再处理消息。
 * token 为训练版显示的临时配对令牌，不写日志（由调用方保证）。
 */
class BridgeSession(
    private val expectedToken: String,
    private val sessionId: String,
    private val sessionConfig: JSONObject,
    private val startedAtMonoMs: Long = 0,
) {

    enum class Phase { AWAIT_AUTH, AWAIT_CONFIG_ACK, STREAMING, CLOSED }

    data class Outcome(val sends: List<JSONObject>, val close: Boolean)

    var phase = Phase.AWAIT_AUTH
        private set

    /** 最近收到完整消息的时刻（mono ms），初始为连接接受时刻 */
    private var lastReceivedAt = startedAtMonoMs
    private var lastSentAt = startedAtMonoMs
    private var closed = false

    /** 收到一条完整消息；由接线层读取线程调用（onTick 可并发来自驱动线程，故加锁） */
    @Synchronized
    fun onMessage(header: JSONObject, now: Long): Outcome {
        if (closed) return Outcome(emptyList(), true)
        lastReceivedAt = now
        // 收到任何消息即视为通道活跃：心跳发送计时随之刷新（有流量无需补发，
        // 慢握手后客户端首条消息不应触发立即心跳）
        lastSentAt = now
        return when (phase) {
            Phase.AWAIT_AUTH -> onAuthReq(header)
            Phase.AWAIT_CONFIG_ACK -> when (header.optString("type")) {
                "CONFIG_ACK" -> if (header.optBoolean("accepted", false)) {
                    phase = Phase.STREAMING
                    Outcome(emptyList(), false)
                } else {
                    close()
                }
                else -> close()
            }
            Phase.STREAMING -> when (header.optString("type")) {
                "HEARTBEAT" -> Outcome(emptyList(), false)
                else -> close()
            }
            Phase.CLOSED -> Outcome(emptyList(), true)
        }
    }

    /** 周期驱动：心跳发送与超时判定（认证窗口/空闲超时）；close 后恒返回关闭 */
    @Synchronized
    fun onTick(now: Long): Outcome {
        if (closed) return Outcome(emptyList(), true)
        val authTimeout = phase == Phase.AWAIT_AUTH && now - startedAtMonoMs >= AUTH_WINDOW_MS
        val idleTimeout = now - lastReceivedAt >= IDLE_TIMEOUT_MS
        if (authTimeout || idleTimeout) return close()
        if (phase == Phase.STREAMING && now - lastSentAt >= HEARTBEAT_INTERVAL_MS) {
            lastSentAt = now
            return Outcome(listOf(heartbeat(now)), false)
        }
        return Outcome(emptyList(), false)
    }

    private fun onAuthReq(header: JSONObject): Outcome {
        if (header.optString("type") != "AUTH_REQ") return close()
        if (header.optString("token") != expectedToken) {
            closed = true
            phase = Phase.CLOSED
            return Outcome(
                listOf(
                    JSONObject().put("type", "AUTH_RESULT")
                        .put("proto", 1)
                        .put("ok", false)
                        .put("reason", "BAD_TOKEN"),
                ),
                true,
            )
        }
        phase = Phase.AWAIT_CONFIG_ACK
        return Outcome(
            listOf(
                JSONObject().put("type", "AUTH_RESULT")
                    .put("proto", 1)
                    .put("ok", true)
                    .put("sessionId", sessionId),
                JSONObject(sessionConfig.toString())
                    .put("type", "SESSION_CONFIG")
                    .put("proto", 1)
                    .put("sessionId", sessionId),
            ),
            false,
        )
    }

    private fun close(): Outcome {
        closed = true
        phase = Phase.CLOSED
        return Outcome(emptyList(), true)
    }

    private fun heartbeat(now: Long) = JSONObject()
        .put("type", "HEARTBEAT")
        .put("proto", 1)
        .put("sessionId", sessionId)
        .put("t", now)

    private companion object {
        const val AUTH_WINDOW_MS = 5_000L
        const val HEARTBEAT_INTERVAL_MS = 2_000L
        const val IDLE_TIMEOUT_MS = 6_000L
    }
}
