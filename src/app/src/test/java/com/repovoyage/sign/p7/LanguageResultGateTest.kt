package com.repovoyage.sign.p7

import com.repovoyage.sign.language.LanguageResult
import com.repovoyage.sign.language.LanguageResultGate
import com.repovoyage.sign.language.OutputSource
import com.repovoyage.sign.language.OutputStatus
import com.repovoyage.sign.sentence.LangCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7 语言结果去重门验收（API.md §6.2：去重键 (sessionId, segmentId, sentenceRevision,
 * language) 仅保留一个有效结果；settingsRevision 落后拒绝）。
 */
class LanguageResultGateTest {

    private val gate = LanguageResultGate()

    private fun result(seg: String, revision: Int, settingsRev: Long, lang: String = "zh-CN") =
        LanguageResult(
            sessionId = "s-1", streamGeneration = 1, sequenceEpoch = 1,
            segmentId = seg, sentenceRevision = revision, settingsRevision = settingsRev,
            language = LangCode(lang), text = "译文", status = OutputStatus.READY,
            source = OutputSource.CLOUD, elapsedMs = 100,
        )

    @Test
    fun `同去重键仅保留首个有效结果`() {
        assertTrue(gate.accept(result("m1", 2, settingsRev = 5)))
        assertFalse(gate.accept(result("m1", 2, settingsRev = 5)))
    }

    @Test
    fun `不同 revision 或语言不受去重影响`() {
        gate.accept(result("m1", 2, settingsRev = 5))
        assertTrue(gate.accept(result("m1", 3, settingsRev = 5)))
        assertTrue(gate.accept(result("m1", 2, settingsRev = 5, lang = "en-US")))
        assertTrue(gate.accept(result("m2", 2, settingsRev = 5)))
    }

    @Test
    fun `settingsRevision 落后于已见快照的迟到结果拒绝`() {
        gate.accept(result("m1", 1, settingsRev = 2))
        assertTrue(gate.accept(result("m2", 1, settingsRev = 3)))     // 新快照
        assertFalse(gate.accept(result("m3", 1, settingsRev = 2)))    // 旧快照迟到
    }
}
