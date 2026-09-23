# 随心说完整 HTTP API（演示版 v2.1）

公网基础地址：`https://101.37.234.129`。所有请求均使用系统信任的 HTTPS 证书；手机无需直接连接服务器的 8000 或 8765 端口。CV 和 Agent 是两个独立服务，通过同一个 Nginx 地址暴露。

| 方法与路径 | 服务 | 输入 | 令牌 | 作用 |
|---|---|---|---|---|
| `GET /cv/health` | CV | 无 | 无 | 检查 CV 模型是否加载 |
| `POST /v1/recognize` | CV | 一个孤立词的 MP4 | `CV_SERVICE_TOKEN` | 返回最多三个词候选 |
| `GET /health` | Agent | 无 | 无 | 检查 Agent 配置 |
| `POST /v1/compose-signs` | Agent | 一句内有序词候选 | `SERVICE_API_KEY` | 从五句语料中选句 |
| `POST /v1/polish` | Agent | 已冻结中文原文 | `SERVICE_API_KEY` | 旧有整理与翻译流程 |
| `GET /openapi.json` | Agent | 无 | 无 | Agent 两个 POST 接口的机器可读契约；CV 接口见本文 |

### CV：POST /v1/recognize

请求体直接放 MP4 字节，**不是** JSON 或 multipart。每次只提交一个已切好的词片段，最大 32 MiB；请求头必须有 `Content-Type: video/mp4` 和 `Authorization: Bearer <CV_SERVICE_TOKEN>`。CV 令牌与 Agent 的 `SERVICE_API_KEY` 不同。

```http
POST /v1/recognize HTTP/1.1
Host: 101.37.234.129
Authorization: Bearer <CV_SERVICE_TOKEN>
Content-Type: video/mp4
Content-Length: <视频字节数>

<MP4 字节>
```

HTTP 200 响应示例：

```json
{
  "frames": 64,
  "any_hand_fraction": 0.515625,
  "needsConfirmation": true,
  "status": "OK",
  "candidates": [
    {"label": "你", "score": 0.9998},
    {"label": "一定", "score": 0.0002},
    {"label": "自己", "score": 0.000001}
  ]
}
```

`frames` 是解码帧数，`any_hand_fraction` 是至少检测到一只手的帧比例，范围 0–1，**不是识别准确率**。`score` 是未校准 softmax，不是可靠概率。`candidates` 按排名排列，最多三个。`needsConfirmation` 恒为 true。`status` 为 `OK`、`TOO_SHORT`（少于 12 帧）或 `INSUFFICIENT_HAND_DETECTION`（检测到手的帧比例低于 0.10）；后两者的 `candidates=[]`，App 不应送入句子补全。

| HTTP | 响应 JSON | 含义 |
|---|---|---|
| 200 | 上述 CV 结果 | 识别完成或片段被质量门槛拒绝 |
| 401 | `{"error":"UNAUTHORIZED"}` | CV Bearer 令牌缺失或错误 |
| 404 | `{"error":"NOT_FOUND"}` | 路径不对 |
| 411 | `{"error":"CONTENT_LENGTH_REQUIRED"}` | 缺少有效 Content-Length |
| 413 | `{"error":"VIDEO_TOO_LARGE"}` 或网关响应 | 视频超过 32 MiB |
| 415 | `{"error":"EXPECTED_VIDEO_MP4"}` | Content-Type 不是 video/mp4 |
| 422 | `{"error":"INVALID_VIDEO"}` | 视频不完整或无法解码 |
| 503 | `{"error":"CV_INFERENCE_FAILED"}` | CV 推理故障 |

网关限流时还可能返回 429，超时/上游故障可能返回非 JSON 的 502/504；App 应先看 HTTP 状态与 Content-Type。CV 服务不回传 `sessionId`、`segmentId` 或 `revision`，App 必须用自己的请求快照关联视频片段并丢弃过期结果。此服务一次只处理一个请求，当前没有自动切词、开放集拒识或语句识别。

## 0. 五句手语演示接口：POST /v1/compose-signs

此接口接收 App 已切好的**一个句子段**内的有序手势结果。每段手势可提交 CV 的前 1–3 个词候选，按排名排列；`score` 可省略，即使提供也是未校准 softmax，不能当正确概率。服务只会从以下五句中选择，或返回 null：

1. 我想回家
2. 你一定可以
3. 你要照顾好自己
4. 祝大家新年好
5. 我们只是好久不见

