# 随心说 / Sign

手语识别结果的语句整理与翻译项目。当前仓库包含 Python Agent、接口契约、部署配置和数据整理脚本；CV 模型与 Android App 由对应模块另行接入。

## 目录

- `agent/`：Python HTTP 服务、测试、生产部署配置。
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

## 数据范围

下载的第三方词典数据库、图像、视频样本及其生成画廊未上传；本仓库不授予这些数据的再分发权。数据整理脚本需要先按 [数据来源说明](datasets/README.md) 准备本地材料，不能仅凭克隆仓库直接重建完整画廊。
