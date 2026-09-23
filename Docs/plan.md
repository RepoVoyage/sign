# GO 3S 手语翻译 — 执行计划

> **状态：草案 v1** | 2026-09-22
> 行为规范见 `ARCHITECTURE.md`（冲突时以其为准），接口契约见 `API.md`。本文只回答"按什么顺序做、每步怎么算完成"。

---

## 0. 现状盘点（2026-09-22）

### 已完成

| 项 | 状态 |
|---|---|
| 架构设计 | ✅ `ARCHITECTURE.md`（修订稿，数值待实测回填） |
| 接口契约 | ✅ `API.md`（模块接口 + USB 线协议 v1） |
| 开发环境 | ✅ Android Studio Quail 4 Patch 1、SDK platform-tools/adb 可用 |
| 第 0 周门禁·连接 | ✅ 实测通过（BLE 扫描→Wi-Fi 按钮→系统热点确认→连接成功） |

### 已实测的相机/流事实

| 事实 | 来源 |
|---|---|
| 相机已激活（无需再走激活） | GetOptions `activate_time` 有值 |
| 取流默认：H.264、30fps、主流 640×360 + 副流 640×480、约 4Mbps、无音频、带 gyro | `startStream` 日志 |
| 实际编码尺寸 640×384（5:3 鱼眼），声明值 ≠ 实际值 | 解码器 ImageData 日志 |
| CSD 61 字节随流发送；首帧开流后约 2s 出图 | 同上 |
| `startStream()` 无参，分辨率 SDK 内部定；`startLive(CameraLiveParams)` 可指定分辨率但走 RTMP 路径 | SDK dex 接口面 |
| **高分辨率取流可行**：`VIDEO_LIVE` 模式主流跟随 `video_resolution`，实测 3840×1920@30 开流成功；1080p 只需设 `video_resolution` + live 模式开流，无需真推 RTMP | P0 推流实测 |
| Demo"预览分辨率 1080P"选项只改渲染尺寸，不改相机流 | 实测（无流重启） |
| 错误码 -214 = 约 5s 连接超时（典型原因：手机不在相机网络） | 实测 |
| 电池温度开流期间 53°C 且上升 | GetOptions |
| Demo 日志明文打印热点 SSID/密码（我们 App 必须避免） | 实测 |
| MIUI 会冻结后台 Demo 进程（Greeze） | logcat |

### 未验证/未完成

- ~~1080p（及以上）取流是否可行~~ ✅ 已验证可行（`VIDEO_LIVE` + `video_resolution`，实测 4K 开流成功）
- ~~持续取流稳定性、断连重连表现~~ ✅ P2/P3 真机矩阵完成；温度观察按用户决定移除（2026-09-23，归因见 P3 真机发现 5）
- ~~自研工程一行代码未写~~ ✅ P1–P3 已落地（骨架/连接/取流+解码，单测 105 条 0 红）

---

## 1. 阶段划分

### P0 门禁收尾——**当前所处阶段**

| 任务 | 完成标准 |
|---|---|
| ~~1080p 取流验证~~ | ✅ 已完成：`VIDEO_LIVE` 模式实测 4K（3840×1920@30）开流成功，分辨率上限风险解除（注意：测试推流用的 YouTube stream key 已入日志，待重置） |
| 30 分钟持续取流观察 | 记录掉帧/掉流次数、温度曲线、是否触发过热保护 |
| 手动断连重连 3 次（关相机/走远） | 记录 Demo 的恢复表现（这决定我们重连设计的兜底强度） |
| ~~结论回写文档~~ | ✅ 已回写 `ARCHITECTURE.md` §8（P0 实测记录表） |

**产出**：分辨率结论（直接决定 ModelSpec 输入预算与 USB 吞吐设计）。

### P1 工程骨架

| 任务 | 完成标准 |
|---|---|
| ~~Gradle 工程：锁定 AGP 8.7.3 / Gradle 8.11.1 / JDK 17 / Kotlin 2.3.20 / minSdk 29 / compileSdk 35 / arm64-v8a~~ | ✅ 两 flavor debug + productionRelease 均构建通过（2026-09-22） |
| ~~Maven 凭据本地注入（不进仓库）~~ | ✅ 凭据走 `~/.gradle/gradle.properties`，仓库零凭据 |
| ~~空壳：Application、前台服务声明、权限清单按 Demo 裁剪~~ | ✅ Manifest 合并通过（allowBackup 冲突已用 tools:replace 解决）；单元测试 p1 全绿 |
| 装机验证 | ⏳ 用户决定推迟：P2 首次装机时顺带补验（2026-09-22） |

