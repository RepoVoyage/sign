package com.repovoyage.sign.p3

import com.repovoyage.sign.video.EncodedFrame
import com.repovoyage.sign.video.H264DecodePrep
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 解码准备链验收（ARCHITECTURE.md §2.2.2 检查表：参数集准备 + 同步起点）。
 * 关键帧必须经验证才可作随机访问点：参数集（SPS/PPS）齐全后的 IDR 才标记
 * isSyncPoint；纯参数集/SEI 帧不产出；代次切换视为新流，参数集状态重置。
 */
class H264DecodePrepTest {

    private val prep = H264DecodePrep()

    /** 构造 Annex-B NAL：00 00 00 01 + header + payload（header 高 3 位为常规取值） */
    private fun nal(header: Int, vararg payload: Int): ByteArray =
        byteArrayOf(0, 0, 0, 1, header.toByte()) + payload.map { it.toByte() }.toByteArray()

    private fun frame(vararg nals: ByteArray, ts: Long = 100, gen: Long = 1): EncodedFrame =
        EncodedFrame(nals.reduce { a, b -> a + b }, ts * 1_000, isSyncPoint = false, gen)

    @Test
    fun `参数集后的 IDR 帧标记为随机访问点`() {
        assertNull(prep.process(frame(nal(0x67), nal(0x68))))          // 纯参数集帧吸收
        val idr = prep.process(frame(nal(0x65)))
        assertNotNull(idr)
        assertTrue(idr!!.isSyncPoint)
    }

    @Test
    fun `参数集与 IDR 同帧直接可作随机访问点`() {
        // 相机 CSD 随流发送时参数集与 IDR 可能同帧（P0 实测 61 字节 CSD）
        val idr = prep.process(frame(nal(0x67), nal(0x68), nal(0x65)))
        assertNotNull(idr)
        assertTrue(idr!!.isSyncPoint)
    }

    @Test
    fun `P 帧永不标记 syncPoint`() {
        prep.process(frame(nal(0x67), nal(0x68)))
        assertTrue(prep.process(frame(nal(0x65)))!!.isSyncPoint)
        assertFalse(prep.process(frame(nal(0x41)))!!.isSyncPoint)
        assertFalse(prep.process(frame(nal(0x41)))!!.isSyncPoint)
    }

    @Test
    fun `参数集未齐时 IDR 不作随机访问点`() {
        // 无 SPS/PPS 的 IDR 无法配置解码器，不得作为随机访问起点（§2.2.2 第 5 行）
        assertFalse(prep.process(frame(nal(0x65)))!!.isSyncPoint)
        assertTrue(prep.process(frame(nal(0x67), nal(0x68), nal(0x65)))!!.isSyncPoint)
    }

    @Test
    fun `纯参数集或 SEI 帧被吸收不产出`() {
        assertNull(prep.process(frame(nal(0x67), nal(0x68))))
        assertNull(prep.process(frame(nal(0x06))))
        assertNotNull(prep.process(frame(nal(0x65))))
    }

    @Test
    fun `代次切换重置参数集状态`() {
        assertTrue(prep.process(frame(nal(0x67), nal(0x68), nal(0x65), gen = 1))!!.isSyncPoint)
        // 新代次 = 新流：旧参数集失效，须重新学到 SPS/PPS（§2.2.2 第 7 行）
        assertFalse(prep.process(frame(nal(0x65), gen = 2))!!.isSyncPoint)
        assertNull(prep.process(frame(nal(0x67), nal(0x68), gen = 2)))
        assertTrue(prep.process(frame(nal(0x65), gen = 2))!!.isSyncPoint)
    }

    @Test
    fun `CSD 提取需参数集齐全`() {
        assertNull(prep.csd())
        prep.process(frame(nal(0x67, 1, 2)))
        assertNull(prep.csd())                                          // 只有 SPS
        prep.process(frame(nal(0x68, 3)))
        val csd = prep.csd()
        assertNotNull(csd)
        assertArrayEquals(nal(0x67, 1, 2), csd!!.first)
        assertArrayEquals(nal(0x68, 3), csd.second)
    }
}
