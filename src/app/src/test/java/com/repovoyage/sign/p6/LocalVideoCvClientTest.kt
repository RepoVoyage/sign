package com.repovoyage.sign.p6

import com.repovoyage.sign.recognition.parseCvResponse
import com.repovoyage.sign.recognition.buildComposeBody
import com.repovoyage.sign.recognition.parseComposeResponse
import org.json.JSONObject
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

    @Test
    fun sendsRecognizedCandidateGroupsInVideoSelectionOrder() {
        val first = parseCvResponse("""{"status":"OK","frames":60,"any_hand_fraction":0.8,
            "needsConfirmation":true,"candidates":[{"label":"我","score":0.9}]}""")
        val second = parseCvResponse("""{"status":"OK","frames":61,"any_hand_fraction":0.7,
            "needsConfirmation":true,"candidates":[{"label":"回","score":0.6},{"label":"你","score":0.3}]}""")
        val json = JSONObject(buildComposeBody("12345678-1234-1234-1234-123456789abc", "seg-1", 2,
            listOf(first, second)))
        val gestures = json.getJSONArray("gestures")
        assertEquals("我", gestures.getJSONObject(0).getJSONArray("candidates").getJSONObject(0).getString("label"))
        assertEquals("回", gestures.getJSONObject(1).getJSONArray("candidates").getJSONObject(0).getString("label"))
        assertEquals(0.3, gestures.getJSONObject(1).getJSONArray("candidates").getJSONObject(1).getDouble("score"), 0.0001)
        assertEquals(2, json.getInt("revision"))
    }

    @Test
    fun displaysAmbiguousComposeWithoutInventingSentence() {
        val result = parseComposeResponse("""{"segmentId":"seg-1","revision":1,"sentence":null,
            "alternatives":["我想回家"],"status":"AMBIGUOUS","needsConfirmation":true}""")
        assertEquals(null, result.sentence)
        assertEquals("AMBIGUOUS", result.status)
        assertTrue(result.needsConfirmation)
    }
}
