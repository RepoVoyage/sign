# 语桥 (Yuqiao) — GO 3S Sign Language Translation for Android

Insta360 竞赛作品。使用 Insta360 GO 3S 相机取流，在手机上实现手语→文本→语音的全链路翻译。

<img src="src/app/src/main/res/drawable/ic_launcher_foreground.png" width="64" alt="语桥 logo" align="right">

**当前状态：** P6 联调阶段——识别链路已跑通（词级 CV → Agent 组句 → 润色/翻译 → 字幕/TTS），使用云端模型 B；本地模型未接。

---

## 数据流

```
GO 3S Wi‑Fi 热点
    │ BLE 发现 & 配网 → 手机连相机 Wi‑Fi → SDK 收流
    ▼
H.264 编码帧（640×384 / 1080p LIVE）
    │ 同帧分片聚合 → 参数集与 IDR 准备
    ▼
MediaCodec 无显示解码 → YUV 图像
    ▼
encodedFrameTap（H264DecodePrep 后截取）→ ClipSegmenter
    │ 固定时长窗口（默认 2s，0.5–10s 可调）
    ▼
MP4 段 → 蜂窝 POST 上传 → 云端词级 CV（/v1/recognize）→ 候选组
    │ 每窗口一个槽（PENDING→FILLED/EMPTY），
    │ 不重排、不去重、不作废整句
    ▼
用户点击「完成本句」→ 云端组句 Agent（/v1/compose-signs）→ 句子
    │ → 直接存历史 + 直接 TTS 朗读（不依赖 LLM 润色）
    │ → 震动核对提醒（needsConfirmation，不阻断）
    ▼
（休眠）云端 LLM 润色/翻译 — 配置模块已于 2026-09-24 移除；
    │ 仅 DataStore 残留凭据的旧装机仍会跑，无凭据静默跳过
    ▼
历史缓存（Room，默认保存 90 天 / 10000 条）
```

---

## 仓库结构

```
sign/
├── src/                          # Gradle root (Android 项目)
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── main/             # 公共代码（UI + 管线 + 识别 + TTS + 设置）
│   │       ├── training/         # flavor: 训练采集（USB Bridge + 调试留存）
│   │       └── production/       # flavor: 成品（无采集入口/端口/落盘）
│   ├── gradle/
│   └── settings.gradle.kts
├── agent/                        # 云端 Agent 组句服务（队友项目）
├── pc/                           # PC 端训练采集（Python / uv）
│   ├── csl_capture.py
│   └── pyproject.toml
├── reference/                    # Insta360 Android SDK + 联调日志
├── Docs/                         # 架构文档 / API 契约 / 联调手册
│   ├── ARCHITECTURE.md           # 系统架构（§1–§7）
│   ├── API.md                    # 应用侧与云端接口契约
│   └── P6-App侧对接契约-*.md     # P6 联调版协议
├── design-system/                # UI 设计系统（selector → MASTER.md）
├── 安卓联调手册_2026-09-23.md
└── 交接报告-手语识别模型云端部署契约-2026-09-23.md
```

### 核心源码 (`src/app/src/main/java/com/repovoyage/sign/`)

| 包 | 作用 |
|---|---|
| `camera/` | GO 3S 取流 / 解码 / 编码帧截获 (`SdkCameraSession`) |
| `video/` | 固定窗口 MP4 切片器 (`ClipSegmenter`)，MediaMuxer + 原始 NAL 写入 |
| `recognition/` | 模型 B 切片识别源（词级 CV → 槽位制 → Agent 组句）(`ClipRecognitionSource`) |
| `pipeline/` | 翻译管线编排 (`TranslationPipeline`)：段状态机 → 润色 → 字幕 → TTS |
| `language/` | 云端 LLM 润色/翻译 (`CredentialLlmPolisher → DirectLlmPolisher`) |
| `sentence/` | 段状态机 (`SentenceManager`) + 边界信号类型 |
| `tts/` | 系统 TTS 引擎封装 (`AndroidTtsSpeaker` + `AutoSpeakQueue`) |
| `settings/` | DataStore 设置 (`AppSettings`)：凭据/语言/窗口/缓存 |
| `net/` | 蜂窝网络绑定 (`CloudNetworkManager`，§2.4.7) |
| `ui/` | Jetpack Compose + Material3 三屏：主界面 / 历史 / 设置 |
| `history/` | Room 历史缓存 + JSON/CSV 导出 |
| `alert/` | 待核对震动提醒 (`ConfirmationAlerter`) |

---

## 构建与运行

### 前置条件

