package com.repovoyage.sign.p7

import com.repovoyage.sign.sentence.LangCode
import com.repovoyage.sign.tts.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P7 TtsManager 验收（API.md §7 初始值）：串行一次一条、同步 ERROR → Failed、
 * 终结回调幂等（迟到不推进）、看门狗 60s、待播超期 MarkedUnspoken、
 * 语音未就绪拒绝、replay 停止当前并重播（新 utteranceId）、停止清空队列。
 * 时钟注入，看门狗/超期巡检由测试手动驱动；tts 包通配 import（K2 竞态规避）。
 */
class TtsManagerTest {

    private class FakeSpeaker : TtsSpeaker {
        val ready = mutableSetOf("zh-CN")
        val spoken = mutableListOf<Triple<String, String, String>>()   // (utteranceId, text, lang)
        var stopCount = 0
        var nextSyncError = false
        var listener: ((String, SpeakerOutcome) -> Unit)? = null

        override fun isLanguageReady(language: LangCode) = language.tag in ready

        override fun speak(utteranceId: String, text: String, language: LangCode): Boolean {
            spoken += Triple(utteranceId, text, language.tag)
            val err = nextSyncError
            nextSyncError = false
            return !err
        }

        override fun stop() {
            stopCount++
        }

        override fun setTerminalListener(l: (String, SpeakerOutcome) -> Unit) {
            listener = l
        }
    }

    private var now = 0L
    private lateinit var speaker: FakeSpeaker
    private lateinit var scope: CoroutineScope
    private lateinit var manager: TtsManagerImpl
    private val events = mutableListOf<TtsEvent>()

