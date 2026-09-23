package com.repovoyage.sign.service

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
import com.repovoyage.sign.camera.CameraSession
import com.repovoyage.sign.camera.SdkCameraSession
import com.repovoyage.sign.camera.sessionStateText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 相机连接/取流前台服务（Manifest 已声明 connectedDevice 类型）。
 * 持有唯一的 [SdkCameraSession]，UI 通过 [session] 访问；通知文本跟随
 * 会话状态实时刷新（P7 通知策略，§2.7：状态可见，不打扰）。
 */
class CameraBridgeForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        session = SdkCameraSession(applicationContext, scope)
        // 通知跟随会话状态（连接中/取流中/重连中…），锁屏与下拉栏可见
        scope.launch {
            session?.state?.collect { state ->
                notificationManager.notify(NOTIFICATION_ID, buildNotification(sessionStateText(state)))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                session?.stop()
                stopSelf()
            }
        }
        // 会话不可断点续传，进程被杀后由用户重新发起
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.camera_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.camera_service_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.camera_service_notification_text)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "camera_session"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.repovoyage.sign.action.STOP_SESSION"

        @Volatile
        var session: CameraSession? = null
            private set

        /** 会话由本服务持有；启动前服务未创建时返回 null */
        fun start(context: Context) {
            context.startService(
                Intent(context, CameraBridgeForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CameraBridgeForegroundService::class.java)
                    .setAction(ACTION_STOP)
            )
        }
    }
}
