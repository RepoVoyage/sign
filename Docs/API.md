# GO 3S 手语翻译 — API 接口文档

> **状态：契约草案，随 ARCHITECTURE.md 修订同步** | 最后更新：2026-09-22
> 本文定义各模块边界的接口契约与训练版 USB 线协议。行为规则（超时处理、降级、资源释放顺序等）以 `ARCHITECTURE.md` 为准，两文冲突时以 ARCHITECTURE.md 为准。标注 **【初始值】** 的数值为第一轮保护性取值，须真机压测后调整并回写本文。

---

## 0. 总则与通用约定

### 0.1 时间与单位

| 时钟 | 单位 | 用途 |
|---|---|---|
| 相机媒体时间 `ptsUs` | 微秒（SDK 回调为毫秒，聚合后统一转 µs） | 帧对齐、段来源范围 `[startPtsUs, endPtsUs)` |
| 手机单调时钟 `monoMs` | 毫秒 | 队列年龄、超时、deadline、性能统计（`elapsedMs`） |
| 墙上时间 `wallMs` | 毫秒（epoch） | 仅历史记录展示，不参与时序判断 |

**禁止**把相机 timestamp 当 Unix 时间，禁止跨时钟比较。

### 0.2 标识符

| 标识 | 类型 | 语义 |
|---|---|---|
| `sessionId` | String（UUID） | 一次会话，从开始到用户停止/进程死亡 |
| `streamGeneration` | Long | 每次重连或重建解码链 +1；旧代次数据不得更新当前句子 |
| `sequenceEpoch` | Long | 识别序列代次；断流、缺帧、过载等连续性失效时 +1，自然换段**不**增加 |
| `segmentId` | String | 会话内唯一句子/段；草稿修改只增 `revision`，不换 ID |
| `revision` | Int | 段内容修订号；FINAL 冻结后迟到推理不得覆盖 |
| `utteranceId` | String | 每次实际 TTS 提交唯一，重播也用新 ID |

### 0.3 错误与返回约定

- 跨模块调用不抛业务异常，统一返回 sealed result 或 Flow 中的事件类型；崩溃性错误（OOM 等）直接失败并走 §6 错误矩阵。
- `confidence: Float?` —— `null` 表示模型无可比较置信度，不虚构分数。
- 区间一律前闭后开 `[start, end)`。
- 接口签名为**语义契约**：实现可调整命名/参数组织，但字段语义、去重键、失效规则不得偏离；改动须同步本文。

---

## 1. Camera Link — 会话与连接

### 1.1 会话状态（UI 观察的唯一状态源）

```kotlin
sealed interface SessionState {
    data object Idle : SessionState
    data object Checking : SessionState                                   // 权限/蓝牙/模式检查
    data class BleConnecting(val deviceName: String?) : SessionState
    data object WifiConnecting : SessionState                             // 系统流程 + bindProcessToNetwork
    data class Authorizing(val status: AuthStatus) : SessionState         // 等待相机授权/忙碌/拒绝
    data object Activating : SessionState                                 // 需公网，仅首次
    data object Preparing : SessionState                                  // loadJson/能力查询/参数集
    data class Streaming(val params: StreamParams) : SessionState
    data class Reconnecting(val attempt: Int, val nextRetryInMs: Long) : SessionState
    data object Stopping : SessionState
    data object PausedHot : SessionState
    data class Error(val reason: SessionError) : SessionState
}

data class StreamParams(
    val width: Int, val height: Int, val fps: Int,
    val encodeType: VideoEncodeType,        // H264 / H265，来自 fetchVideoEncodeType
    val streamGeneration: Long
)
```

### 1.2 命令与事件

```kotlin
interface CameraSession {
    val state: StateFlow<SessionState>
    val events: SharedFlow<SessionEvent>    // 见下
    suspend fun start(bleDevice: DiscoveredCamera)
    suspend fun stop()                      // 用户停止，优先于一切延迟重试
    suspend fun resumeAfterCooldown()       // PAUSED_HOT → 用户确认后
}

sealed interface SessionEvent {
    data class BatteryLow(val levelPercent: Int) : SessionEvent          // 震动 5 分钟限一次
    data class Overheat(val temperatureC: Int) : SessionEvent
    data class Disconnected(val cause: DisconnectCause) : SessionEvent   // 立即通知，进入 Reconnecting
    data class StreamOverload(val location: OverloadLocation) : SessionEvent  // 编码入口/聚合/解码，见 §2.4
    data class ReconnectFailed(val attempt: Int) : SessionEvent          // 第 5 次触发长震
}
```

