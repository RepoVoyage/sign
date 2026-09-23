package com.repovoyage.sign.camera

/**
 * 重连退避策略（API.md §1.2 初始值）：
 * 1、2、4、8、16 秒，最多 5 次；成功出图后清零。
 */
class ReconnectPolicy {

    private var attempt = 0

    /** 每次断线/重试失败时调用；返回下次重试延迟，超过 5 次返回 null = 放弃 */
    fun onDisconnected(): Long? {
        if (attempt >= MAX_ATTEMPTS) return null
        val delayMs = BACKOFF_DELAYS_MS[attempt]
        attempt++
        return delayMs
    }

    /** 成功出图后调用，计数清零 */
    fun onStreamRecovered() {
        attempt = 0
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        val BACKOFF_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)
    }
}
