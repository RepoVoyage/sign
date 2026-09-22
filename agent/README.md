# 随心说 Python Agent

接收 FINAL 冻结原文，调用实际 LLM 整理中文并按需翻译。接口：`POST /v1/polish`。固定模拟语句、模拟模式与代码中的示例输出已删除；测试替身仅存在于 `tests/`。

## 线上访问

- 调试文档：https://101.37.234.129/docs
- 健康检查：https://101.37.234.129/health
- 接口：https://101.37.234.129/v1/polish

接口要求 `Authorization: Bearer <SERVICE_API_KEY>`。令牌已写入本机 `agent/.env`，与服务器一致，不是 `LLM_API_KEY`。Apifox 选择 Bearer Token 并粘贴 SERVICE_API_KEY 的值；Swagger 点击 Authorize 后输入令牌。

详细字段见 [接口文档](docs/API.md)，部署和维护见 [部署说明](docs/DEPLOYMENT.md)。

## 本地运行

Python 3.11+，已有环境可直接运行：

```bash
cd /Users/zengyy/code/insta360/agent
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

新环境可使用 uv：

```bash
uv venv --python 3.13
uv pip install --python .venv/bin/python -r requirements-lock.txt
```

PyCharm 选择 `.venv/bin/python`，运行模块 `uvicorn`，参数 `app.main:app --host 127.0.0.1 --port 8000`，工作目录 `agent`。Debug 不加 `--reload`。

服务自动加载本目录 `.env`，系统环境变量优先，修改后重启。配置项：

| 字段 | 用途 |
|---|---|
| LLM_API_KEY | 模型供应商密钥，只在服务端使用 |
| LLM_BASE_URL | API 前缀，程序追加 /chat/completions |
| LLM_MODEL | 供应商模型名称 |
| LLM_TIMEOUT_SECONDS | 模型时限，新接口最多 10 秒 |
| SERVICE_API_KEY | App/Apifox 访问本服务的独立令牌；公网必须填写 |

`.env.example` 仅是无密钥配置模板，不含模拟返回逻辑。实际 `.env` 被 Git 忽略，文件权限为 600。

## 目录

```text
app/                    配置、契约、HTTP 接口、模型调用与基础校验
tests/                 自动化测试（模型响应替身仅在此目录）
docs/                  接口、部署说明及 OpenAPI
deploy/                Nginx、systemd 与证书续期配置
requirements.txt       运行依赖范围
requirements-prod.lock 精确生产依赖，不含 pytest
requirements-lock.txt  完整开发/测试依赖
```

## 验证

```bash
.venv/bin/python -m pytest -q
```

本地 57 项测试通过；测试依赖有两条既有弃用提示。已验证服务器真实模型调用。代码的保真检查是启发式，不证明跨语言语义完全正确；手机仍负责代次、设置、截止时间、队列及 TTS 控制。