重连退避【初始值】：1、2、4、8、16 秒，最多 5 次；成功出图后清零。

---

## 2. Video Processor — 聚合、解码与输出端

### 2.1 数据类型

```kotlin
/** SDK 回调入口；进入本层前已完成有界复制，取得所有权（§2.2.1-2） */
data class StreamChunk(
    val data: ByteArray,
    val timestampMs: Long,               // 相机时钟，同帧分片共享
    val type: PreviewStreamType,         // 仅 VIDEO 类型进入聚合
    val receivedAtMonoMs: Long,
    val streamGeneration: Long
)

/** timestamp 切换提交的聚合帧；未确认完整的尾帧丢弃 */
data class EncodedFrame(
    val data: ByteArray,
    val ptsUs: Long,                     // ms → µs
    val isSyncPoint: Boolean,            // 仅经验证的随机访问帧（H.264 IDR 等）
    val streamGeneration: Long
)

/** 解码输出；应用拥有副本，codec 缓冲已释放 */
data class DecodedFrame(
    val planes: List<FramePlane>,
    val width: Int, val height: Int,
    val crop: Rect,
    val pixelFormat: PixelLayout,        // 实测布局（如 I420 半平面/紧凑），不假定 NV12
    val ptsUs: Long,
    val streamGeneration: Long
)
data class FramePlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)
```

### 2.2 输出端契约（flavor 注入，运行时无开关）

```kotlin
interface DecodedFrameSink {             // 共用代码只面向此接口
    fun onFrame(frame: DecodedFrame)
    fun onGap(event: FrameGapEvent)      // 缺帧/解码重建，触发 sequenceEpoch 处理
    fun onOverload(location: OverloadLocation)
}
// production: ModelInputAdapter（采样+预处理 → SignRecognizer）
// training:   UsbCaptureAdapter（像素转换 → USB 发送池）
```

### 2.3 有界队列容量【初始值】（ARCHITECTURE.md §2.2.5）

| 位置 | 容量/时限 | 超限 |
|---|---|---|
| 编码分片入口 | 128 片 / 8 MiB / 最老 250ms | 报告一次过载并重新同步 |
| 当前帧聚合 | 4 MiB / 同 timestamp 500ms | 丢弃并重新同步 |
| 待解码完整帧 | 4 帧 / 8 MiB / 250ms | 清空解码链重新同步 |
| 待转换图像 | 应用持有 2 帧 | 按采样契约跳过或发缺帧事件 |
| 采样帧/在途张量 | 环形区 `sequenceLength+2`，合计 64 MiB | 装不下拒绝启动该规格 |
| 推理 | 1 执行 + 1 待执行 | 段中断，重开新序列 |
| 语言处理 | 1 执行 + 2 待处理 | 转显式未处理/降级状态 |

---

## 3. ModelSpec / CaptureSpec — 模型与采集契约

随模型发布，JSON 随包；字段**训练后锁定**，当前不填数值。

```kotlin
data class ModelSpec(
    val modelVersion: String, val checksum: String,     // PC 训练与手机使用同一套规则
    val inputKind: InputKind,                            // RGB_VIDEO / KEYPOINTS / 组合
    val inputWidth: Int, val inputHeight: Int,
    val channelOrder: ChannelOrder, val tensorLayout: TensorLayout,
    val dtype: DType, val normalization: NormalizationParams,
    val inputFps: Int, val sequenceLength: Int, val windowStride: Int,
    val maxFrameGapUs: Long, val missingFramePolicy: MissingFramePolicy,   // 允许的重采样方式
    val cropAndOrientation: CropSpec, val mirror: Boolean, val denoise: DenoisePolicy?,  // 与训练一致
    val outputKind: OutputKind,                          // 标签序列/词序列/句子 + 时间区间
    val boundaryContract: BoundaryContract,              // 见 §4.2
    val confidenceCalibration: ConfidenceCalibration,    // 阈值来自验证集
    val runtime: RuntimeSpec                              // TFLite/ONNX、线程/后端、内存
)
```

