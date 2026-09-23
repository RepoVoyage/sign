package com.repovoyage.sign.alert

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.app.NotificationManager
import android.os.Build

/**
 * 待核对震动提醒（§2.7，2026-09-23 用户决定：**仅 LLM 翻译低置信
 * （NEEDS_CONFIRMATION）触发震动**，低电/断连/过载等一律不震）。
 *
 * - 限频：每 [MIN_INTERVAL_MS]（30s，§2.7 初始值）最多一次
 * - 遵守系统勿扰与震动设置：静音模式或勿扰过滤非 ALL 时不震
 *   （Vibrator API 会绕过系统勿扰，必须应用侧自查；不单独依赖
 *   getRingerMode——§2.7）；被拦截的事件不消耗限频窗口
 * - 震动效果按 VibrationEffect 波形 API 显式构造（短双震，区别于
 *   通知震动）；真机实测手感随验收调参
 *
 * 时钟/门禁/震动执行全部注入，状态机 JVM 可测。
 */
class ConfirmationAlerter(
    private val nowMs: () -> Long,
    private val canVibrate: () -> Boolean,
    private val vibrate: () -> Unit,
) {

    private var lastVibratedAtMs: Long? = null

    /** LLM 结果转 NEEDS_CONFIRMATION 时调用；限频与勿扰门禁内置 */
    fun onNeedsConfirmation() {
        val now = nowMs()
        val last = lastVibratedAtMs
        if (last != null && now - last < MIN_INTERVAL_MS) return
        if (!canVibrate()) return
        lastVibratedAtMs = now
        vibrate()
    }

    companion object {
        const val MIN_INTERVAL_MS = 30_000L

        fun create(context: Context): ConfirmationAlerter {
            // 短双震：150ms 震 - 100ms 停 - 150ms 震，不循环（波形在真机验收时调参）
            val effect = VibrationEffect.createWaveform(
                longArrayOf(150, 100, 150),
                intArrayOf(VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE),
                -1,
            )
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val audio = context.getSystemService(AudioManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            return ConfirmationAlerter(
                nowMs = SystemClock::elapsedRealtime,
                canVibrate = {
                    vibrator.hasVibrator() &&
                        audio.ringerMode != AudioManager.RINGER_MODE_SILENT &&
                        notifications.currentInterruptionFilter.let {
                            it == NotificationManager.INTERRUPTION_FILTER_ALL ||
                                it == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                        }
                },
                vibrate = { vibrator.vibrate(effect) },
            )
        }
    }
}
