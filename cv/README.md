# 随心说：胸前手语识别原型

接下来采集现有 19 词的完整演示数据时，按 [全量拍摄与测试计划](全量拍摄与测试计划.md) 分批录制和留出测试集。

当前已准备 Google MediaPipe Hand Landmarker 官方预训练模型和独立 Python 3.12 环境。
MediaPipe 模型负责提取手部关键点；当前已有使用四场素材训练的 19 词 TCN 演示基线。TCN 不是预训练手语翻译器。Android 接入和 GO 3S 实时传输尚未实现。

## 环境与模型

在仓库根目录执行：

```bash
uv venv cv/.venv --python 3.12
uv pip install --python cv/.venv/bin/python -r cv/requirements-lock.txt
curl -fL https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task -o cv/models/hand_landmarker.task
```

模型版本：float16/1；SHA256：`fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`。
官方说明：https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker/python
模型、原始视频、生成结果、虚拟环境均不提交 Git。

## 先检查试拍视频

```bash
cv/.venv/bin/python cv/extract.py cv/data/videos/sample.mp4 --output cv/outputs/sample.npz --overlay cv/outputs/sample_overlay.mp4
```

生成关键点 NPZ、检测覆盖率 JSON、可回看的骨架叠加视频。覆盖率不是识别准确率，也不是关键点正确率。
已有一批视频时，可用 `cv/.venv/bin/python cv/audit.py cv/data/videos` 批量检查；汇总表写入 `cv/outputs/audit/coverage.csv`。这一步仅用于素材质量检查，不会训练分类器。
坐标为图像归一化坐标和手腕相对估计深度；不能把两只手的 z 当作共同坐标系的真实深度。
左右手按模型 handedness 分槽，交叉遮挡可能导致误判或跳变，必须看叠加视频检查。
视频时间按文件帧率推算；建议采集固定帧率视频。推理长边最多 1280，不裁剪画面。

## 数据准备

训练清单 `data/manifest.csv` 记录视频路径、词标签、拍摄者、场次和数据划分。
当前两场素材可运行 `cv/.venv/bin/python cv/prepare_manifest.py`，根据两份覆盖率报告自动生成清单；需从仓库根目录运行。
视频路径相对 manifest 所在目录。每段视频包含一个完整表达。

- 首批先做 5 个表达，每个 20 次，分至少三个独立录制场次。
- split 为 train、val、test；同一演示者同一场次不得跨集合。
- 所有类别必须出现在 train 和 test；若使用 val，也必须包含全部类别。测试视频不要用于调参。
- 如需验证换人效果，应按人分集合；脚本只强制按人加场次分组。
- 文件改名或重复剪辑也可能泄漏，脚本只能拦截路径重复，仍需人工确保视频独立。
- 额外录有手可见的普通动作，标为“非目标动作”，并在各集合保留样本。
- 全程检测不到手的片段会被拒绝，不用于训练。

## 训练与预测

```bash
cv/.venv/bin/python cv/classifier.py train cv/data/manifest.csv --output cv/outputs/training
cv/.venv/bin/python cv/classifier.py predict cv/outputs/sample.npz --checkpoint cv/outputs/training/classifier.pt
```

训练使用局部手形、原始手腕 xy 和手存在标记（共 132 维），保留朝向及双手相对位置。
采样到 64 帧后进入两层 TCN；有独立验证集时按验证 loss 选 checkpoint，没有时按固定训练轮数保存最后一轮，最后只评估一次 test。
支持 PyTorch MPS，运行环境不可用时回退 CPU；小数据集无需独立 NVIDIA 显卡。
输出 evaluation.json 包含测试样本数、准确率和混淆矩阵（行是真实，列是预测）。
预测返回前三个候选和未校准 softmax 分数，不代表可靠概率，始终要求用户确认。
尚未实现开放集拒识、自动动作分段或自动播报，不能把非目标手语强行分类后直接朗读。
只有真实素材独立测试后才能判断 Demo 识别效果。