- **JDK 17**（实测 `/home/h/jdk17`）
- **Android SDK**（compileSdk 35 / minSdk 29 / targetSdk 35）
- **Insta360 SDK 凭据**（不提交仓库）：

  ```bash
  # ~/.gradle/gradle.properties
  insta360MavenUsername=your_username
  insta360MavenPassword=your_password
  ```

  正确配置后 `settings.gradle.kts` 会添加 Insta360 Maven 仓库 — 否则构建在仓库配置阶段失败。

### 命令

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

# 全量测试
./src/gradlew -p src test

# 训练包（含 USB 采集入口）
./src/gradlew -p src assembleTrainingDebug

# 成品包（无采集入口/端口/落盘）
./src/gradlew -p src assembleProductionDebug

# 真机安装（以训练包为例）
adb install -r src/app/build/outputs/apk/training/debug/app-training-debug.apk
```

### Flavor 差异

| | training | production |
|---|---|---|
| 包名 | `com.repovoyage.sign.training` | `com.repovoyage.sign` |
| USB 采集入口 | ✅ 有（前台服务 + PC 传输） | ❌ 无 |
| 调试切片留存 | ✅ `clips_debug/` 保留 5 个 | ❌ 不落盘 |
| 录屏 (USB) 统计 | ✅ | ❌ |

### 运行时配置（设置页）

| 项 | 说明 |
|---|---|
| **字幕语言** | 默认 `zh-CN`，可选 `en-US` / `ja-JP`；顺序 = 处理优先级 |
| **语音语言** | ⊆ 字幕语言；总开关启停播报 |
| **模型 B 识别服务** | CV 令牌 + Agent 令牌（部署方提供），切片窗口（0.5–10s） |
| **文本缓存** | 默认开；关闭后停止写入新记录 |

---

## 关键设计决策（2026-09-23/24 用户定稿）

1. **全链路走云，无本地引擎** — `LanguageBackend` / `OutputSource.LOCAL / FALLBACK` 已移除；
2. **固定时长窗口切分** — 视频切分定义权在用户（settings 可调 0.5–10s，默认 2s）；
3. **槽位制**（非节奏令牌）— 每窗口一槽；识中/成功/拒识分别 → PENDING… / FILLED(label) / EMPTY(＿)；不去重、不重排、不作废整句；
4. **「完成本句」点击即发** — 快照当前已填槽，不等在途、不被识别阻塞；
5. **完成句直接入库 + 直接朗读** — FINAL 时立即读原句（`rawChinese`），不依赖 LLM 润色；润色结果只进字幕不重复播；
6. **needsConfirmation → 震动提醒（不阻断）** — Agent 标记的需要核对仅触发震动（30s限频）+ 状态行「组句待核对」，句子照常收尾入库播报；
7. **CV 拒识 → 重打提示**（`needs_repeat`，不震动、不是待核实标志）；
8. **隐私红线**：成品无视频落盘；凭据仅存本机 DataStore 不内置不提交；日志不输出帧内容/密钥/对话（§2.6/§6.3）；
9. **云端 LLM 配置模块移除**（2026-09-24 用户决定）— 组句走云端 Agent，不再需要用户配置 LLM；未配置凭据时语言处理静默跳过（无"不可用"噪音行），字幕 = 识别原句。

---

## 网络架构（§2.4.7）

- 相机 Wi‑Fi 是进程**默认网络**，不提供公网访问；
- 云端请求走**蜂窝绑定** `CloudNetworkManager`（`requestNetwork` + `Network.bindSocket`）；
- 未就绪时回退到进程默认网络（相机未连接时可行，相机会话中失败降级 UNAVAILABLE）。

---

## 测试

```bash
./src/gradlew -p src test
```

- **233+ JVM 单元测试**（JUnit4 + kotlinx-coroutines-test）
- `p6/` — 切片识别源测试（槽位制、HTTP 传输、组句契约）
- `p7/` — 管线编排测试（段状态机、TTS、缓存、纠错）
- 真机联调日志：`adb logcat -s ClipCv YuqiaoTts`（CV 联调 / TTS 诊断）

---

## 相关文档

| 文档 | 内容 |
|---|---|
| [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) | 系统架构设计（§1–§7） |
| [`Docs/API.md`](Docs/API.md) | App 侧与云端接口契约 |
| [`Docs/准备保真样本集验收-2026-09-23.md`](保真样本集验收-2026-09-23.md) | 保真样本集验收标准 |
| [`交接报告-手语识别模型云端部署契约-2026-09-23.md`](交接报告-手语识别模型云端部署契约-2026-09-23.md) | 云端模型部署契约 |
| [`第一人称本地视频联调.md`](第一人称本地视频联调.md) | P6 联调协议 |

---

## License

Insta360 竞赛内部项目。