`CaptureSpec`（训练版专用）另含：`captureFps`、像素格式（紧凑 I420/RGB）、`preprocessVersion`、连续样本与真实时间戳记录方式。同一帧不得在两 spec 间复用转换结果。

---

## 4. Sign Recognizer（仅 production）

### 4.1 接口

```kotlin
interface SignRecognizer {
    val spec: ModelSpec
    /** 按媒体时间连续提交采样帧；超过 maxFrameGapUs 由调用方发 Gap，不在此插值 */
    fun submitFrame(frame: SampledFrame)
    /** 新 sequenceEpoch：旧推理返回后丢弃结果，不并发争用实例 */
    fun resetSequence(reason: SequenceResetReason)
    val updates: Flow<RecognitionUpdate>
}

data class RecognitionUpdate(
    val sequenceEpoch: Long,
    val segmentId: String?,               // 无既有段归属时为 null（开新段）
    val draftText: String,                // 去重标签后的草稿（同一 segment 修订）
    val tokenSpans: List<TokenSpan>?,     // 模型支持 token 时间区间时非 null
    val confidence: Float?,
    val boundary: BoundarySignal?
)

data class TokenSpan(val text: String, val startPtsUs: Long, val endPtsUs: Long, val stable: Boolean)
```

### 4.2 自动边界信号（发布门槛）

```kotlin
data class BoundarySignal(
    val cutoffPtsUs: Long,                    // 来源媒体截止点（经时钟映射，非手机时钟）
    val requiredFutureContextUs: Long,        // 截止点后仍需的上下文
    val reliability: BoundaryReliability,     // RELIABLE / UNCERTAIN
    val source: BoundarySource                // 模型信号/配套时序模块
)
```

- `RELIABLE` → SentenceManager 进入 FINALIZING；`UNCERTAIN` → 该范围转待核对，不阻塞后续表达。
- 无经验证边界能力的模型**不得**接入连续翻译发布路径；不得用固定静默时长或 LLM 补标点替代。

---

## 5. Sentence Manager

### 5.1 段状态与事件

```kotlin
enum class SegmentState { DRAFT, FINALIZING, FINAL, NEEDS_CONFIRMATION, INTERRUPTED, DISCARDED }
```

```kotlin
sealed interface SentenceEvent {
    data class DraftUpdated(val segmentId: String, val revision: Int, val draftText: String) : SentenceEvent
    data class Finalizing(val segmentId: String) : SentenceEvent            // 后台收尾，等待【初始值】2s
    data class Final(val sentence: ConfirmedSentence) : SentenceEvent       // 冻结原文与来源范围
    data class NeedsConfirmation(val segmentId: String, val reason: ConfirmReason) : SentenceEvent
    data class Interrupted(val segmentId: String, val cause: InterruptCause) : SentenceEvent  // 不自动播报
    data class Discarded(val segmentId: String) : SentenceEvent             // 用户放弃待核对项
}
```

### 5.2 数据契约（与 ARCHITECTURE.md §2.4.3 一致）

```kotlin
@JvmInline value class LangCode(val tag: String)

data class ConfirmedSentence(
    val sessionId: String,
    val streamGeneration: Long,
    val sequenceEpoch: Long,
    val segmentId: String,
    val revision: Int,
    val startPtsUs: Long,                 // 前闭后开
    val endPtsUs: Long,
    val rawChinese: String,               // FINAL 冻结文本，LLM 唯一合法输入
    val confidence: Float?,               // null = 模型无置信度
    val userConfirmed: Boolean            // 仅记录是否经用户核对，自动确认可为 false
)
```

规则：重叠窗口更新同段草稿（增 revision）；断线/停止/设置变更使未生效请求过期，迟到结果丢弃；`INTERRUPTED` 段不自动播报，可补字幕。

---

## 6. Language Processing — LLM 整理与翻译

### 6.1 领域接口（与 ARCHITECTURE.md §2.4.3 一致）