### P2 相机连接模块

| 任务 | 完成标准 |
|---|---|
| `CameraSession` 状态机 + BLE→AP→热点→绑网→connect 全流程（API.md §1） | 真机连接成功率 ≥ 连续 10 次成功 |
| 断线重连（1/2/4/8/16s × 5）+ 通知/震动 | 拔电池/超距场景按矩阵恢复或明确失败 |
| 权限拒绝路径、停止清理顺序（§2.1.3） | 杀进程/锁屏/切后台各场景不泄漏、不重叠会话 |

**进度（2026-09-23，真机矩阵完成）**：状态机/退避策略/连接链路（`SdkCameraSession`，
照 Demo ConnectionViewModel 抄录）+ SessionEvent 广播 + FGS 持有会话 + 验证 UI 已落地，
单测 82 条（37 绿 / 45 分阶段 @Ignore / 0 红）。真机验收：连续 10 次连接 10/10、
短暂断电退避自愈（1/2/4/8/16s 第 5 次恢复）、5 次退避耗尽→Error+ReconnectFailed、
Error→用户重连恢复。真机驱动修正 4 处：初连瞬时错误自动重试（相机休眠唤醒失败/
快速重连 GATT 133）、SDK BLE connect 挂死加 60s 尝试级 watchdog（实测挂死 108s）、
系统热点连接 10s 超时（系统对不可用网络可拖 37s）、重试预算进 Checking 即重置
（不跨会话残留）。

**真机发现（P3 输入与修正项）**：
1. **相机在"已连接但无取流"空闲态约 1 分钟自动休眠**（WiFi 心跳超时断连）——
   P3 必须在连接完成后立即开流，否则连接无法维持；30 分钟持续取流观察随 P3 做。
2. SDK 默认日志会把热点密码明文打进 logcat——**production 构建必须调高
   InstaCameraConfig.logLevel**。
3. MIUI 杀进程问题按用户决定不纳入范围（2026-09-23）。

**连接灵敏度修正（2026-09-23，用户实测痛点）**：分段计时日志定位到用户
"难以一次连接"的根因——进程残留的旧热点关联拆除/冷扫描会让首次
`requestNetwork` 拖满 10s 超时，而失败重试走**整链路重跑**（BLE 释放→重连
正好撞 GATT 快速重连瞬断窗口），实测一次点击内部失败 2 次、24s 才 Streaming。
修正：`connectSystemWifiWithRetry`（同一尝试内局部重试 3 次 × 间隔 1s，
BLE/AP 模式不重跑不释放）。修正后矩阵 **16/16 一次点击成功**：暖态
（停止→5s→重连）10 轮中位 5.1s；force-stop 流式中杀进程脏状态 3 轮 ~5.8s
零重试（原必失败路径）；冷态（相机休眠 100–160s BLE 唤醒）2 轮 5.0s/15.7s，
后者命中局部重试即恢复。另发现相机侧 `error=1002` 强制断连（旧 Wi-Fi 会话
残留时相机拒绝新会话）1 次，既有自动重试 1 轮 7s 恢复，无需处理。

**已知风险**：MIUI 冻结后台进程（Greeze）——按用户决定不关注。

### P3 取流 + 解码——**App 侧最高技术风险**

| 任务 | 完成标准 |
|---|---|
| ~~方式二：`onStreamDataNotify` → 同 timestamp 分片聚合 → timestamp 切换提交~~ | ✅ 满帧率解码 + 满帧率帧计数即帧边界正确的证据（边界错则硬解必然花屏/丢帧） |
| ~~MediaCodec 无 Surface 解码 + `getOutputImage` 布局读取/复制~~ | ✅ 30fps 满帧率、c2.qti.avc.decoder、实际 640×384 |
| ~~参数集/关键帧准备、重同步、`streamGeneration`~~ | ✅ 断电重连 gen2 干净重门控恢复，旧代次零串扰 |
| ~~有界队列 + 过载处理（§2.2.5 初始值）~~ | ✅ 单测注入慢消费覆盖（过载单次报告/丢弃/恢复）；真机自然负载未触发过载 |
| ~~时间戳体系（µs 媒体时间 / 单调时钟分离）~~ | ✅ 单元测试覆盖 |

