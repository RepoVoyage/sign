# 服务器部署与使用

部署日期：2026-09-23。服务器账号 `ecs-user@101.37.234.129`，密码不保存在项目中。当前 Agent 2.1.0 与独立 CV 测试服务同时运行。

## 访问地址

| 用途 | 地址 |
|---|---|
| Apifox / App 接口 | https://101.37.234.129/v1/polish |
| 五句手语补全 | https://101.37.234.129/v1/compose-signs |
| 孤立词 CV 识别 | https://101.37.234.129/v1/recognize |
| CV 健康检查 | https://101.37.234.129/cv/health |
| Swagger | https://101.37.234.129/docs |
| OpenAPI 导入 | https://101.37.234.129/openapi.json |
| 健康检查 | https://101.37.234.129/health |

Apifox 设置 POST、JSON 请求体，认证选择 Bearer Token。将本机 `agent/.env` 中 **SERVICE_API_KEY** 的值填入 Token。它已通过 SSH 同步到服务器，不要使用 LLM_API_KEY 或 SSH 密码代替。Swagger 的 Authorize 同样填写 SERVICE_API_KEY。

请求体按 [完整 API 文档](API.md)；App 对接步骤见 [交接文档](交接文档.md)。`/v1/polish` 的 rawChinese 来自当前 FINAL 冻结文本；`/v1/compose-signs` 接收 CV 候选，只选择固定五句。健康检查公开可访问，不能证明上游模型稳定性。生成均调用真实模型，线上不包含固定模拟返回。

## 运行结构

```text
公网 HTTPS :443 → Nginx → 127.0.0.1:8000 → Python Agent → 模型供应商
                     ↳ 127.0.0.1:8765 → CV MediaPipe + NumPy 分类器
```

- 代码目录：`/opt/insta360-agent`
- 配置文件：`/opt/insta360-agent/.env`，权限 600
- Python：uv 管理的 3.13.15，独立虚拟环境 `/opt/insta360-agent/.venv`
- systemd：`insta360-agent.service`，ecs-user 运行，开机启动、失败重启
- CV：`/opt/insta360-cv`，独立 Python 3.12 虚拟环境、`insta360-cv.service`，模型在 `models/hand_landmarker.task` 与 `outputs/classifier_numpy.npz`；令牌只在 `.env` 的 `CV_SERVICE_TOKEN` 中
- Nginx：`/etc/nginx/sites-available/insta360-agent`
- 证书：`/etc/letsencrypt/live/insta360-agent/`
- 续期：`insta360-cert-renew.timer`，每天两次检查，成功后重载 Nginx
- 公网端口：80 用于证书验证和 HTTPS 跳转；443 用于服务；8000 只监听本机，无需开放

IP 证书为 Let's Encrypt 短期证书，自动续期很重要，不要关闭 80 端口或续期任务。已完成 certbot dry-run 演练，续期与 Nginx 重载均成功。依据：[Let's Encrypt IP 证书与 Certbot 说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)。

Nginx 对 `/v1/polish` 和 `/v1/compose-signs` 按 IP 限制 30 次/分钟、突发 5 次、并发 3 个请求；默认请求体最多 128 KiB。`/v1/recognize` 单独允许最大 32 MiB，并按 IP 限制 12 次/分钟、突发 2 次、并发 2 个请求；CV 服务本身一次处理一个视频。超限返回 429/413，可能不是 JSON。同一出口 IP 的设备共享限额，这只是当前联调的基础保护。

服务令牌用于当前团队联调，后续多用户产品应替换为正式用户认证及配额机制。保真检查和真实手机完整流程仍需进一步验收。

## 日常维护

先登录：

```bash
ssh ecs-user@101.37.234.129
```

检查与重启：

```bash
sudo systemctl status insta360-agent --no-pager
sudo journalctl -u insta360-agent -n 100 --no-pager
sudo systemctl restart insta360-agent
sudo systemctl status insta360-cv --no-pager
sudo journalctl -u insta360-cv -n 100 --no-pager
sudo systemctl restart insta360-cv
sudo nginx -t
sudo systemctl list-timers insta360-cert-renew.timer
```

修改模型或访问令牌：编辑 `/opt/insta360-agent/.env` 后重启 Agent。只改本机 `.env` 不会自动更新服务器。不要把配置文件提交到 Git。

部署 Agent 代码更新时，只同步 `app/` 和必要的依赖/配置文件；保留服务器 `.env` 和 `.venv`。依赖更新后：

```bash
cd /opt/insta360-agent
~/.local/bin/uv pip sync --python .venv/bin/python requirements-prod.lock
sudo systemctl restart insta360-agent
```

CV 更新需同步 `server.py`、`extract.py`、`inference_numpy.py`、`requirements-server.txt`、MediaPipe 模型文件及从训练 checkpoint 导出的 `classifier_numpy.npz`；保留 `/opt/insta360-cv/.env`。两套 Python 环境不要混用。先在服务器本机检查 CV `/health` 与一段实际 MP4 的 `/v1/recognize`，再测试公网 HTTPS。修改 Nginx 前备份当前配置，运行 `sudo nginx -t` 成功后才 reload。此次部署的旧 Agent 代码及 Nginx 配置备份分别在 `/opt/insta360-agent/app.backup-605bdac` 和 `/etc/nginx/sites-available/insta360-agent.backup-605bdac`。

生产依赖锁不含 pytest；本地完整测试依赖用 `requirements-lock.txt`。Nginx 和 systemd 可复用配置保存在本机 `agent/deploy/`。

## 已验证与限制

本地 Agent 74 项自动化测试通过；服务器真实模型已对三个词候选返回“我想回家”，公网 CV 上传一段真实 MP4 返回三个候选，旧 `/v1/polish` 回归请求也返回 200。服务端模型、Nginx 和系统服务的健康检查通过。完整视频→CV→Agent 的选定片段联调通过，但这不代表五句在新场次的准确率。

发现一次公网模型请求超过 10 秒，返回预期的 `504 MODEL_TIMEOUT`；接口保留原契约的 10 秒期限，不通过放宽期限隐藏问题。云模型延迟有波动，App 必须正确处理 UNAVAILABLE/超时；本次部署不代表所有句子都能在期限内完成。

五句补全接口向当前模型发送 `enable_thinking=false`、`max_tokens=64`，减少短句选择耗时；原有 `/v1/polish` 不受影响。当前模型接口已验证接受这两个参数，但仍可能因网络或上游延迟超过 10 秒。更换模型供应商时需先验证参数兼容性和实际响应时间。