```kotlin
enum class LanguageBackend { LOCAL, CLOUD }
enum class OutputSource { LOCAL, CLOUD, FALLBACK, USER }   // USER = 人工核对修正（仅纠错路径产生，2026-09-23）
enum class OutputStatus { READY, NEEDS_CONFIRMATION, UNAVAILABLE }

data class OutputPreferences(
    val selectedLanguages: List<LangCode>,    // 默认 [zh-CN]
    val spokenLanguages: List<LangCode>,      // 必须是 selectedLanguages 子集
    val backend: LanguageBackend,
    val revision: Long                        // 设置快照版本；变更取消旧任务并拒绝旧结果
)

data class LanguageResult(
    val sessionId: String, val streamGeneration: Long, val sequenceEpoch: Long,
    val segmentId: String, val sentenceRevision: Int, val settingsRevision: Long,
    val language: LangCode,
    val text: String?,                        // UNAVAILABLE 时为 null，不得用中文冒充外语译文
    val status: OutputStatus,
    val source: OutputSource,
    val elapsedMs: Long                       // 手机单调时钟，含排队
)

interface LanguageProcessor {
    fun process(sentence: ConfirmedSentence, preferences: OutputPreferences): Flow<LanguageResult>
}
```

### 6.2 内部引擎接口

```kotlin
/** LOCAL / CLOUD 两个实现共享的引擎契约 */
interface LlmPolisher {
    /**
     * 只整理 rawChinese（语序、虚词、标点），并按 targetLanguages 翻译。
     * 禁止新增事实；否定/数字/专名/有意重复必须保留（ARCHITECTURE.md §2.4.5）。
     * deadline 到达必须返回未完成标记，不得无限等待。
     */
    suspend fun polish(input: PolishInput, deadlineMonoMs: Long): PolishOutput
}

data class PolishInput(
    // 段身份：云端 HTTP 契约要求随请求发送并回显校验（agent/docs/API.md §2）
    val sessionId: String, val segmentId: String, val revision: Int,
    val rawChinese: String,
    val targetLanguages: List<LangCode>,      // 仅用户选定的语言；仅中文时不含外语
    val contextSentences: List<ConfirmedSentence>  // 已确认句子的受限衔接上下文，最近优先最多 5 条
)

data class PolishOutput(
    val polishedChinese: String?,             // 中文整理结果
    val translations: Map<LangCode, String>,  // 按需翻译
    val issues: List<FidelityIssue>,          // 歧义/关键语义变化/校验失败 → 该语言转 NEEDS_CONFIRMATION，不自动 TTS
    val source: OutputSource, val elapsedMs: Long
)
```

去重键：同一 `(sessionId, segmentId, revision, language)` 只保留一个有效结果。

期限【初始值】：本地每句 3 秒、云端 10 秒（从 FINAL 起算，含排队与网络）；多语言共享该期限，已返回语言不回滚，超时语言标 `UNAVAILABLE`。云端失败仅在本地就绪且剩余期限允许时降级。

### 6.3 云端直连契约（Chat Completions，`DirectLlmPolisher`）

App 直连云端 LLM（**中间代理服务已按用户决定移除**，agent 源码保留在 main 分支）：

- `POST {LLM_BASE_URL}/chat/completions`，`Authorization: Bearer <LLM_API_KEY>`；
  `messages` = 保真 system 提示词 + user（`rawChinese/targetLanguages/context` 简化 JSON）
- 响应取 `choices[0].message.content`（JSON：`polishedChinese/translations/issues`）
- 客户端 guard 保守启发式（数字/否定/有分隔重复变化、中文冒充外语拦截、未请求语言校验）；不构成跨语言语义正确性证明
- 上游错误按 HTTP 状态映射（401/403→`MODEL_AUTH_FAILED` 不可重试；429/超时/网络→可重试）；期限内不自动重试
- 凭据**运行时配置**（App 设置项，用户自持，排除云备份；不写日志）——发布包不内置密钥（§6.3 原约束保持）

---

## 7. TTS Manager

