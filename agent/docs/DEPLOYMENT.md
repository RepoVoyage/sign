# 服务器部署与使用

部署日期：2026-09-22。服务器账号 `ecs-user@101.37.234.129`，密码不保存在项目中。

## 访问地址

| 用途 | 地址 |
|---|---|
| Apifox / App 接口 | https://101.37.234.129/v1/polish |
| Swagger | https://101.37.234.129/docs |
| OpenAPI 导入 | https://101.37.234.129/openapi.json |
| 健康检查 | https://101.37.234.129/health |

Apifox 设置 POST、JSON 请求体，认证选择 Bearer Token。将本机 `agent/.env` 中 **SERVICE_API_KEY** 的值填入 Token。它已通过 SSH 同步到服务器，不要使用 LLM_API_KEY 或 SSH 密码代替。Swagger 的 Authorize 同样填写 SERVICE_API_KEY。

请求体按 `API.md`；rawChinese 来自当前 FINAL 冻结文本。健康检查公开可访问，不能证明上游模型稳定性。所有生成均调用真实模型，线上不包含固定模拟返回。

## 运行结构

```text
公网 HTTPS :443 → Nginx → 127.0.0.1:8000 → Python Agent → 模型供应商
```

- 代码目录：`/opt/insta360-agent`
- 配置文件：`/opt/insta360-agent/.env`，权限 600
- Python：uv 管理的 3.13.15，独立虚拟环境 `/opt/insta360-agent/.venv`
- systemd：`insta360-agent.service`，ecs-user 运行，开机启动、失败重启
- Nginx：`/etc/nginx/sites-available/insta360-agent`
- 证书：`/etc/letsencrypt/live/insta360-agent/`
- 续期：`insta360-cert-renew.timer`，每天两次检查，成功后重载 Nginx
- 公网端口：80 用于证书验证和 HTTPS 跳转；443 用于服务；8000 只监听本机，无需开放

IP 证书为 Let's Encrypt 短期证书，自动续期很重要，不要关闭 80 端口或续期任务。已完成 certbot dry-run 演练，续期与 Nginx 重载均成功。依据：[Let's Encrypt IP 证书与 Certbot 说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)。

Nginx 对 `/v1/polish` 按 IP 限制 30 次/分钟、突发 5 次、并发 3 个请求；请求体最多 128 KiB。超限返回 429/413，可能不是 JSON。同一出口 IP 的设备共享限额，这只是当前联调的基础保护。

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
sudo nginx -t
sudo systemctl list-timers insta360-cert-renew.timer
```

修改模型或访问令牌：编辑 `/opt/insta360-agent/.env` 后重启 Agent。只改本机 `.env` 不会自动更新服务器。不要把配置文件提交到 Git。

部署代码更新时，只同步 `app/` 和必要的依赖/配置文件；保留服务器 `.env` 和 `.venv`。依赖更新后：

```bash
cd /opt/insta360-agent
~/.local/bin/uv pip sync --python .venv/bin/python requirements-prod.lock
sudo systemctl restart insta360-agent
```

生产依赖锁不含 pytest；本地完整测试依赖用 `requirements-lock.txt`。Nginx 和 systemd 可复用配置保存在本机 `agent/deploy/`。

## 已验证与限制

本地 57 项自动化测试通过，生产应用目录已移除 mock/example/sample 逻辑。服务器真实模型成功返回中文和英文，公网健康检查、证书验证、OpenAPI 和 401 鉴权均通过；公网真实生成复核返回 200，用时约 1.4 秒。

发现一次公网模型请求超过 10 秒，返回预期的 `504 MODEL_TIMEOUT`；接口保留原契约的 10 秒期限，不通过放宽期限隐藏问题。云模型延迟有波动，App 必须正确处理 UNAVAILABLE/超时；本次部署不代表所有句子都能在期限内完成。