这里的“想”不是 CV 词类，而是被授权在固定句式中补出；CV 标签“祝贺”对应目标句的“祝”，“很久不”对应“好久不”。不允许生成其他句子。当前 CV 模型仍是 19 个**孤立词**分类器，App 必须切好每个词和句子段；此接口不接收视频、不做手势分段。

请求头与 `/v1/polish` 相同：`Authorization: Bearer <SERVICE_API_KEY>`、`Content-Type: application/json`；可选 `X-Remaining-Budget-Ms` 为 1–10000，云端模型调用最多 10 秒。

```json
{
  "sessionId": "12345678-1234-1234-1234-123456789abc",
  "segmentId": "seg-sign-1",
  "revision": 1,
  "gestures": [
    {"candidates": [{"label": "我", "score": 0.82}, {"label": "我们", "score": 0.12}]},
    {"candidates": [{"label": "回", "score": 0.71}]},
    {"candidates": [{"label": "家", "score": 0.63}]}
  ]
}
```

`sessionId` 为 UUID；`segmentId` 非空、1–128 字符；`revision` 为 0–2147483647 的整数。`gestures` 为按时间排列的 1–12 项；每项 `candidates` 为按 CV 排名排列的 1–3 项，词标签不能重复，必须属于当前 19 类。`score` 可选，若提供须在 0–1 内。未约定字段和不合法数据返回 422。新段使用新 `segmentId`；同段修改则增加 `revision`。

当前允许的 19 个 CV 标签：`一定`、`你`、`只`、`可以`、`回`、`大家`、`好`、`家`、`年`、`很久不`、`我`、`我们`、`新`、`是`、`照顾`、`祝贺`、`自己`、`要`、`见`。例如“想”不是合法 CV 标签，不能作为 `candidates[].label` 直接提交。

成功响应示例：

```json
{
  "segmentId": "seg-sign-1",
  "revision": 1,
  "sentence": "我想回家",
  "alternatives": ["我想回家"],
  "status": "CANDIDATE",
  "needsConfirmation": true
}
```

`status` 为 `CANDIDATE`、`AMBIGUOUS` 或 `INSUFFICIENT_EVIDENCE`。后两者的 `sentence` 为 null；`alternatives` 是按至少两个有序词候选、区分词和候选排名权重筛出的可选句，不保证正确。两个都排第三的弱候选不会单独构成证据。证据不足时不调用 LLM。LLM 只能从 `alternatives` 选择或返回 null；越界输出返回 502 `MODEL_INVALID_RESPONSE`。所有结果 `needsConfirmation=true`，App 应展示给用户确认，不能直接自动播报。当前模型对胸前视角的同场次抽查为 13/19，且缺少非目标动作拒识；五句组合效果尚未独立验证。

错误体结构、鉴权、超时和模型错误码与 `/v1/polish` 相同。接口本身不保存会话；App 负责视频分段、候选排序、代次失效、去重、结果确认和 TTS。公网服务已部署 Agent 2.1.0；可通过线上 OpenAPI 核对实际版本。

以用户提供的 `API_new.md` §5–6 为依据。本文件补齐 Python HTTP 的可执行约定，不替代全系统契约；原始 `API_new.md` 保持不变。该文档引用的 `ARCHITECTURE.md` 当前未在仓库中找到，若之后提供，需进一步核对冲突。

## 1. 变更与边界

| 原版 | 新版 |
|---|---|
| POST /api/v1/compose | POST /v1/polish；旧接口已移除，调用会返回 404 |
| utterance_id | sessionId + segmentId；utteranceId 仅属于 TTS 提交，不能复用作段 ID |
| words 数组 | rawChinese：FINAL 冻结原文，不拆词、不 trim、不接收草稿 |
| revision | 段内容版本原样回传；允许从 0 开始 |
| sentence / question / status | polishedChinese / translations / issues |
| 仅中文 | 中文整理 + 仅用户选择的语言翻译 |
| 模型超时默认 30 秒 | 云端模型调用最多 10 秒，可按客户端剩余预算缩短 |

Python 实现的是 §6.3 云端 HTTP，使用一个模型请求处理全部语言，返回一次 JSON，不是 §6.1 的 Kotlin Flow。LOCAL 引擎、1 执行+2 待处理队列、从 FINAL 起算的期限、代次失效、去重和 TTS 调度属于 App 的 LanguageProcessor。服务不保存会话、去重缓存或手机单调时钟，不自动降级到未实现的本地模型。