```kotlin
data class SpeakRequest(
    val sessionId: String,
    val segmentId: String,
    val language: LangCode,               // 必须有已验证的离线 Voice
    val text: String,                     // 仅 READY 文本；DRAFT/FINALIZING/待核对/中断不播
    val orderKey: Long                    // 源媒体时间序，按表达顺序播报，不抢播
)

sealed interface EnqueueResult {
    data object Queued : EnqueueResult
    data object Duplicate : EnqueueResult          // 自动播报键 (sessionId, segmentId, language) 已存在
    data class Rejected(val reason: RejectReason) : EnqueueResult  // 容量满/语音未就绪
}

interface TtsManager {
    fun enqueue(request: SpeakRequest): EnqueueResult
    fun stopCurrentAndClearQueue()               // 仅用户停止/关声音/显式重播
    fun replay(sessionId: String, segmentId: String, language: LangCode)  // 用户显式重播，新 utteranceId
    val events: Flow<TtsEvent>
}

sealed interface TtsEvent {
    data class Started(val utteranceId: String) : TtsEvent
    data class Finished(val utteranceId: String) : TtsEvent
    data class Failed(val utteranceId: String, val reason: TtsError) : TtsEvent
    data class MarkedUnspoken(val segmentId: String, val language: LangCode) : TtsEvent  // 超期转字幕"未播报"
}
```

【初始值】：串行队列一次提交一条；待播缓存 3 句；进入待播 5 秒未开始 → `MarkedUnspoken`；引擎看门狗 60 秒。`speak()` 同步 ERROR、`onDone/onError/onStop` 走同一幂等终结，只处理匹配当前 `utteranceId` 的事件；迟到回调不推进新任务。正常新句不用 `QUEUE_FLUSH`。

---

## 8. 文本缓存（Room，默认开启、可关闭）

```kotlin
@Entity(tableName = "sentences", primaryKeys = ["sessionId", "segmentId"])
data class SentenceRecord(
    val sessionId: String, val segmentId: String,
    val streamGeneration: Long, val sequenceEpoch: Long, val revision: Int,
    val startPtsUs: Long, val endPtsUs: Long,
    val wallTimeStart: Long, val wallTimeEnd: Long,      // 展示用墙上时间
    val rawChinese: String, val confidence: Float?,
    val userConfirmed: Boolean,
    val modelVersion: String, val specChecksum: String
)

@Entity(tableName = "language_results",
        primaryKeys = ["sessionId", "segmentId", "language"],
        foreignKeys = [/* → sentences */])
data class LanguageResultRecord(
    val sessionId: String, val segmentId: String, val language: LangCode,
    val sentenceRevision: Int, val settingsRevision: Long,
    val text: String?, val status: String, val source: String,  // 未完成/歧义不得伪装正常译文
    val processedAtWallMs: Long
)

interface SentenceCache {
    suspend fun upsertSentence(record: SentenceRecord)
    suspend fun mergeLanguageResult(record: LanguageResultRecord)   // 同 (sessionId, segmentId) 按语言合并，不存草稿版本
    fun observeHistory(...): Flow<List<SentenceWithResults>>
    suspend fun deleteBySegment / deleteBySession / deleteAll()
    suspend fun exportJson / exportCsv(...)            // 用户主动触发，系统分享机制；CSV 处理公式前缀
}
```

保留策略【初始值】：最近 90 天且 ≤10000 条，任一超限清理。缓存关闭后停止写入，已存记录由用户处置。写入失败不影响实时字幕。

---

## 9. USB Bridge 线协议（仅 training flavor）

### 9.1 连接模型

- 手机 `ServerSocket` 仅监听 `127.0.0.1:9999`；PC 经 `adb forward tcp:9999 tcp:9999` 连接。多手机各自指定序列号与端口。
- 单客户端；PC 首条消息必须为 `AUTH_REQ`，连接接受后 **5 秒**内完成整个认证，否则关闭并恢复可连接状态。
- 双方空闲每 **2 秒**发 `HEARTBEAT`，**6 秒**未收到任何完整有效消息即断开。
- PC 直接关闭连接视作断连：手机中断当前采集段，PC 将样本标记不完整。

### 9.2 消息分帧（双向一致）

```text
+---------------------+----------------------+---------------------------+
| uint32 大端 headerLen | UTF-8 JSON header     | payload, header.payloadLen |
+---------------------+----------------------+---------------------------+
```

- 接收端**循环读满**；收到首字节后 **2 秒**内须读满整条消息（整条消息一个截止时间，不按小片续期）；长度校验失败即断开。
- 上限【初始值】：JSON header ≤ 16 KiB；图像 payload ≤ 32 MiB；控制消息 payload ≤ 64 KiB。

Header 公共字段：`"proto"`（整数协议版本，当前为 1，不兼容变更时递增）、`"type"`、`"sessionId"`（AUTH 除外）。

