package com.repovoyage.sign.p7

import com.repovoyage.sign.alert.ConfirmationAlerter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §2.7 震动语义（2026-09-23 用户决定：仅 LLM 低置信触发）：
 * 30s 限频、勿扰/静音门禁、被拦截事件不消耗限频窗口。
 */
class ConfirmationAlerterTest {

    private var now = 0L
    private var vibrateCount = 0
    private var allowed = true

    private fun alerter() = ConfirmationAlerter(
        nowMs = { now },
        canVibrate = { allowed },
        vibrate = { vibrateCount++ },
    )

    @Test
    fun `首次触发即震动`() {
        alerter().onNeedsConfirmation()
        assertEquals(1, vibrateCount)
    }

    @Test
    fun `30 秒内重复触发不再震动`() {
        val a = alerter()
        a.onNeedsConfirmation()
        now += 29_999
        a.onNeedsConfirmation()
        assertEquals(1, vibrateCount)
        now += 1
        a.onNeedsConfirmation()
        assertEquals(2, vibrateCount)
    }

    @Test
    fun `勿扰或静音时不震动且不消耗限频窗口`() {
        val a = alerter()
        allowed = false
        a.onNeedsConfirmation()
        assertEquals(0, vibrateCount)
        // 门禁解除后立即震动（上一次被拦截未占用 30s 窗口）
        allowed = true
        a.onNeedsConfirmation()
        assertEquals(1, vibrateCount)
    }
}