服务支持独立 Bearer 访问令牌；本次部署使用 Nginx HTTPS、基础限流和 systemd。模型凭据只存在服务端。手机端完整集成与生产验收仍需完成。

## 2. POST /v1/polish

`Content-Type: application/json`，UTF-8。字段采用 camelCase，未约定字段返回 422。

公网服务要求 `Authorization: Bearer <SERVICE_API_KEY>`。此令牌不同于模型 API Key；本机 `.env` 与服务器使用同一服务令牌。Swagger 点击 Authorize 后输入令牌，Apifox 选择 Bearer Token。

```json
{
  "sessionId": "12345678-1234-1234-1234-123456789abc",
  "segmentId": "seg-1",
  "revision": 3,
  "rawChinese": "我 需要 帮助",
  "targetLanguages": ["en-US"],
  "context": []
}
```

| 字段 | 类型 | 必填/默认 | 校验与语义 |
|---|---|---|---|
| sessionId | string | 必填 | UUID 字符串，标识当前会话，不作为模型输入 |
| segmentId | string | 必填 | 1–128 字符，非纯空白，会话内唯一段 ID |
| revision | int | 必填 | 0–2147483647，拒绝 bool/字符串/浮点数，原样回传 |
| rawChinese | string | 必填 | 1–2000 字符且非纯空白；原样送给模型，唯一合法的表达内容来源 |
| targetLanguages | string[] | 默认 ["zh-CN"] | 1–8 项，不得重复；规范大小写，例如 zh-CN/en-US/ja-JP/zh-Hant-TW |
| context | object[] | 默认 [] | 最多 5 项；只能提交已确认的历史句子摘要，不能混入草稿 |
| context[].segmentId | string | 必填 | 同 segmentId 长度约束 |
| context[].rawChinese | string | 必填 | 同 rawChinese 长度约束，仅用于有限衔接，不能据此新增事实 |

**实现补充，待团队确认：**原草案没有定义句长、上下文和语言上限，本版暂设上述保护性边界，不声称来自 ARCHITECTURE.md。超限拒绝，不截断、不静默拆句。语言标签暂接受 `小写2–3字母语言[-首字母大写4字母脚本][-大写2字母地区或3位数字地区]`；不是完整 BCP 47 注册表，也不保证模型支持任意语言。`context` 采用 §6.3 的简化 HTTP 结构，不要求完整 ConfirmedSentence；是否已确认由 App 保证。

`targetLanguages=["zh-CN"]` 时 translations 必须为空。请求只选外语时仍可返回 polishedChinese 作为整理中间结果，但 App 不应自动展示/播报未选语言。繁体等中文变体作为单独目标语言，由 translations 对应键返回。

### 可选期限请求头

```http
X-Remaining-Budget-Ms: 7500
```

正整数 1–10000，省略按 10000。模型调用时限为 `min(LLM_TIMEOUT_SECONDS, 剩余预算/1000, 10秒)`，全部语言共享一个时限，不对每种语言重新开始计时。超时不重试，返回 504。已有 `.env` 即使写了 30，也不会超过这里的 10 秒上限。

这个请求头是实现扩展，不是手机 `deadlineMonoMs`。App 应在发出请求前用自身单调时钟计算剩余预算，并为完整 HTTP 调用设置同一剩余期限；请求头无法扣除后续传输时间，不能替代客户端截止控制。预算已耗尽时 App 不应发起请求，而应直接将未完成语言标记 UNAVAILABLE。服务器不返回伪装成手机时钟的 elapsedMs。

## 3. 200 响应

```json
{
  "segmentId": "seg-1",
  "revision": 3,
  "polishedChinese": "我需要帮助。",
  "translations": {"en-US": "I need help."},
  "issues": []
}
```

| 字段 | 类型 | 语义 |
|---|---|---|
| segmentId | string | 请求段 ID |
| revision | int | 请求版本号，对应 LanguageResult.sentenceRevision |
| polishedChinese | string/null | 整理后的中文，1–2000 字符；无法完成为 null |
| translations | object | 目标语言→非空译文，每条最多 2000 字符；不含 zh-CN 或未请求的语言；缺失语言省略该键 |
| issues | object[] | 逐语言问题列表；不得仅凭 HTTP 200 直接播报 |

