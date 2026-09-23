package com.repovoyage.sign.recognition

import com.repovoyage.sign.sentence.RecognitionUpdate
import com.repovoyage.sign.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 识别源路由（2026-09-23 用户决定：两模型均云端部署按视角选择）：
 * 选定模型 B（第一视角）→ [ClipRecognitionSource]（固定窗口切片云端识别，
 * 已部署联调服务）；其余（模型 A / 未选择）→ flavor 默认源（training=桩源，
 * production=不可用）。模型选择变更只影响下一次管线 start()。
 */
class RoutingRecognitionSource(
    settings: AppSettings,
    private val clipSource: ClipRecognitionSource,
    private val fallback: RecognitionSource,
    scope: CoroutineScope,
) : RecognitionSource {

    @Volatile private var useClip = false

    init {
        scope.launch {
            settings.selectedModelId.collect { useClip = it == ModelCatalog.MODEL_B_ID }
        }
    }

    private val delegate: RecognitionSource get() = if (useClip) clipSource else fallback

    override val isAvailable: Boolean get() = delegate.isAvailable
    override val sourceDescription: String get() = delegate.sourceDescription
    override val updates: Flow<RecognitionUpdate> get() = delegate.updates
    override fun start() = delegate.start()

    /** 两侧都停：路由可能在运行期间翻转，停错一侧会留下孤儿源 */
    override fun stop() {
        clipSource.stop()
        fallback.stop()
    }
}