**进度（2026-09-23，取流+解码链真机验证通过）**：`FrameAssembler`（8 条契约单测）
+ `ChunkIngestQueue`（过载单次报告/丢弃/恢复 6 条单测）+ `SdkCameraSession` 开流接线
（连接后立即开流 → 入口队列 → 聚合 → `getPreviewParams` 查询进 Streaming）。
解码链：`H264DecodePrep`（Annex-B NAL 扫描、SPS/PPS→CSD、参数集齐全+IDR 才标记
isSyncPoint、纯参数集/SEI 帧吸收、代次重置，7 条单测）→ `DecodeFrameQueue`（§2.2.5
表 3 有界队列，6 条单测）→ `DecodeSyncGate`（每代次从首个随机访问帧起投喂，
5 条单测）→ `SurfacelessH264Decoder`（无 Surface configure + `getOutputImage`
平面复制）→ `DecodedFrame/FramePlane/PixelLayout` 契约类型（API.md §2.1）。
单测合计 105 条 0 红、38 条分阶段 @Ignore。

真机实测（小米 2510DRK44C，c2.qti.avc.decoder 硬解）：
- **满帧率解码**：解码 30.0 fps 与取流一致；实际解码尺寸 **640×384**（声明
  1920×1080 只是 preview 渲染尺寸，SPS 实际为 5:3 鱼眼 640×384）
- **断电重连不花屏**：Disconnected 事件触发 → 退避重连成功 → gen2 干净重门控
  （起步损失 ~9 帧/300ms），恢复后 30 fps 满帧率；旧代次数据零串扰
- **零过载、零解码错误**；解码起步 ~9 帧为 IDR 前不可解码帧 + decoder 冷启动，
  属压缩域本质约束（P6 以 `onGap`/sequenceEpoch 消化），不做额外优化
- 时间戳体系：ptsUs（媒体时间）与 mono 时钟（排队时长/账目）分离，队列单测覆盖

真机驱动修正：**GO 3S 不触发 `onParamsChanged`**（全程零回调），
改为 `onOpened` 后轮询 `getPreviewParams()`（返回 `Result<PreviewParams>`，
Result 为 value class 导致方法名混淆）。

**真机发现（P3 输入）**：
1. P0 推流残留的 `video_resolution=4K` 会让相机持续做无用 4K 编码，电池温度冲到
   57°C 后相机侧挂死（冻屏、ERR_SOCKET_READ 强制断连）——已用 Demo App 复位回
   1080p；后续 App 内如需改分辨率须走 setOptions 并记录。
2. **GOP 超长（>1000 帧）**：4.5 分钟会话仅 2 个自然 IDR；随机访问完全依赖开流
   首帧（CSD+IDR 随流）与 `requestStreamIframe()`——重同步必须主动请求关键帧，
   不能干等下一个 IDR。
3. 4K 档实测收到的 VIDEO 流仍是低分辨率流（~9KB/帧与 4Mbps 档吻合）——已核实：
   VIDEO_NORMAL 模式下声明 preview=1920×1080 为渲染尺寸，实际编码主流即 640×384。
4. SDK 噪声：BLE 释放后 CommandExeManager 残留重试，每 ~2s 刷一条 FastBle
   write error 日志，不影响 Wi-Fi 取流（后续若需静默须在 App 侧规避）。
5. 30 分钟持续取流观察与温度曲线按用户决定移除（2026-09-23）：过热风险已归因于
   4K 编码残留并解除；当前低负载主流（~4Mbps）下会话稳定性以零断连/零过载验收。

### P4 训练采集通道（可与 P5 并行）

| 任务 | 完成标准 |
|---|---|
| ~~`UsbBridgeService` + 线协议 v1（API.md §9）~~ | ✅ 真机实测：60 秒 1799 帧满帧率（30.0 fps）、10.5 MiB/s（理论 11.1）、零 GAP 零缺帧；**预算档位定案 128 MiB**（2026-09-23） |
| ~~PC Python 客户端（uv 管理）~~ | ✅ 真机联调通过：AUTH/心跳/收帧落盘/完整性校验，END COMPLETE（792 帧）与 --duration 主动停路径实测；模拟手机侧 7 用例联调（2026-09-23） |
| ~~采集授权流程与素材元数据~~ | ✅ 授权四要素+删除范围缺一不连接不落盘（退出码 5）；`authorization.json` 记录受试者/授权关联/访问者/保留期限（到期日）/删除范围/相机型号固件/机位/CaptureSpec/标签；真机复验通过（2026-09-23） |