### FidelityIssue 实现约定

原草案未定义该类型的具体字段，当前补充为：

```json
{"language":"en-US","code":"AMBIGUITY","message":"请核对原句所指对象。"}
```

| code | App 输出状态 | 文本处理 |
|---|---|---|
| AMBIGUITY | NEEDS_CONFIRMATION | 可能仍有候选文本，展示核对，不自动 TTS |
| FIDELITY_CHECK_FAILED | NEEDS_CONFIRMATION | 否定/数字/重复等可能变化，展示核对，不自动 TTS |
| UNAVAILABLE | UNAVAILABLE | 中文设 null；外语省略对应键；禁止中文充当外语降级结果 |

`message` 是 1–300 字符的非空说明。相同语言/代码去重。单个语言优先级：**UNAVAILABLE > NEEDS_CONFIRMATION > READY**。任何缺失文本都应按 UNAVAILABLE 处理，即使客户端未识别到 issue。无 issue 且有文本才可映射 READY；仍不等于跨语言语义已被证明正确。

例如部分结果可用：

```json
{
  "segmentId": "seg-1",
  "revision": 3,
  "polishedChinese": "我需要帮助。",
  "translations": {"en-US": "I need help."},
  "issues": [{"language":"ja-JP","code":"UNAVAILABLE","message":"该语言翻译未完成。"}]
}
```

仅日语不可用，不撤销中文/英文。单次 HTTP 非流式响应无法在整体模型超时前交付部分语言；全请求超时意味着该请求尚未交付的语言都未完成。App 已从其他有效路径收到的结果不得回滚。

### 保真检查边界

模型提示要求保留否定、数字、专名、有意重复，禁止增加事实。服务端补充中文否定/数字计数及显式分隔重复词检查、外语阿拉伯数字检查和“中文原样复制成外语”拦截。可疑输出按语言标记；完整性缺失标 UNAVAILABLE。

规则是保守启发式，不能可靠检测所有专名变化、事实新增、跨语言否定和译文语种，也可能将合理数字改写误标；不构成翻译正确性证明。不要把它宣传成已完成保真验证。

## 4. 错误响应

```json
{
  "segmentId": "seg-1",
  "revision": 3,
  "error": {"code":"MODEL_TIMEOUT","message":"期限内未完成，请将未返回语言标记为 UNAVAILABLE。","retryable":true}
}
```

| HTTP | code | retryable | 原因 |
|---|---|---|---|
| 401 | UNAUTHORIZED | false | 缺失或错误的服务访问令牌 |
| 422 | INVALID_REQUEST | false | JSON、字段、语言标签、请求头或长度错误 |
| 503 | MODEL_NOT_CONFIGURED | false | Key/Base URL/模型名未填齐 |
| 504 | MODEL_TIMEOUT | true | 模型阶段或总调用时限耗尽 |
| 502 | MODEL_UNAVAILABLE | true | 模型网络连接/传输异常 |
| 502 | MODEL_AUTH_FAILED | false | 上游 401/403 |
| 503 | MODEL_RATE_LIMITED | true | 上游 429 |
| 502 | MODEL_UPSTREAM_ERROR | 上游5xx为true | 其他非成功响应，不跟随重定向 |
| 502 | MODEL_INVALID_RESPONSE | true | 输出无法解析/字段非法/额外语言等 |
| 500 | INTERNAL_ERROR | false | 内部未预期异常 |

422/500 的 segmentId、revision 为 null，其余错误关联合法请求。客户端同时处理非 2xx JSON 与网络/代理层非 JSON 错误；404/405 使用框架默认格式。错误不回显模型密钥或上游原始错误体。

`retryable` 只是技术上可重试，不允许绕过原期限、在已失效会话重试或自动播报迟到结果。本服务不自动重试。期限内云端失败，仅由 App 判断本地引擎是否就绪且剩余时间足够；不可用外语仍保持 UNAVAILABLE。

## 5. Kotlin 对接与状态映射

