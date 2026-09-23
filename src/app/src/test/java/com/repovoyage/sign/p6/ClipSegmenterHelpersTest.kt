package com.repovoyage.sign.p6

import com.repovoyage.sign.video.annexBToRawNal
import com.repovoyage.sign.video.extractParameterSets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 切片器纯函数验收（MediaMuxer 交互留真机联调）：Annex-B → 裸 VCL NAL 提取
 * 与 CSD 提取——MP4 段可解码性的前提。样本必须是**无前缀裸 NAL**：MIUI
 * MPEG4Writer 自行加 4 字节长度前缀（真机验尸：喂 AVCC 双重前缀 → 服务器
 * 解出 0 帧）。
 */
class ClipSegmenterHelpersTest {

    private fun nal(vararg bytes: Int): ByteArray = bytes.map { it.toByte() }.toByteArray()

    private val sc4 = nal(0, 0, 0, 1)
    private val sc3 = nal(0, 0, 1)

    @Test
    fun `裸 NAL 提取——剔除参数集并去掉起始码`() {
        val data = sc4 + nal(0x67, 0x42, 0x00, 0x1E) +
            sc3 + nal(0x68, 0xCE, 0x38, 0x80) +
            sc4 + nal(0x65, 0xAA, 0xBB)
        assertArrayEquals(nal(0x65, 0xAA, 0xBB), annexBToRawNal(data))
    }

    @Test
    fun `裸 NAL 提取——4 字节起始码的前导零不得混入载荷`() {
        // 后随 4 字节起始码时，前一 NAL 的 end 含其首个 00，须裁掉
        val data = sc4 + nal(0x65, 0x11, 0x22) + sc4 + nal(0x68, 0xCE)
        // 两个 VCL？否：65 是 VCL，68 是 PPS → 单 VCL
        assertArrayEquals(nal(0x65, 0x11, 0x22), annexBToRawNal(data))
    }

    @Test
    fun `多 slice AU 不支持——返回 null（调用方整段作废）`() {
        val data = sc4 + nal(0x65, 0xAA) + sc3 + nal(0x41, 0xCC)
        assertNull(annexBToRawNal(data))
        // 三个 VCL 同样拒绝（不得因置 null 后被第三个误置回）
        val three = sc4 + nal(0x41, 0x01) + sc3 + nal(0x41, 0x02) + sc3 + nal(0x41, 0x03)
        assertNull(annexBToRawNal(three))
    }

    @Test
    fun `无 VCL 帧返回 null`() {
        val data = sc4 + nal(0x67, 0x42) + sc3 + nal(0x68, 0xCE)
        assertNull(annexBToRawNal(data))
    }

    @Test
    fun `CSD 提取——SPS 与 PPS 各带 4 字节起始码`() {
        val data = sc3 + nal(0x67, 0x42, 0x00, 0x1E) + sc4 + nal(0x68, 0xCE, 0x38) + sc3 + nal(0x65, 0xAA)
        val (sps, pps) = extractParameterSets(data)!!
        assertArrayEquals(sc4 + nal(0x67, 0x42, 0x00, 0x1E), sps)
        assertArrayEquals(sc4 + nal(0x68, 0xCE, 0x38), pps)
    }

    @Test
    fun `CSD 不齐返回 null`() {
        val data = sc4 + nal(0x67, 0x42) + sc3 + nal(0x65, 0xAA)
        assertNull(extractParameterSets(data))
    }

    @Test
    fun `裸 NAL 提取——载荷内部零字节不受裁剪影响`() {
        val data = sc4 + nal(0x41, 0x00, 0x00, 0x03, 0x00, 0x55)
        val result = annexBToRawNal(data)!!
        assertEquals(6, result.size)
        assertEquals(0x41, result[0].toInt())
    }
}
