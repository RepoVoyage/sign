package com.repovoyage.sign.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.repovoyage.sign.R
import com.repovoyage.sign.camera.DecodedFrameSink
import com.repovoyage.sign.camera.FrameGapEvent
import com.repovoyage.sign.camera.GapReason
import com.repovoyage.sign.camera.OverloadLocation
import com.repovoyage.sign.camera.SdkCameraSession
import com.repovoyage.sign.service.CameraBridgeForegroundService
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

/**
 * 训练采集前台服务（training flavor）：持有 [UsbBridgeServer]，把
 * SdkCameraSession 的解码输出经 [DecodedFrameSink] 送入 USB 发送池。
 *
 * 生命周期：相机取流已就绪后由 UI（CaptureEntry）启动；停止 = 用户点停止 →
 * server.stop()（2 秒收尾 + END）。临时配对令牌在通知栏展示，不写日志。
 * 采集授权流程与素材元数据（§2.8.3）在 P4 后续任务接入。
 */
class UsbCaptureService : Service() {

    private var pool: FrameSendPool? = null
    private var server: UsbBridgeServer? = null

    /** 相机解码回调线程直接调用：I420 转换在回调线程，socket 写出在 pump 线程 */
    private val adapter = object : DecodedFrameSink {
        override fun onFrame(frame: com.repovoyage.sign.video.DecodedFrame) {
            server?.offer(
                frame.planes, frame.width, frame.height,
                frame.crop.left, frame.crop.top, frame.crop.right, frame.crop.bottom,
                frame.ptsUs, frame.streamGeneration,
            )
        }

        override fun onGap(event: FrameGapEvent) {
            server?.reportGap(event.reason.name)
        }

        override fun onOverload(location: OverloadLocation) {
            server?.reportGap(GapReason.OVERLOAD.name)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        val cameraSession = CameraBridgeForegroundService.session as? SdkCameraSession
        if (cameraSession == null) {
            // 相机服务未就绪：无从采集，直接结束（UI 应在取流后再启动采集）
            stopSelf()
            return
        }
        val p = FrameSendPool(
            maxFrames = MAX_FRAMES,
            maxBytes = MAX_BUDGET_BYTES,     // 【初始值】128 MiB，真机吞吐实测后定案档位
        )
        val token = UUID.randomUUID().toString().replace("-", "").take(8)
        val s = UsbBridgeServer(
            token = token,
            sessionConfig = JSONObject()
                .put("captureSpecVersion", "1")
                .put("pixelFormat", "I420")
                .put("width", cameraSession.decodeStats.value?.width ?: 0)
                .put("height", cameraSession.decodeStats.value?.height ?: 0)
                .put("captureFps", 30)
                .put("bufferTargetMs", 2_000)
                .put("maxPayloadBytes", FrameCodec.MAX_IMAGE_PAYLOAD_BYTES)
                .put("preprocessVersion", "i420-compact-1")
                .apply {
                    // §2.8.3 采集元数据：相机型号/固件（连接时快照，未知则省略字段）
                    cameraSession.cameraModel?.let { put("cameraModel", it) }
                    cameraSession.cameraFirmware?.let { put("cameraFirmware", it) }
                },
            pool = p,
        )
        pool = p
        server = s
        currentToken = token
        s.start()
        updateTokenNotification(token)
        cameraSession.decodedFrameSink = adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        currentToken = null
        detachSink()
        // server.stop() 阻塞至收尾（≤2s 排空 + END），不占主线程
        val s = server
        server = null
        if (s != null) thread(name = "usb-capture-stop") { s.stop() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        detachSink()
        val s = server
        server = null
        // 收尾在线程里做，服务立即退场（END 尽力发送，PC 未收到按不完整处理）
        if (s != null) thread(name = "usb-capture-stop") { s.stop() }
        stopSelf()
    }

    private fun detachSink() {
        (CameraBridgeForegroundService.session as? SdkCameraSession)?.let {
            if (it.decodedFrameSink === adapter) it.decodedFrameSink = null
        }
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            baseNotification()
                .setContentTitle(getString(R.string.capture_service_notification_title))
                .setContentText(getString(R.string.capture_service_notification_text))
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    /** 令牌只在通知栏展示给用户（配对时念给 PC 端），不写日志 */
    private fun updateTokenNotification(token: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            baseNotification()
                .setContentTitle(getString(R.string.capture_service_notification_title))
                .setContentText(getString(R.string.capture_service_token_text, token))
                .build(),
        )
    }

    companion object {
        private const val CHANNEL_ID = "usb_capture"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.repovoyage.sign.training.action.STOP_CAPTURE"
        const val MAX_FRAMES = 60          // ceil(30fps × 2s)
        const val MAX_BUDGET_BYTES = 128L * 1024 * 1024

        @Volatile
        var currentToken: String? = null
            private set

        fun start(context: Context) {
            context.startService(Intent(context, UsbCaptureService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, UsbCaptureService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
