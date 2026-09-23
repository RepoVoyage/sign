package com.repovoyage.sign.video

import com.arashivision.sdk.camera.api.preview.PreviewStreamType
import java.io.File

/** API.md §2.1 — SDK 回调入口；进入本层前已完成有界复制，取得所有权 */
data class StreamChunk(
    val data: ByteArray,
    val timestampMs: Long,               // 相机时钟，同帧分片共享
    val type: PreviewStreamType,         // 仅 VIDEO 类型进入聚合
    val receivedAtMonoMs: Long,
    val streamGeneration: Long,
)

/** timestamp 切换提交的聚合帧；未确认完整的尾帧丢弃 */
data class EncodedFrame(
    val data: ByteArray,
    val ptsUs: Long,                     // ms → µs
    val isSyncPoint: Boolean,            // 仅经验证的随机访问帧（H.264 IDR 等）
    val streamGeneration: Long,
)

/** ClipSegmenter 产出的一段 MP4（固定窗口、随机访问点起始）；消费方上传后删除文件 */
data class ClipSegment(
    val file: File,
    val startPtsUs: Long,
    val endPtsUs: Long,
)

/** P3 真机验证用取流统计；单写者（分片消费协程），验证收敛后移除 */
data class StreamStats(
    val generation: Long,
    val framesCommitted: Long,
    val bytesCommitted: Long,
    val lastPtsUs: Long,
    val syncFrames: Long,                // isSyncPoint=true 的帧数（验证 IDR 间隔用）
)

/** P3 真机验证用解码统计；单写者（解码消费协程），验证收敛后移除 */
data class DecodeStats(
    val generation: Long,
    val framesDecoded: Long,
    val width: Int,                      // 实际解码尺寸（SPS 实际值，可与声明值不同）
    val height: Int,
)
