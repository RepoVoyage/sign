package com.repovoyage.sign

import android.app.Application
import android.net.ConnectivityManager
import android.util.Log
import com.arashivision.sdk.camera.InstaCameraSDK
import com.repovoyage.sign.alert.ConfirmationAlerter
import com.repovoyage.sign.camera.SdkCameraSession
import com.repovoyage.sign.language.DEFAULT_LLM_CLIENT
import com.repovoyage.sign.net.CloudNetworkManager
import com.repovoyage.sign.net.ConnectivityManagerCloudNetworkProvider
import com.repovoyage.sign.history.RoomSentenceCache
import com.repovoyage.sign.history.SentenceCache
import com.repovoyage.sign.history.SentenceDatabase
import com.repovoyage.sign.history.applyRetentionPolicy
import com.repovoyage.sign.language.LanguageProcessor
import com.repovoyage.sign.language.LanguageProcessorImpl
import com.repovoyage.sign.pipeline.CredentialLlmPolisher
import com.repovoyage.sign.pipeline.TranslationPipeline
import com.repovoyage.sign.recognition.CameraClipFeed
import com.repovoyage.sign.recognition.ClipRecognitionSource
import com.repovoyage.sign.recognition.DebugClipRetention
import com.repovoyage.sign.recognition.HttpClipTransport
import com.repovoyage.sign.recognition.LocalVideoComposeClient
import com.repovoyage.sign.recognition.LocalVideoCvClient
import com.repovoyage.sign.recognition.RecognitionSource
import com.repovoyage.sign.recognition.RecognitionSourceImpl
import com.repovoyage.sign.recognition.RoutingRecognitionSource
import com.repovoyage.sign.service.CameraBridgeForegroundService
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.tts.AndroidTtsSpeaker
import com.repovoyage.sign.tts.TtsManager
import com.repovoyage.sign.tts.TtsManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

class SignApp : Application() {

    /** 应用级单例容器（MVVM 的组合根；正式 DI 框架为 §3.2 遗留决策项） */
    val settings: AppSettings by lazy { AppSettings(this) }
    val database: SentenceDatabase by lazy { SentenceDatabase.create(this) }
    val sentenceCache: SentenceCache by lazy { RoomSentenceCache(database.sentenceDao()) }
    val ttsSpeaker: AndroidTtsSpeaker by lazy { AndroidTtsSpeaker(this) }
    val ttsManager: TtsManager by lazy { TtsManagerImpl(ttsSpeaker, appScope) }

    /** §2.7 震动（用户决定 2026-09-23）：仅 LLM 低置信结果触发 */
    val confirmationAlerter: ConfirmationAlerter by lazy { ConfirmationAlerter.create(this) }

    /** §2.4.7：相机会话中云端 LLM 走单独请求的蜂窝网络，不改绑整进程 */
    val cloudNetwork: CloudNetworkManager by lazy {
        CloudNetworkManager(
            ConnectivityManagerCloudNetworkProvider(getSystemService(ConnectivityManager::class.java)),
        )
    }

    val languageProcessor: LanguageProcessor by lazy {
        LanguageProcessorImpl(
            // §6.3：凭据运行时读取，发布包不内置密钥
            engine = CredentialLlmPolisher(
                credentials = { settings.llmCredentials.first() },
                // 蜂窝绑定客户端优先；未就绪回落进程默认网络（无相机会话时可用，
                // 相机会话中则失败降级 UNAVAILABLE——§2.4.7 第 5 条）
                clientProvider = { cloudNetwork.clientOrNull() ?: DEFAULT_LLM_CLIENT },
            ),
            scope = appScope,
        )
    }
    /**
     * 模型 B 切片识别源（P6 联调，2026-09-23）：固定窗口 MP4 段 → 词级 CV →
     * Agent 组句 → RecognitionUpdate。上传经 §2.4.7 蜂窝绑定客户端（相机
     * 在线时进程默认网络无公网），未就绪回落默认网络。
     */
    val clipSource: ClipRecognitionSource by lazy {
        val cellularClient: () -> OkHttpClient = { cloudNetwork.clientOrNull() ?: DEFAULT_LLM_CLIENT }
        ClipRecognitionSource(
            outputDir = File(cacheDir, "recognition_clips"),
            settings = settings,
            transport = HttpClipTransport(
                cvClient = LocalVideoCvClient(clientProvider = cellularClient),
                composeClient = LocalVideoComposeClient(clientProvider = cellularClient),
            ),
            feed = CameraClipFeed { CameraBridgeForegroundService.session as? SdkCameraSession },
            scope = appScope,
            acquireCellular = { cloudNetwork.acquire() },
            debugRetainDir = DebugClipRetention.dir(this),   // flavor 缝：production=null
        )
    }

    /** 识别源路由：模型 B → 切片识别；其余 → flavor 默认源（training 桩/production 不可用） */
    val recognitionRouting: RecognitionSource by lazy {
        RoutingRecognitionSource(settings, clipSource, RecognitionSourceImpl, appScope)
    }

    val pipeline: TranslationPipeline by lazy {
        TranslationPipeline(
            source = recognitionRouting,
            settings = settings,
            processor = languageProcessor,
            tts = ttsManager,
            cache = sentenceCache,
            scope = appScope,
            cloudNetwork = cloudNetwork,
            alerter = confirmationAlerter,
        )
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // ARCHITECTURE.md §2.1.2：按 Application 位置初始化（Demo 在 MainActivity
        // 权限通过后才 init，两者等价性在首个真机构建时验证）。
        // 日志级别用默认值：SDK 日志可能带相机信息，成品不开 verbose。
        InstaCameraSDK.init(this) {
            cacheDir = externalCacheDir?.absolutePath
        }
        // §2.6 保留策略：每次进程启动清理一次 90 天/万条超限；
        // 写入失败不影响实时链路（§6 错误矩阵），仅记日志。
        appScope.launch {
            runCatching {
                applyRetentionPolicy(database.sentenceDao(), System.currentTimeMillis())
                // 未配置 LLM 凭据 = 语言处理已静默跳过（TranslationPipeline），
                // 历史里残留的 UNAVAILABLE 行永不会再更新，属纯噪音，启动时清掉；
                // 日后若重新配置凭据则跳过本清理，保留真实失败标记
                if (!settings.llmCredentials.first().isConfigured) {
                    val purged = database.sentenceDao().deleteUnavailableResults()
                    if (purged > 0) Log.i(TAG, "purged $purged legacy UNAVAILABLE results")
                }
            }.onFailure { Log.w(TAG, "retention cleanup failed", it) }
        }
    }

    private companion object {
        const val TAG = "SignApp"
    }
}