### 9.3 消息类型

| type | 方向 | payload | 说明 |
|---|---|---|---|
| `AUTH_REQ` | PC→手机 | 无 | `{"token":"…","clientInfo":"pc-training/x.y"}`；token 为训练版显示的临时配对令牌，不入日志 |
| `AUTH_RESULT` | 手机→PC | 无 | `{"ok":true,"sessionId":"…"}` / `{"ok":false,"reason":"BAD_TOKEN\|AUTH_TIMEOUT"}` |
| `SESSION_CONFIG` | 手机→PC | 无 | `{"captureSpecVersion":"…","pixelFormat":"I420\|RGB888","width":…,"height":…,"captureFps":…,"bufferTargetMs":2000,"maxPayloadBytes":…,"preprocessVersion":"…","cameraModel":"…","cameraFirmware":"…"}`（相机字段供 §2.8.3 素材元数据，连接前未知可省略） |
| `CONFIG_ACK` | PC→手机 | 无 | `{"accepted":true}`；false 或超时即关闭。协商结果不得超过 §9.2 上限 |
| `FRAME` | 手机→PC | 紧凑像素 | header 见下 |
| `GAP_EVENT` | 手机→PC | 无 | 缺帧/编码恢复/重连/过载中断，PC 不将前后帧拼连续样本 |
| `END` | 手机→PC | 无 | 正常停止收尾后发送；异常路径尽力发送，PC 未收到即按不完整处理 |
| `HEARTBEAT` | 双向 | 无 | `{"t":<单调毫秒>}` |

`FRAME` header：

```json
{
  "type": "FRAME", "proto": 1, "sessionId": "…",
  "streamGeneration": 7, "frameIndex": 1234,
  "ptsUs": 180123456, "ptsUnit": "us",
  "width": 1920, "height": 1080,
  "pixelFormat": "I420", "preprocessVersion": "…",
  "payloadLen": 3110400
}
```

PC 侧校验：声明长度与尺寸×格式计算值一致，拒绝越界/未知格式；序号连续性在 END 时核对。

`GAP_EVENT`：`{"type":"GAP_EVENT","reason":"LOST_FRAMES|DECODE_RESET|RECONNECT|OVERLOAD","lastFrameIndex":…,"startPtsUs":…,"endPtsUs":…}`

`END`：`{"type":"END","status":"COMPLETE|INCOMPLETE","reason":"…","lastFrameIndex":…,"validStartPtsUs":…,"validEndPtsUs":…}`；任一丢失、错误或收尾超时不得发 `COMPLETE`。

### 9.4 背压与生命周期【初始值】

- 发送池预算 128 MiB【已定案（2026-09-23 真机实测：640×384@30 I420 满帧率 60 秒、10.5 MiB/s、零过载；256/64 档仅在采集规格变更时启用）】，采集段开始前确定，运行中不扩容；覆盖转换中/待发/在途/空闲块及元数据。
- 缓冲目标约 2 秒：帧数上限 `min(ceil(captureFps×2), 预算可容纳数)`；帧进入发送阶段到完整写出 ≤ 2 秒（单调时钟，不因零星写出续期）。
- 入队超限、最老帧超时或连续 1 秒写入无进展 → 中断当前采集段并结束连接；不阻塞相机回调、不静默丢帧。
- 用户正常停止：停止纳帧 → 既有任务 2 秒内收尾（不重置各帧原有截止）→ 发 END → 关连接。恢复须新开采集段，新旧积压不拼接。

---

## 10. 预留与待定项

| 项 | 状态 | 依据 |
|---|---|---|
| `ModelSpec` 全部数值 | 训练后锁定 | ARCHITECTURE.md §2.2.4 |
| 句长/token 预算、文本拆分合并、来源映射（若语言处理单元 ≠ 识别段） | 预留，确定后扩充 §5/§6 接口 | §2.4.8 |
| 云端 HTTP 契约与凭据交付 | 启用云端前单独验收 | §2.4.6 |
| 各【初始值】（队列/期限/缓存/看门狗/USB 预算） | 真机压测后调整并回写 | §2.2.5、§2.5、§2.8.2 |
| 自动边界算法与阈值 | 随模型验证，无验证不发布连续翻译 | §2.4.2 |
