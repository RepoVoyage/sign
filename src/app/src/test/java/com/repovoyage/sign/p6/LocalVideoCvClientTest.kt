package com.repovoyage.sign.p6

import com.repovoyage.sign.recognition.parseCvResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVideoCvClientTest {
    @Test
    fun parsesRankedCandidatesWithoutTreatingScoresAsConfidence() {
        val result = parseCvResponse("""{
            "status":"OK", "frames":77, "any_hand_fraction":0.5,
            "needsConfirmation":true,
            "candidates":[{"label":"我","score":0.9},{"label":"回","score":0.08}]
        }""")
        assertEquals("OK", result.status)
        assertEquals(77, result.frames)
        assertEquals(listOf("我", "回"), result.candidates.map { it.label })
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun keepsRejectedVideoWithoutInventingCandidates() {
        val result = parseCvResponse("""{
            "status":"INSUFFICIENT_HAND_DETECTION", "frames":30,
            "any_hand_fraction":0.03, "needsConfirmation":true, "candidates":[]
        }""")
        assertTrue(result.candidates.isEmpty())
    }
}
