package com.repovoyage.sign.video

/**
 * 解码准备链（ARCHITECTURE.md §2.2.2 检查表：参数集准备 + 同步起点）。
 *
 * 逐帧扫描 Annex-B NAL 单元：SPS/PPS 更新为最新（相机 CSD 随流重发时自然刷新），
 * 组成 CSD 供 MediaCodec configure；仅"参数集齐全 + 含 IDR slice"的帧才标记
 * isSyncPoint（聚合层恒为 false，由本层标记）。纯参数集/SEI 等无 VCL 的帧吸收
 * 不产出。代次切换视为新流：参数集状态重置，须重新学到 SPS/PPS 才有随机访问点。
 *
 * 仅支持 H.264（NAL 头 1 字节，type 5=IDR/7=SPS/8=PPS）；H.265（VPS/SPS/PPS）
 * 待实际遇到 H265 流时另行接入。
 */
class H264DecodePrep {

    private var generation: Long? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /**
     * 解析一帧：更新参数集状态并标记 isSyncPoint。
     * @return 无 VCL 数据（纯参数集/SEI 帧）时返回 null
     */
    fun process(frame: EncodedFrame): EncodedFrame? {
        if (frame.streamGeneration != generation) {
            generation = frame.streamGeneration
            sps = null
            pps = null
        }
        var hasVcl = false
        var hasIdr = false
        forEachNal(frame.data) { start, end ->
            val type = frame.data[start].toInt() and 0x1F
            when (type) {
                NAL_TYPE_SPS -> sps = extractNal(frame.data, start, end)
                NAL_TYPE_PPS -> pps = extractNal(frame.data, start, end)
                NAL_TYPE_IDR -> {
                    hasVcl = true
                    hasIdr = true
                }
                NAL_TYPE_NON_IDR -> hasVcl = true
                // SEI/AUD 等非 VCL：忽略
                else -> {}
            }
        }
        if (!hasVcl) return null
        return if (hasIdr && sps != null && pps != null) frame.copy(isSyncPoint = true) else frame
    }

    /** MediaCodec configure 用 CSD（csd-0=SPS、csd-1=PPS，各含 Annex-B 起始码）；未齐返回 null */
    fun csd(): Pair<ByteArray, ByteArray>? {
        val s = sps ?: return null
        val p = pps ?: return null
        return s to p
    }

    /** Annex-B 扫描：以 00 00 01（或 00 00 00 01）分界，对每个 NAL 调 block(start, end) */
    private inline fun forEachNal(data: ByteArray, block: (start: Int, end: Int) -> Unit) {
        var i = 0
        var start = -1
        while (i <= data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (start >= 0) block(start, i)
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start >= 0 && start < data.size) block(start, data.size)
    }

    /** 取出 NAL 并补 4 字节起始码；尾部 0x00 属下一起始码前导/ trailing_zero，裁掉 */
    private fun extractNal(data: ByteArray, start: Int, end: Int): ByteArray {
        var e = end
        while (e > start && data[e - 1] == 0.toByte()) e--
        return byteArrayOf(0, 0, 0, 1) + data.copyOfRange(start, e)
    }

    private companion object {
        const val NAL_TYPE_NON_IDR = 1
        const val NAL_TYPE_IDR = 5
        const val NAL_TYPE_SPS = 7
        const val NAL_TYPE_PPS = 8
    }
}
