package com.repovoyage.sign.p7

import com.repovoyage.sign.sentence.LangCode
import com.repovoyage.sign.tts.AutoSpeakQueue
import com.repovoyage.sign.tts.EnqueueResult
import com.repovoyage.sign.tts.RejectReason
import com.repovoyage.sign.tts.SpeakRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7 自动播报队列验收（API.md §7 初始值：串行一次一条、待播 3 句、
 * 去重键 (sessionId, segmentId, language)、待播 5 秒未开始转未播报）。
 */
class AutoSpeakQueueTest {

    private val queue = AutoSpeakQueue()

    private fun req(seg: String, lang: String = "zh-CN", order: Long = 1) =
        SpeakRequest("s-1", seg, LangCode(lang), "文本$seg", order)

    @Test
    fun `同键重复入队返回 Duplicate`() {
        assertEquals(EnqueueResult.Queued, queue.enqueue(req("m1"), nowMonoMs = 0))
        assertEquals(EnqueueResult.Duplicate, queue.enqueue(req("m1"), nowMonoMs = 10))
    }

    @Test
    fun `不同语言或不同段不算重复`() {
        queue.enqueue(req("m1", lang = "zh-CN"), 0)
        assertEquals(EnqueueResult.Queued, queue.enqueue(req("m1", lang = "en-US"), 0))
        assertEquals(EnqueueResult.Queued, queue.enqueue(req("m2", lang = "zh-CN"), 0))
    }

    @Test
    fun `待播缓存 3 句容量满拒绝`() {
        queue.enqueue(req("m1"), 0)
        queue.enqueue(req("m2"), 0)
        queue.enqueue(req("m3"), 0)
        assertEquals(EnqueueResult.Rejected(RejectReason.QUEUE_FULL), queue.enqueue(req("m4"), 0))
    }

    @Test
    fun `串行一次一条`() {
        queue.enqueue(req("m1", order = 1), 0)
        queue.enqueue(req("m2", order = 2), 0)
        assertEquals("m1", queue.pollNext()?.segmentId)
        assertNull(queue.pollNext())          // 上一条未结束，不抢播
        queue.onPlaybackFinished()
        assertEquals("m2", queue.pollNext()?.segmentId)
    }

    @Test
    fun `进入待播 5 秒未开始转未播报`() {
        queue.enqueue(req("m1"), nowMonoMs = 0)
        assertTrue(queue.checkOverdue(nowMonoMs = 4_999).isEmpty())
        assertEquals(1, queue.checkOverdue(nowMonoMs = 5_000).size)
    }

    @Test
    fun `已开始播报的条目不再标注未播报`() {
        queue.enqueue(req("m1"), nowMonoMs = 0)
        queue.pollNext()
        assertTrue(queue.checkOverdue(nowMonoMs = 6_000).isEmpty())
    }

    @Test
    fun `播报过的键再次入队仍判重复`() {
        queue.enqueue(req("m1"), 0)
        queue.pollNext()
        queue.onPlaybackFinished()
        assertEquals(EnqueueResult.Duplicate, queue.enqueue(req("m1"), nowMonoMs = 100))
    }

    @Test
    fun `待播条目按 orderKey 顺序出队`() {
        queue.enqueue(req("m3", order = 30), 0)
        queue.enqueue(req("m1", order = 10), 0)
        queue.enqueue(req("m2", order = 20), 0)
        assertEquals("m1", queue.pollNext()?.segmentId)
        queue.onPlaybackFinished()
        assertEquals("m2", queue.pollNext()?.segmentId)
    }
}