**进度（2026-09-23，P4 全部完成——吞吐联调+授权流程真机验证通过）**：
授权流程（§2.8.3）：PC 客户端授权四要素+删除范围（`--subject/--consent-ref/
--access/--retention/--delete-scope`）缺一即拒绝连接与落盘（退出码 5）；
`SESSION_CONFIG` 增加 `cameraModel/cameraFirmware`（连接时快照，
`Insta360 GO 3S`/`v9.0.59` 真机取值验证）；段目录落 `authorization.json`
（受试者/授权关联/访问者/保留期限含到期日/删除范围/相机/机位/CaptureSpec/
标签，`modelVersion` 留空待训练后回填），summary.json 记标签起止墙钟与
授权引用；通知文案写明用途与去向（受采集者知情）。通知/退出码/元数据
真机复验通过；PC 侧 10 用例联调全过。
协议栈 `FrameCodec`（分帧/两段截止）
→ `BridgeSession`（握手/心跳/超时状态机）→ `FrameSendPool`（§9.4 背压账目）
→ `I420.compact`（设备相关布局归一）→ `UsbBridgeServer`（accept/reader/pump 三
线程，单写者串行写出，新连接重置采集段）→ `DecodedFrameSink`（§2.2 契约，
解码线程回调）→ `UsbCaptureService`（training FGS，配对令牌仅显示于通知栏）
→ `CaptureEntry` flavor 缝（production 编译期无采集代码，`compileProductionDebugKotlin`
通过）。PC 侧 `pc/csl_capture.py`（uv，纯标准库）：AUTH → SESSION_CONFIG 校验
（仅 I420）→ select 主循环（2s 心跳/6s 静默断开）→ 逐帧校验 payloadLen×尺寸
落盘（frames.i420 + meta.jsonl 索引 + summary.json）→ END 时核对序号连续性，
COMPLETE 但有缺口按协议违规上报。单测 145 条 0 红、38 条分阶段 @Ignore。

真机实测（小米 2510DRK44C ← GO 3S 640×384@30 H264 → adb forward）：
- **吞吐**：60 秒 1799 帧（632.5 MiB）、30.0 fps 满帧率、10.5 MiB/s 持续
  （640×384 I420 理论 11.1 MiB/s）；128 MiB 池 ≈ 12 秒积压 ≫ 2 秒缓冲目标，
  **128 MiB 档定案**（256/64 档仅在规格变更时启用）
- **素材完整性**：序号 0..1798 连续、offset/len 与 640×384×1.5 一致、pts 单调
  （中位间隔 33000µs）；首帧 I420→RGB 抽查为真实画面
- **END COMPLETE 路径**：手机 UI「停止采集服务」→ END COMPLETE，PC 退出码 0
  （792 帧段）；`--duration` 主动停 → CLIENT_STOP（退出码 0，素材保留）
- 联调修掉一个 spec 偏差：`AUTH_RESULT` 缺 `proto` 公共字段（§9.2）——PC 严格
  校验揭穿，手机端补齐并加单测断言
- 采集素材目录示例：`<out>/<时间戳>_<sessionId>/{frames.i420, meta.jsonl,
  summary.json}`（段间不拼接，GAP 后素材标不完整）

### P5 训练与模型（外部依赖，**最早启动、最晚交付**）

不阻塞 P1–P4，但决定最终发布能力：

- 数据采集 → 训练 → 连续识别 + 自动边界 → `ModelSpec` 定稿
- **发布门槛**：自动边界未经验证 → 不发布连续翻译（不加逐句按钮兜底，见 ARCHITECTURE.md §9）
- PC/Android 预处理一致性验证（张量容差比对）

### P6 识别与句子管理（依赖 ModelSpec 草案）