```kotlin
@Serializable
data class PolishContext(val segmentId: String, val rawChinese: String)

@Serializable
data class PolishRequest(
    val sessionId: String,
    val segmentId: String,
    val revision: Int,
    val rawChinese: String,
    val targetLanguages: List<String> = listOf("zh-CN"),
    val context: List<PolishContext> = emptyList()
)

@Serializable
data class FidelityIssue(val language: String, val code: String, val message: String)

@Serializable
data class PolishResponse(
    val segmentId: String,
    val revision: Int,
    val polishedChinese: String?,
    val translations: Map<String, String>,
    val issues: List<FidelityIssue>
)

interface AgentApi {
    @POST("v1/polish")
    suspend fun polish(
        @Header("Authorization") authorization: String,
        @Body body: PolishRequest,
        @Header("X-Remaining-Budget-Ms") remainingBudgetMs: Long
    ): Response<PolishResponse>
}
```

使用 kotlinx.serialization 的 Serializable 和 Retrofit 的 Body/Header/POST/Response；示例需集成 App 自身转换器与依赖，仓库无 Android 工程，未编译验证。

App 发送前保存完整 ConfirmedSentence 和 OutputPreferences 快照。HTTP 只发送 §6.3 的简化字段，响应也只回传 segmentId/revision，因此以下信息必须由请求闭包保留，不能从“当前设置”重新读取：

- sessionId、streamGeneration、sequenceEpoch、segmentId、sentence revision；
- settingsRevision、selectedLanguages、spokenLanguages；
- FINAL 时刻、绝对本机单调时钟 deadline。

响应到达先比对当前有效代次、段修订和设置快照；停止/重连/设置变更后的旧结果丢弃。原响应 ID/revision 也必须匹配。按 `(sessionId, segmentId, revision, language)` 保留一个有效结果。

对用户选择的每个 language：zh-CN 取 polishedChinese，其他取 translations[language]。有 UNAVAILABLE 或缺文本则 UNAVAILABLE；有其他 issue 则 NEEDS_CONFIRMATION；否则 READY。将保留的身份快照写入 LanguageResult，source=CLOUD，elapsedMs 由手机从 FINAL 起算，包含排队与网络。

只有 READY 且属于 spokenLanguages 的结果可进入 TTS，还要遵守段状态、去重和语音可用规则。后端不代替客户端的这些校验。

## 6. 配置、模型协议和健康检查

读取 `agent/.env`，系统环境变量优先，修改后重启。API Key/Base URL/模型名原有值保持不变；配置 SERVICE_API_KEY 可启用访问认证，公网部署必须启用。

```dotenv
LLM_API_KEY=
LLM_BASE_URL=
LLM_MODEL=
LLM_TIMEOUT_SECONDS=10
SERVICE_API_KEY=
```

LLM_BASE_URL 为 API 前缀，程序追加 `/chat/completions`。使用 Bearer Key，提交 model、stream=false、system/user messages；读取 choices[0].message.content 的严格 JSON。`/v1/polish` 的模型输出必须为 polishedChinese/translations/issues；`/v1/compose-signs` 的模型输出只允许 sentence（五句之一或 null）。均不接受 Markdown。不强制供应商 JSON mode，无自动本地降级。

`GET /health` 仍返回：

```json
{"status":"ok","model_configured":false}
```

model_configured 只说明配置齐全，不代表上游可用；健康检查不调用模型。模拟模式与固定样例已删除，所有整理请求均调用实际模型。

## 7. 调试和验收

```bash
curl -sS http://127.0.0.1:8000/v1/polish \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${SERVICE_API_KEY}" \
  -H 'X-Remaining-Budget-Ms: 9000' \
  -d '{"sessionId":"12345678-1234-1234-1234-123456789abc","segmentId":"seg-1","revision":3,"rawChinese":"我 需要 帮助","targetLanguages":["en-US"],"context":[]}'
```

示例响应仅说明数据格式，实际文本由模型生成。在线调试 `/docs`，机器定义 `/openapi.json`，仓库快照 `docs/openapi.json`。测试命令 `.venv/bin/python -m pytest -q`。

实测范围：74 项自动化测试、服务器真实模型调用、公网 HTTPS 和 Bearer 鉴权；选定三个真实视频的 CV→Agent 接口链路已验证。手机真机接入、连续视频切词/切句、跨场次准确率、从 FINAL 起算的完整期限和整体翻译质量仍需验收。

线上地址与运维操作见 DEPLOYMENT.md。Agent 两个 POST 路由按来源 IP 限流 30 次/分钟、突发 5 次、同时最多 3 个请求，默认超过 128 KiB 返回 413。CV 路由另有 12 次/分钟和 32 MiB 的上限。这些网关错误可能是非 JSON。
