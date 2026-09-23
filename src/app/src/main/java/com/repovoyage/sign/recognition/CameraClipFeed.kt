package com.repovoyage.sign.recognition

import com.repovoyage.sign.camera.SdkCameraSession
import com.repovoyage.sign.video.ClipSegmenter
import com.repovoyage.sign.video.ClipOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 相机切片供给（[ClipFeed] 生产实现）：把 [SdkCameraSession] 的编码帧分流
 * （encodedFrameTap，解码前 H.264 原始帧）接到 [ClipSegmenter]，固定窗口
 * 封装 MP4；关键帧按需用 requestStreamIframe()（GOP 实测超长）。
 * 切片要求相机已在取流且有实际解码尺寸（MediaFormat 需要真实宽高）。
 */
class CameraClipFeed(private val sessionProvider: () -> SdkCameraSession?) : ClipFeed {

    private var session: SdkCameraSession? = null
    private var segmenter: ClipSegmenter? = null

    override fun attach(
        outputDir: File,
        windowUs: Long,
        scope: CoroutineScope,
        onSegment: (File, Long, Long) -> Unit,
        onDropped: () -> Unit,
        onSentenceBoundary: () -> Unit,
    ): String? {
        val sdk = sessionProvider() ?: return "请先连接相机并开始取流"
        val stats = sdk.decodeStats.value
        if (stats == null || stats.width <= 0 || stats.height <= 0) {
            return "相机流未就绪（无解码尺寸），请等取流稳定后再开始"
        }
        outputDir.mkdirs()
        val segmenter = ClipSegmenter(
            outputDir = outputDir,
            width = stats.width,
            height = stats.height,
            windowUs = windowUs,
            onKeyFrameNeeded = sdk::requestKeyFrame,
        )
        this.session = sdk
        this.segmenter = segmenter
        sdk.encodedFrameTap = segmenter::offer
        scope.launch { segmenter.run() }
        scope.launch {
            segmenter.segments.collect { output ->
                when (output) {
                    is ClipOutput.Video -> onSegment(
                        output.segment.file, output.segment.startPtsUs, output.segment.endPtsUs,
                    )
                    ClipOutput.Dropped -> onDropped()
                    ClipOutput.SentenceBoundary -> onSentenceBoundary()
                }
            }
        }
        sdk.requestKeyFrame()
        return null
    }

    override fun finishSentence(): Boolean = segmenter?.flushSentence() ?: false

    override fun detach() {
        session?.encodedFrameTap = null
        segmenter?.release()
        session = null
        segmenter = null
    }
}