- ~~`SentenceManager` 状态机（API.md §5）~~ ✅ 纯同步领域对象 + 10 条契约测试全绿（2026-09-23，`7f74720`）：重叠窗口同段增 revision、epoch 前进中断旧段、迟到提交/收尾丢弃、RELIABLE→FINALIZING / UNCERTAIN→待核对不阻塞、finalize 冻结、discard
- `SignRecognizer` 适配器（API.md §4）**推迟**：依赖 `ModelSpec` 子类型与数值（§3，训练后锁定），P5 未启动前拟草案无消费者——待 P5 ModelSpec 草案就绪后实现（2026-09-23 用户决定跳过）
- 验收：重叠窗口、边界信号、中断/过期回调全路径测试（✅ SentenceManager 侧；识别器侧随适配器补）

### P7 语言处理 / TTS / 缓存 / UI

- `LanguageProcessor`（本地优先，云端留接口）、`TtsManager`、Room 缓存、字幕 UI
- 验收：否定/数字保真样本集、TTS 去重键、90 天/万条清理、导出

**进度（2026-09-23）**：`LanguageResultGate`/`AutoSpeakQueue`/`SentenceCacheRetention`
三纯逻辑组件 17 测试全绿；云端语言处理按用户决定**改为 App 直连 LLM**
（`DirectLlmPolisher`，agent 服务逻辑移植：保真提示词 + guard 启发式 +
错误映射，7 测试 loopback 联调）；**agent 中间服务路径已移除**（源码保留在
main 分支），API.md §6.3 改为直连契约，凭据走 App 运行时设置（自持、
排除备份，发布包不内置密钥）。

**进度（2026-09-23 晚）**：`LanguageProcessor` 编排、`TtsManager`、缓存域层
（"Unresolved reference"排查结案：根因为 `repovayage` 拼写错误，非编译器 bug，
见 Docs/编译问题排查记录）与 **Room 落库**落地：域实体按 §8 注解（复合主键/
FK 级联/schema 导出）、`RoomSentenceCache`（合并=主键 upsert、历史=两表组合流）、
启动时保留清理（`applyRetentionPolicy` 复用纯域 evict）、`CacheExporter`
（FileProvider+ACTION_SEND，CSV 公式防护）、`AppSettings`（DataStore：缓存开关
默认开+首次告知标志）。基建：Room 2.8.5 + KSP 2.3.0（2.3.12 需更新 AGP，回退）
+ DataStore 1.2.1。验收：JVM 179 条全绿、真机 DAO androidTest 8/8
（小米 2510DRK44C/Android 16）、两 flavor debug + productionRelease 构建通过。
待做：字幕/设置 UI（Compose 基建未引入）、管线接线（识别源缺失随 P5/P6；
缓存写入的开关门控随管线）、UI 阶段处理首次告知展示与 LLM 凭据设置。

**进度（2026-09-23 深夜）**：Compose UI 三屏 + 翻译管线编排落地。MainActivity
迁 ComponentActivity + Compose（主界面/设置/历史三屏导航，P2 面板逻辑迁入
MainViewModel）；`TranslationPipeline` 接线 识别源→SentenceManager→
LanguageProcessor→字幕/缓存/TTS（Finalizing 2s 收尾、每句设置快照、revision
变更清待播、语音开关立即停、缓存开关门控写入）；`RecognitionSource` flavor 缝
（training 桩源 9 句脚本——含否定/数字保真样本；production 不可用，按 §6 只显
状态不伪造结果）；**识别模型选择**（用户新增需求：两个 P5 训练模型，
ModelCatalog 占位命名待 P6 回填，选择持久化于 AppSettings，推理接入后生效）；
AppSettings 扩充（字幕/语音语言·顺序=优先级·语音⊆字幕、播报开关、LLM 凭据、
settingsRevision）；TtsManager.clearPendingKeepCurrent + AndroidTtsSpeaker
（离线 Voice 就绪判定）。基建：Compose BOM 2026.06.01（ui 1.11.x 为
compileSdk 35 末代，1.12+ 需 36——升级须先过 §3.2 AAR 实测决策）+
activity-compose 1.10.1 + lifecycle 2.10.0；release lintVital 因 AGP 8.7.3
lint 与 Kotlin 2.3.20 工具链崩溃针对性关闭（注释在案，P8 人工评审补偿）。
验收：JVM 196 条全绿（+7 管线 +9 设置 +1 TTS）、两 flavor debug +
productionRelease 构建通过、training debug 已装机（手动验收进行中）。
待做：§2.4.7 蜂窝并发（相机会话中云端 LLM，测试机有 SIM 可完整验证）、
UI 手动验收收尾、P6 识别接入。