    @Before
    fun setUp() {
        now = 0L
        speaker = FakeSpeaker()
        scope = CoroutineScope(Dispatchers.Unconfined)
        manager = TtsManagerImpl(
            speaker = speaker,
            scope = scope,
            monoMs = { now },
            tickMs = Long.MAX_VALUE,   // 巡检不自动跑，测试手动 checkTimers
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun req(seg: String, lang: String = "zh-CN", order: Long = 1) =
        SpeakRequest("s-1", seg, LangCode(lang), "文本$seg", order)

    private fun collectEvents() {
        scope.launch { manager.events.collect { events += it } }
    }

    private fun terminal(id: String, outcome: SpeakerOutcome) {
        speaker.listener!!(id, outcome)
    }

    private fun utteranceOf(seg: String): String {
        val idx = seg.drop(1).toInt() - 1
        return speaker.spoken[idx].first
    }

    @Test
    fun `串行一次一条与终结推进`() = runBlocking {
        collectEvents()
        assertEquals(EnqueueResult.Queued, manager.enqueue(req("m1", order = 1)))
        assertEquals(EnqueueResult.Queued, manager.enqueue(req("m2", order = 2)))
        // 只播了 m1，m2 等待
        assertEquals(listOf("m1"), speaker.spoken.map { it.second.drop(2) })
        terminal(utteranceOf("m1"), SpeakerOutcome.DONE)
        // m1 完结后 m2 开始
        assertEquals(listOf("m1", "m2"), speaker.spoken.map { it.second.drop(2) })
        terminal(utteranceOf("m2"), SpeakerOutcome.DONE)
        assertEquals(2, events.filterIsInstance<TtsEvent.Started>().size)
        assertEquals(2, events.filterIsInstance<TtsEvent.Finished>().size)
    }

    @Test
    fun `同步 ERROR 发 Failed 并推进`() = runBlocking {
        collectEvents()
        speaker.nextSyncError = true
        manager.enqueue(req("m1"))
        assertEquals(1, events.filterIsInstance<TtsEvent.Failed>().size)
        assertEquals(TtsError.ENGINE_ERROR, events.filterIsInstance<TtsEvent.Failed>().single().reason)
        assertEquals(0, events.filterIsInstance<TtsEvent.Started>().size)
        // 失败后队列继续
        manager.enqueue(req("m2"))
        assertEquals(listOf("m1", "m2"), speaker.spoken.map { it.second.drop(2) })
    }

    @Test
    fun `迟到回调不推进新任务`() = runBlocking {
        collectEvents()
        manager.enqueue(req("m1"))
        manager.enqueue(req("m2"))
        terminal(utteranceOf("m1"), SpeakerOutcome.DONE)
        val spokenAfterM1 = speaker.spoken.size
        // m1 的迟到重复回调：不产生新事件、不影响 m2
        terminal(utteranceOf("m1"), SpeakerOutcome.DONE)
        assertEquals(spokenAfterM1, speaker.spoken.size)
        assertEquals(1, events.filterIsInstance<TtsEvent.Finished>().size)
        terminal(utteranceOf("m2"), SpeakerOutcome.DONE)
        assertEquals(2, events.filterIsInstance<TtsEvent.Finished>().size)
    }

    @Test
    fun `看门狗超时终结并推进`() = runBlocking {
        collectEvents()
        manager.enqueue(req("m1"))
        manager.enqueue(req("m2"))
        now += 60_000
        manager.checkTimers()
        val failed = events.filterIsInstance<TtsEvent.Failed>().single()
        assertEquals(TtsError.WATCHDOG_TIMEOUT, failed.reason)
        // 超时后推进 m2
        assertEquals(listOf("m1", "m2"), speaker.spoken.map { it.second.drop(2) })
        // 引擎侧随后补来的回调被幂等吸收：不产生 Finished（m2 仍未完结）
        terminal(failed.utteranceId, SpeakerOutcome.DONE)
        assertEquals(0, events.filterIsInstance<TtsEvent.Finished>().size)
    }

    @Test
    fun `待播超期转未播报且不再播`() = runBlocking {
        collectEvents()
        manager.enqueue(req("m1"))
        manager.enqueue(req("m2"))
        now += 5_000
        manager.checkTimers()
        val unspoken = events.filterIsInstance<TtsEvent.MarkedUnspoken>().single()
        assertEquals("m2", unspoken.segmentId)
        terminal(utteranceOf("m1"), SpeakerOutcome.DONE)
        assertEquals(listOf("m1"), speaker.spoken.map { it.second.drop(2) })   // m2 被移出待播
    }

    @Test
    fun `语音未就绪拒绝`() = runBlocking {
        val r = manager.enqueue(req("m1", lang = "en-US"))
        assertEquals(EnqueueResult.Rejected(RejectReason.VOICE_NOT_READY), r)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun `replay 停止当前并重播新 utteranceId`() = runBlocking {
        collectEvents()
        manager.enqueue(req("m1"))
        val firstId = utteranceOf("m1")
        manager.replay("s-1", "m1", LangCode("zh-CN"))
        assertEquals(1, speaker.stopCount)   // 停止当前
        terminal(firstId, SpeakerOutcome.STOP)   // 引擎 onStop 终结旧播报
        assertEquals(listOf("m1", "m1"), speaker.spoken.map { it.second.drop(2) })
        val secondId = speaker.spoken[1].first
        assertNotEquals(firstId, secondId)
    }

    @Test
    fun `停止清空队列不再播后续`() = runBlocking {
        collectEvents()
        manager.enqueue(req("m1"))
        manager.enqueue(req("m2"))
        manager.stopCurrentAndClearQueue()
        terminal(utteranceOf("m1"), SpeakerOutcome.STOP)
        assertEquals(listOf("m1"), speaker.spoken.map { it.second.drop(2) })
    }

    @Test
    fun `设置变更清空待播但当前句播完`() = runBlocking {
        collectEvents()
        manager.enqueue(req("m1"))
        manager.enqueue(req("m2"))
        manager.enqueue(req("m3"))
        assertEquals(1, speaker.spoken.size)          // m1 播报中，m2/m3 待播
        manager.clearPendingKeepCurrent()
        assertEquals(0, speaker.stopCount)            // 不打断当前句
        terminal(utteranceOf("m1"), SpeakerOutcome.DONE)
        assertEquals(1, speaker.spoken.size)          // 待播已清，不再推进新句
        assertTrue(events.any { it is TtsEvent.Finished })
    }
}