## 电脑摄像头演示

在仓库根目录运行：

```bash
cv/.venv/bin/python cv/live_demo.py
```

首次运行按 macOS 提示允许终端访问摄像头。预览窗口出现后，按空格开始录一个词，再按空格结束并识别；按 Q 或 Esc 退出。识别结果（前三候选、手部检测帧比例）显示在启动命令的终端里。每段最多 6 秒，超过后会自动结束。若默认摄像头不是目标设备，可加 `--camera 1` 等编号。可先用 `--video cv/data/videos/session04/你1.MP4` 做不打开摄像头的回放检查。

这个演示仍依赖人为划定动作起止，没有连续手语分段或非目标动作拒识。手部检测帧比例低于 10% 时拒绝预测，输出 `insufficient_hand_detection`；模型分数未校准，需要人工确认。当前训练素材来自胸前相机，电脑摄像头位置和视角变化会影响结果，最好把摄像头放到接近胸前视角的位置试验。

## 短视频 HTTP 识别服务

App 将**一个孤立词**的 MP4 片段作为原始请求体发送到 `POST /v1/recognize`，请求头 `Content-Type: video/mp4`、`Authorization: Bearer <CV_SERVICE_TOKEN>`。返回 JSON：

```json
{
  "status": "OK",
  "frames": 64,
  "any_hand_fraction": 0.516,
  "candidates": [{"label": "你", "score": 0.9995}],
  "needsConfirmation": true
}
```

实际返回最多三个候选；分数是未校准 softmax。少于 12 帧返回 `TOO_SHORT`，检测到手的帧不足 10% 返回 `INSUFFICIENT_HAND_DETECTION`，这两种情况 `candidates=[]`。最大请求体 32 MiB。App 需把每次 `OK` 结果的 `candidates` 按词序组成 `gestures`，提交到 Agent 的 `POST /v1/compose-signs`；句子和词的切分由 App 负责。

本机先验证：

```bash
cv/.venv/bin/python cv/export_numpy.py cv/outputs/training_s01_s02_s03_s04_holdout_seed42/classifier.pt --output cv/outputs/classifier_numpy.npz
cv/.venv/bin/python cv/server.py
```

另开终端：

```bash
curl -H 'Content-Type: video/mp4' --data-binary @cv/data/videos/session04/你1.MP4 http://127.0.0.1:8765/v1/recognize
```

服务器已按 `cv/deploy/insta360-cv.service` 部署，通过 `https://101.37.234.129/v1/recognize` 提供 HTTPS 测试接口；使用 `cv/requirements-server.txt` 和导出的 NumPy 权重，不安装 PyTorch。`CV_SERVICE_TOKEN` 仅保存在服务器 `/opt/insta360-cv/.env`，不入库。完整请求/响应契约见 [完整 API 文档](../agent/docs/API.md)，App 步骤见 [交接文档](../agent/docs/交接文档.md)。该服务是测试原型，未实现连续识别、手势边界检测、非目标动作拒识和可靠置信度校准。

## 本机验证结果

2026-09-22：Apple M4 / 16GB / Python 3.12.13。
官方双手示例图构造的 10 帧视频，10 帧均检测到双手；黑色视频无手检测且被分类特征入口拒绝。
TCN 在 MPS 上完成前向、反向和优化器更新，并通过保存/加载输出一致性检查。
这些是运行检查，不是胸前视频准确率测试；完整真实训练仍待素材。
`check_setup.py` 可重新验证，官方示例图保存在本地 outputs，不提交仓库。
macOS 受限沙箱可能无法初始化图形上下文，在普通本地终端运行检查。

## 2026-09-23 两场素材的探索性训练

`session01` 的已标注词视频用作训练，`session02` 用作独立场次测试；只包含两场都有的 6 类：你、大家、好、年、新、见。第一场中完全未检测到手的片段没有进入训练。