**进度（2026-09-23 深夜二，§2.4.7 蜂窝并发）**：云端 LLM 双网路径落地——
`CloudNetworkManager`（requestNetwork 等回调不轮询、客户端按有效网络复用、
断网废弃 + cancelAll 在途请求、release 注销；句柄/客户端工厂抽象化，
状态机 JVM 可测）+ `DirectLlmPolisher` 迁 OkHttp 4.12（clientProvider 注入，
预算超时经 newBuilder 派生共享连接池；5.x 的 okhttp-android 要求
compileSdk 37，锁 4.x）+ 管线生命周期挂钩（启动且凭据已配置 → acquire，
停止 → release，凭据中途变更跟随启停）；`network_security_config.xml`
照 Demo 抄写（明文例外仅相机本地地址，云端强制 HTTPS）；设置页加费用与
文本离机告知（第 6 条）。验收：JVM 204 条全绿（+7 状态机 +1 挂钩），
Polisher 7 条 loopback 契约测试迁移后仍绿，双 flavor + release 构建通过。
待做：真机双网验证（相机热点 + SIM 并发，需用户凭据与相机在场）。

**进度（2026-09-23 深夜三，P7 尾款·通知与震动）**：LLM 真实接入跑通
（用户凭据 DeepSeek OpenAI 兼容端点，排查确认双因：原配 URL 为百炼
Anthropic 协议端点（App 契约 §6.3 只讲 Chat Completions）+ 模型名不存在；
凭据文案改供应商中立"API 地址（OpenAI 兼容）"）；蜂窝双网系统层验证通过
（dumpsys 确认 App 的 CELLULAR 请求被 LTE 满足，相机 Wi-Fi 并存，蜂窝
ping 公网 0 丢包）。震动按**用户决定收紧：仅 LLM 翻译低置信
（NEEDS_CONFIRMATION）震动**，30s 限频、遵守勿扰/静音（Vibrator 绕过
系统勿扰故应用侧自查 interruptionFilter+ringerMode）、短双震波形——
ARCHITECTURE §2.7 表格已按决定修订（低电/断连/识别低置信一律只显不震）。
FGS 通知脱离 P2 占位：文本跟随会话状态实时刷新（sessionStateText 抽为
UI/通知共用）。修复：Alerter 限频溢出（Long.MIN_VALUE 哨兵 → nullable）。
验收：JVM 208 条全绿（+3 Alerter 语义 +1 管线仅低置信震动；另修复
FINAL 全链路测试对异步副作用的竞态断言）、双 flavor + release 构建通过、
training debug 已装机。**范围修订（2026-09-23 用户决定）：机位文字指引、
本地 LLM 引擎（决策项 #3）砍掉不做**。

**进度（2026-09-23 深夜八，frontend-design 重构 + P6 联调目标重定义：模型 B
固定窗口切片识别）**：① UI 按 frontend-design skill 二遍法重构——字幕流为
hero（22sp/30sp 时间轨行，3dp 状态色条编码序列+待核对），chrome 退平面发丝线
层（去卡片套件），空态/状态行文案重写，MASTER.md 增「设计落地」章节；真机
装机截图自审通过。② 评估 codex/app-local-video-test 分支（手动 MP4 台架：
词级 CV `/v1/recognize` + 组句 Agent `/v1/compose-signs` 已部署可达，但缺帧流
入口/服务端分段/数值句置信度/开放语料）——**不能直接当 P6，作第一轮联调**。
③ 用户重定义下一步目标：**相机帧流 → 固定时长窗口切分（切分定义权在用户，
设置页可调 0.5–10s，默认 2s）→ 逐段云端识别 → 直接进字幕管线**。落地：
SdkCameraSession 编码帧分流（encodedFrameTap，解码前）+ requestKeyFrame；
ClipSegmenter（MediaMuxer 直接封装原始 H.264 不转码、IDR 对齐、Annex-B→AVCC、
坏段/换代残段丢弃）；ClipRecognitionSource（词候选累积为草稿、CV 拒绝词→
重打提示不打扰、「完成本句」→Agent 组句、needsConfirmation 布尔直接映射
UNCERTAIN 边界=待核对+震动，无数值置信度不伪造、pts 账本取词段范围）；
RoutingRecognitionSource（模型 B→切片源，其余→flavor 桩/不可用）；上传走
§2.4.7 蜂窝绑定客户端（相机在线+蜂窝并发首次实链路验证）；设置页新增
CV/Agent 令牌 + 切片窗口；分支客户端/协议文档 cherry-pick 改造入库。
验收：JVM 228 条全绿（+17：协议解析 5、切片纯函数 5、识别源流程 7）、
双 flavor + release 构建通过。待做：真机端到端（需两令牌+相机在场）、
切片大小/上传延迟实测（GO 3S 高码率，必要时 setVideoBitrate 压档）、
五句语料放开与 sentence_confidence 数值化（队友侧）。

