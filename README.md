# 随心说 / Sign

手语识别结果的语句整理与翻译项目。当前仓库包含 Python Agent、接口契约、部署配置、数据整理脚本和 CV 原型；Android App 待接入。

## 目录

- `agent/`：Python HTTP 服务、测试、生产部署配置。
- `cv/`：MediaPipe 双手关键点提取、19 词 TCN 分类和短视频 HTTP 测试服务。见 [CV 使用说明](cv/README.md)；目前只识别孤立词。
- `agent/docs/接口调用说明.md`：App / Apifox 请求、响应和认证说明。
- `docs/`：项目调研文档。
- `datasets/`：数据来源说明和整理脚本。

## 开发

```bash
cd agent
uv venv --python 3.13
uv pip install --python .venv/bin/python -r requirements-lock.txt
cp .env.example .env
```

填写 `.env` 中模型 API Key、Base URL、模型名及独立的 `SERVICE_API_KEY`，然后启动：

```bash
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

具体使用见 [Agent 说明](agent/README.md) 和 [接口调用说明](agent/docs/接口调用说明.md)。真实密钥不随仓库发布，请向维护者获取服务访问令牌。

当前演示链路：GO 3S → App 切出单词短视频 → CV `/v1/recognize` 返回每词最多三个候选 → Agent `/v1/compose-signs` 从五句固定语料中选择一句 → App 展示给用户确认。App 的视频切词/切句和端到端集成仍待完成；CV 分数未校准，不可直接据此自动播报。

## 数据范围

下载的第三方词典数据库、图像、视频样本及其生成画廊未上传；本仓库不授予这些数据的再分发权。数据整理脚本需要先按 [数据来源说明](datasets/README.md) 准备本地材料，不能仅凭克隆仓库直接重建完整画廊。