```bash
cv/.venv/bin/python cv/audit.py cv/data/videos/session01 --output cv/outputs/audit
cv/.venv/bin/python cv/audit.py cv/data/videos/session02 --output cv/outputs/audit_session02_labeled
cv/.venv/bin/python cv/prepare_manifest.py
cv/.venv/bin/python cv/classifier.py train cv/data/manifest.csv --output cv/outputs/training_s01_to_s02 --epochs 80
cv/.venv/bin/python cv/classifier.py predict cv/outputs/audit_session02_labeled/features/见1.npz --checkpoint cv/outputs/training_s01_to_s02/classifier.pt
```

结果：21 段训练，12 段测试，8/12 正确（66.7%）。本轮没有第三场可作独立验证集，固定训练 80 轮；运行于受限环境中的 CPU。报告在 `outputs/training_s01_to_s02/evaluation.json`，权重在同目录 `classifier.pt`。每类测试仅 1–3 段，这一数字不能代表日常使用准确率。模型对“你”两段均误判为“大家”，对“新”两段均误判为其他类别。推理分数未经校准，即使接近 1 也不能作为可靠概率。

## 2026-09-23 第三场的五词测试

`session03` 有你、大家、好、年、新共 18 段，没有“见”，所以本轮只训练五类。用 `session01` 和 `session02` 的 27 段训练，`session03` 的 18 段测试，全部 18 段判对。训练场次和测试场次的背景相近，均为同一拍摄者；每类测试只有 3–4 段，不能推断换人、换场景或连续手语的效果。

```bash
cv/.venv/bin/python cv/audit.py cv/data/videos/session03 --output cv/outputs/audit_session03
cv/.venv/bin/python cv/prepare_manifest.py --third cv/outputs/audit_session03/coverage.csv --output cv/data/manifest_s01_s02_train_s03_test.csv
cv/.venv/bin/python cv/classifier.py train cv/data/manifest_s01_s02_train_s03_test.csv --output cv/outputs/training_s01_s02_to_s03 --epochs 80
```

报告和权重分别在 `outputs/training_s01_s02_to_s03/evaluation.json` 与 `classifier.pt`。特别注意，“大家”的三段测试视频平均只有约 7% 的帧检测到手；纯零输入也会被模型判成“大家”（softmax 约 0.82）。因此 18/18 不能证明五个手语词均被稳健识别，也不能用于自动播报。下一阶段需要更可靠的手部检测、非手语负样本和跨环境测试。

完整 19 词的新增 80 段视频已归档到 `data/videos/session04/`。采集记录、当前批次的检查结果以及下一批安排见 [全量拍摄与测试计划](全量拍摄与测试计划.md)。

## 2026-09-23 session04 加入训练后的随机抽查

固定随机种子 42，从 session04 的 19 个词中每词留出 1 段，共 19 段；其余 61 段与 session01–03 中有手部检测的 87 段合计 148 段训练。没有独立验证集，固定训练 80 轮。可复现命令：

```bash
cv/.venv/bin/python cv/prepare_random_holdout.py
cv/.venv/bin/python cv/classifier.py train cv/data/manifest_s04_holdout_seed42.csv --output cv/outputs/training_s01_s02_s03_s04_holdout_seed42 --epochs 80 --allow-within-session-test
```

留出集 13/19 正确（68.4%）。误判为：一定→我们、只→祝贺、回→你、很久不→家、我们→一定、照顾→见。逐段结果和手部检测覆盖率在 `outputs/training_s01_s02_s03_s04_holdout_seed42/holdout_review.csv`，详细报告在同目录 `evaluation.json`，模型在同目录 `classifier.pt`，随机抽样清单在 `data/manifest_s04_holdout_seed42.selection.json`。

这是**同一拍摄者、同一场次内部**的随机留出，训练集已含 session04 的近邻片段，所以只能检查模型是否初步学到这批动作，不能作为跨场次准确率。尤其“大家”的留出片段只有约 9% 的帧检测到手，即使判对也不能证明识别可靠。下一步应使用不参与训练的 session05/06 做完整 19 词测试，并加入非目标动作和无手片段检验拒识能力。