**进度（2026-09-23 深夜七，置信度流程定稿）**：用户定稿置信度流程——CV 每动作
多候选+CV 置信度 → 服务侧组合 LLM 挑词成句并给句子置信度 → App 只看句子
置信度：<0.7【初始阈值】→ 待核实标志+震动（识别侧唯一触发）；guard 保真失败
同桶（用户确认"可以有那个 guard"）；CV 侧不确定不直接打扰用户；needs_repeat
改重打提示（非待核实标志）。落地：管线 submit 置信度降级 + NeedsConfirmation
震动挂钩 + 桩源改置信度驱动（0.55 低置信演示句）+1 单测；P6 契约/§2.7 同步。
验收：JVM 211 条全绿、双 flavor + release 构建通过。

**进度（2026-09-23 深夜六，历史分组收敛 + 设备列表修复）**：按时间归并
对话选项经用户决定**移除**（仅保留按会话分组，选项按钮删除，归并逻辑与
4 条单测一并删除）；对话重命名保留（key=sessionId）。主界面扫描设备列表
改竖排全宽芯片 + 单行省略（横排 Row 将长设备名芯片挤成竖条，真机反馈）。
验收：JVM 210 条全绿、双 flavor + release 构建通过、已装机。

**进度（2026-09-23 深夜五，语桥品牌 UI 重设计 + 三项新需求）**：
调用 UI 设计 skill（Swiss 极简+信任方向，色板按用户品牌覆盖并实测对比度）
全量重设计：Material3 双主题 token（品牌蓝 #1259C0/青 #0E7C8C，深色
#4FD8EE/#0E1520，关键文本 ≥4.5:1）、底部三页导航（翻译/历史/设置，矢量
图标族 2dp 描边）、字幕优先排版（正文 18sp/28sp）、圆角卡片体系；
**App 更名「语桥」**（training 包保留·训练后缀以区分）；启动图标换用户
提供的手语声波 logo（chroma-key 透明前景 + 自适应图标 + 深浅色背景）；
通知小图标改声波矢量剪影。三项新需求（用户决定）：①识别模型目录改
**云端语义**——模型 A=面对面视角 / 模型 B=第一视角，去除本地安装门控，
云端识别客户端随 P6 接线；②**字幕倒序**（越晚越在上）；③历史页加
**按时间归并对话**选项（30 分钟间隔切分，可跨会话）+ **对话重命名**
（默认名=起始时间，DataStore JSON 持久化）。验收：JVM 214 条全绿
（+4 归并 +1 重命名；UsbBridgeServer 1 条 socket 时序测试为负载抖动型
flaky，单跑复绿）、双 flavor + release 构建通过、真机截屏确认新 UI 与
品牌图标生效。设计系统持久化于 design-system/default/MASTER.md（色板段
已修正为品牌 token）。**真机验证（2026-09-23 用户确认）：震动手感实测
通过（波形无需调参）；双网端到端通过（相机热点 + 蜂窝并发，LLM 出真实
文本）**。**保真样本集验收 v1 通过**（Docs/保真样本集验收-2026-09-23.md：
8/8 保真、guard 零误报、失败正确降级）——**P7 全部验收行至此完成**。
待做/待决策：P6 识别接入（契约已定：Docs/P6-App侧对接契约-基于联调手册v0；
识别服务队友侧已 17/17 验收）、识别侧待核对是否也震动（一行接线，待定）、
发布前扩充保真样本集。logo 源图入库 Docs/brand/logo-source-1254x1254.jpg。

