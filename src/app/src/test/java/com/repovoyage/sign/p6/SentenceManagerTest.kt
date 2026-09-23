package com.repovoyage.sign.p6

import com.repovoyage.sign.sentence.BoundaryReliability
import com.repovoyage.sign.sentence.BoundarySignal
import com.repovoyage.sign.sentence.BoundarySource
import com.repovoyage.sign.sentence.RecognitionUpdate
import com.repovoyage.sign.sentence.SentenceEvent
import com.repovoyage.sign.sentence.SentenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 句子管理验收（API.md §4/§5 / ARCHITECTURE §2.4.3）。
 */
class SentenceManagerTest {

    private val manager = SentenceManager(sessionId = "s-1", streamGeneration = 3)

    private fun update(
        seg: String?, text: String, epoch: Long = 1,
        boundary: BoundarySignal? = null, confidence: Float? = 0.8f,
    ) = RecognitionUpdate(epoch, seg, text, null, confidence, boundary)

    private fun reliable() =
        BoundarySignal(9_000, 300, BoundaryReliability.RELIABLE, BoundarySource.MODEL)

    private fun uncertain() =
        BoundarySignal(9_000, 300, BoundaryReliability.UNCERTAIN, BoundarySource.MODEL)

    @Test
    fun `重叠窗口更新同段草稿且 revision 递增`() {
        assertEquals(
            listOf(SentenceEvent.DraftUpdated("m1", 1, "我")),
            manager.submit(update("m1", "我")),
        )
        assertEquals(
            listOf(SentenceEvent.DraftUpdated("m1", 2, "我需要")),
            manager.submit(update("m1", "我需要")),
        )
    }

    @Test
    fun `segmentId 为空时开新段`() {
        val events = manager.submit(update(null, "你好"))
        val draft = events.filterIsInstance<SentenceEvent.DraftUpdated>().single()
        assertTrue(draft.segmentId.isNotBlank())
        assertEquals(1, draft.revision)
    }

    @Test
    fun `RELIABLE 边界进入 FINALIZING`() {
        manager.submit(update("m1", "我"))
        val events = manager.submit(update("m1", "我需要", boundary = reliable()))
        assertTrue(SentenceEvent.Finalizing("m1") in events)
    }

    @Test
    fun `UNCERTAIN 边界转待核对且不阻塞后续表达`() {
        manager.submit(update("m1", "我"))
        val events = manager.submit(update("m1", "我", boundary = uncertain()))
        assertTrue(events.any { it is SentenceEvent.NeedsConfirmation && it.segmentId == "m1" })
        // 后续新段照常推进
        assertTrue(manager.submit(update("m2", "谢谢")).any { it is SentenceEvent.DraftUpdated })
    }

    @Test
    fun `finalize 冻结原文且迟到推理不覆盖`() {
        manager.submit(update("m1", "我"))
        manager.submit(update("m1", "我需要"))
        val events = manager.finalize("m1", startPtsUs = 0, endPtsUs = 9_000)
        val s = events.filterIsInstance<SentenceEvent.Final>().single().sentence
        assertEquals("我需要", s.rawChinese)
        assertEquals(2, s.revision)
        assertEquals(0L, s.startPtsUs)
        assertEquals(9_000L, s.endPtsUs)
        assertEquals("s-1", s.sessionId)
        assertEquals(3L, s.streamGeneration)
        assertEquals(false, s.userConfirmed)          // 自动确认
        // 冻结后迟到草稿被丢弃
        assertTrue(manager.submit(update("m1", "我需要帮")).isEmpty())
    }

    @Test
    fun `epoch 前进中断旧段且旧 epoch 迟到结果丢弃`() {
        manager.submit(update("m1", "我"))
        val events = manager.submit(update(null, "新句", epoch = 2))
        assertTrue(events.any { it is SentenceEvent.Interrupted && it.segmentId == "m1" })
        // 旧 epoch 的迟到更新不再推进任何段
        assertTrue(manager.submit(update("m1", "旧结果", epoch = 1)).isEmpty())
    }

    @Test
    fun `用户放弃待核对项转 DISCARDED`() {
        manager.submit(update("m1", "我", boundary = uncertain()))
        assertTrue(SentenceEvent.Discarded("m1") in manager.discard("m1"))
    }

    @Test
    fun `中断段的迟到 finalize 被丢弃`() {
        manager.submit(update("m1", "我"))
        manager.submit(update(null, "新句", epoch = 2))   // m1 → INTERRUPTED
        assertTrue(manager.finalize("m1", 0, 9_000).isEmpty())
    }

    @Test
    fun `FINALIZING 段被 epoch 前进中断`() {
        manager.submit(update("m1", "我"))
        manager.submit(update("m1", "我需要", boundary = reliable()))
        val events = manager.submit(update(null, "新句", epoch = 2))
        assertTrue(events.any { it is SentenceEvent.Interrupted && it.segmentId == "m1" })
    }

    @Test
    fun `未知段 finalize 与非待核对段 discard 幂等返回空`() {
        assertTrue(manager.finalize("ghost", 0, 9_000).isEmpty())
        manager.submit(update("m1", "我"))                 // DRAFT（未转待核对）
        assertTrue(manager.discard("m1").isEmpty())
    }
}