**进度（2026-09-23 深夜四，待核对确认/纠错交互）**：四项设计决策经用户
确认后落地——①仅 LLM 侧纠错（识别→LLM→低置信震动→用户修改；识别侧
待核对卡片维持只"放弃"）②契约 `OutputSource` 增 **USER**（API.md §6.1
同步修订）：纠错落库 source=USER/status=READY 整行覆盖；原样保存=人工
确认（status=READY，source 保留模型来源）③原文 rawChinese 不可修改
（§2.4.5 保真基准）④历史页只读。UI：字幕行"待核对"→「核对」按钮→
对话框（原文只读 + 候选可编辑 + 保存/取消）；纠错后不自动播报
（§2.4.2 规则 12）。验收：JVM 209 条全绿（+1 纠错/确认落库语义），
双 flavor + release 构建通过，training debug 已装机。待做：真机震动手感
实测、保真样本集系统性验收、真机双网端到端（相机+凭据同场）。

### P8 验收（持续，按 ARCHITECTURE.md §8 全表）

每阶段结束跑对应行；发布前全表过一遍 + 持续 30 分钟压力 + 训练隔离检查（productionRelease 无采集入口/端口/落盘）。

---

## 2. 依赖关系与并行

```text
P0 ──► P1 ──► P2 ──► P3 ──► P4 ──┐
                 │               ├──► P6 ──► P7 ──► P8 发布
                 └──► P5（模型训练，外部周期）──┘
```

- P0 的分辨率结论 → P3 解码规格、P4 吞吐预算、P5 ModelSpec 输入
- P5 与 P1–P4 完全并行；P5 的 ModelSpec 草案尽早冻结输入格式（分辨率/采样率），避免 P6 返工
- ~~P7 的 LLM 本地引擎选型（MediaPipe LLM / llama.cpp）可在 P3 期间用样机预研~~（2026-09-23 用户决定砍掉，仅云端）

---

## 3. 待用户决策项

| # | 决策 | 影响阶段 | 备注 |
|---|---|---|---|
| ~~1~~ | ~~640×360 若确认不够、1080p 也不可行时的降级策略~~ | — | ✅ 已解除：live 模式可取 1080p/4K，改为"取流分辨率档位选型"（在 1080p 与 4K 间按发热/带宽/模型需求定，P3 实测后定） |
| 2 | 项目绝对截止日期 | 全局 | 确定后回填，决定 P6/P7 裁剪范围 |
| ~~3~~ | ~~LLM 本地引擎（MediaPipe / llama.cpp / 仅云端）~~ | P7 | ✅ 已决：**仅云端**，本地引擎砍掉不做（2026-09-23 用户决定；ARCHITECTURE §2.4.6 本地条款冻结） |
| 4 | 云端 LLM 是否本期实现 | P7 | 可只留 `LlmPolisher` 接口 |
| 5 | 目标手机范围（当前实测机：小米 2510DRK44C / MIUI） | P2/P8 | OEM 差异决定测试矩阵 |

## 4. 风险清单（按优先级）

| 风险 | 等级 | 缓解 |
|---|---|---|
| ~~相机 liveview 分辨率上限不足~~ | ~~高~~ | ✅ P0 已解除：live 模式实测 4K 开流成功；遗留发热问题（见下） |
| 自动边界模型能力不达标 | 高 | P5 尽早启动；不达标则收缩产品口径为"独立动作识别" |
| 相机过热（实测 53°C 起步） | 低 | ✅ 已归因解除：过热由 4K 编码残留引起（P3 真机发现 1），低负载主流未复现；温度观察按用户决定移除 |
| MIUI/国产 OEM 杀前台服务 | 中 | P2 就验证 FGS 存活；明确支持机型范围 |
| ~~SDK 分片聚合契约与实际不符~~ | ~~中~~ | ✅ P3 已解除：满帧率解码验证帧边界正确 |
| USB 吞吐不足 | 低 | P4 实测带宽后定预算档（128/256/64 MiB 或降规格） |

---

## 5. 立即的下一步

1. **P5 数据采集与训练尽早启动**（外部周期最长）：采集通道已完备，可开始正式
   试采（`pc/csl_capture.py`，授权参数见 `--help`；素材含 authorization.json
   可追溯元数据）
2. P6 识别与句子管理可依赖 ModelSpec 草案并行起步
