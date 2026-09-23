#!/usr/bin/env python3
"""CSL 训练采集 PC 客户端（API.md §9 USB 线协议 v1 接收端 + §2.8.3 授权流程）。

前置：`adb forward tcp:9999 tcp:9999`（多手机各自 -s 指定序列号与端口）。
用法（授权四要素 + 删除范围缺一不开始持久化，ARCHITECTURE.md §2.8.3）：
  uv run csl_capture.py --token <训练版通知栏显示的配对令牌> --out <素材目录> \
      --subject <匿名受试者编号> --consent-ref <授权记录关联> \
      --access <访问者> --retention <90d|ISO 日期> --delete-scope <删除范围> \
      [--label <人工标注文本>] [--position <机位>] [--duration 60]

落盘结构（一个连接 = 一个采集段，段内追加写、段间不拼接，§9.4）：
  <out>/<时间戳>_<sessionId>/
    authorization.json  授权记录 + 相机/采样配置 + 标签（可追溯）
    frames.i420         紧凑 I420 像素逐帧追加
    meta.jsonl          每帧一行：frameIndex/ptsUs/尺寸/offset/len（可重切单帧）
    summary.json        END 结果 + 完整性校验账目 + 标签起止

校验（§9.3 PC 侧）：每帧 payloadLen 与尺寸×I420 计算值一致，拒绝未知格式；
序号连续性在 END 时核对，status=COMPLETE 但有缺口/序号不符按协议违规上报。

退出码：0 = END COMPLETE 且校验通过，或 --duration 到时主动停；
2 = AUTH 失败；3 = 样本不完整（素材已保存）；4 = 协议/完整性校验违规；
5 = 授权未成立（要素缺失/保留期限不可解析，未持久化任何数据）。
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import select
import socket
import struct
import sys
import time
from pathlib import Path

PROTO = 1
CLIENT_INFO = "pc-training/0.1"

MSG_TIMEOUT_S = 2.0          # 首字节起读满整条消息（§9.2）
HEARTBEAT_INTERVAL_S = 2.0   # 空闲心跳（§9.1）
IDLE_DISCONNECT_S = 6.0      # 6 秒无完整有效消息即断开（§9.1）
HEADER_MAX = 16 * 1024
IMAGE_PAYLOAD_MAX = 32 * 1024 * 1024
CONTROL_PAYLOAD_MAX = 64 * 1024


class ProtocolError(Exception):
    """协议违规（长度越界/格式不符/版本不符/校验失败）——断开连接"""


class Disconnected(Exception):
    """对端关闭或静默超时——按不完整处理"""


# --------------------------------------------- 分帧（与手机端 FrameCodec 一致）


def encode(header: dict, payload: bytes = b"") -> bytes:
    data = json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return struct.pack(">I", len(data)) + data + payload


def send(sock: socket.socket, header: dict, payload: bytes = b"") -> None:
    sock.sendall(encode({**header, "proto": PROTO}, payload))


def i420_size(w: int, h: int) -> int:
    """紧凑 I420 字节数：Y(W×H) + U/V 各 (⌈W/2⌉×⌈H/2⌉)"""
    return w * h + 2 * ((w + 1) // 2) * ((h + 1) // 2)


def _recv_full(sock: socket.socket, n: int, deadline: float, what: str) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ProtocolError(f"{what} 读取超时（>{MSG_TIMEOUT_S}s）")
        sock.settimeout(remaining)
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise Disconnected(f"EOF（{what} 读到 {len(buf)}/{n}）")
        buf += chunk
    return bytes(buf)


def read_message(sock: socket.socket) -> tuple[dict, bytes]:
    """读一条完整消息（调用前 select 已确认有数据，截止时间自此起算）。"""
    deadline = time.monotonic() + MSG_TIMEOUT_S
    (hlen,) = struct.unpack(">I", _recv_full(sock, 4, deadline, "长度前缀"))
    if not 0 < hlen <= HEADER_MAX:
        raise ProtocolError(f"headerLen 越界：{hlen}")
    raw = _recv_full(sock, hlen, deadline, "header")
    try:
        header = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise ProtocolError(f"header 解析失败：{e}") from e
    plen = header.get("payloadLen", 0)
    cap = IMAGE_PAYLOAD_MAX if header.get("type") == "FRAME" else CONTROL_PAYLOAD_MAX
    if not isinstance(plen, int) or not 0 <= plen <= cap:
        raise ProtocolError(f"payloadLen 越界：{plen!r}")
    payload = _recv_full(sock, plen, deadline, "payload") if plen else b""
    return header, payload


def require_type(header: dict, expected: str) -> None:
    if header.get("proto") != PROTO:
        raise ProtocolError(f"协议版本不符：{header.get('proto')!r}")
    if header.get("type") != expected:
        raise ProtocolError(f"期待 {expected}，收到 {header.get('type')!r}")


def _wall_now() -> str:
    return datetime.datetime.now().astimezone().isoformat(timespec="seconds")


def parse_retention(raw: str) -> dict | None:
    """保留期限解析：'90d'（起始日起 N 天）或 ISO 日期；非法返回 None。"""
    m = re.fullmatch(r"\s*(\d+)\s*d\s*", raw)
    if m:
        expires = datetime.date.today() + datetime.timedelta(days=int(m.group(1)))
        return {"raw": raw.strip(), "expiresOn": expires.isoformat()}
    try:
        datetime.date.fromisoformat(raw.strip())
    except ValueError:
        return None
    return {"raw": raw.strip(), "expiresOn": raw.strip()}


# --------------------------------------------------------------------- 采集段


class SegmentStore:
    """一个连接一个采集段：授权记录 + 追加写帧 + 逐帧元数据 + END 完整性校验账目。"""

    def __init__(self, root: Path, session_id: str, config: dict, auth: dict):
        self.dir = root / f"{time.strftime('%Y%m%d_%H%M%S')}_{session_id[:8]}"
        self.dir.mkdir(parents=True)
        self.session_id = session_id
        self.config = config
        self.auth = auth
        self.started_at = _wall_now()
        self._frames = open(self.dir / "frames.i420", "wb")
        self._meta = open(self.dir / "meta.jsonl", "w", encoding="utf-8")
        # 授权记录（§2.8.3：受试者/授权关联/相机固件/机位/采样配置/标签，可追溯）
        (self.dir / "authorization.json").write_text(
            json.dumps({
                "subjectId": auth["subjectId"],
                "consentRef": auth["consentRef"],
                "accessors": auth["accessors"],
                "retention": auth["retention"],
                "deleteScope": auth["deleteScope"],
                "position": auth.get("position"),
                "label": auth.get("label"),
                "sessionId": session_id,
                "camera": {
                    "model": config.get("cameraModel"),
                    "firmware": config.get("cameraFirmware"),
                },
                "captureSpec": {
                    "captureSpecVersion": config.get("captureSpecVersion"),
                    "pixelFormat": config.get("pixelFormat"),
                    "width": config.get("width"),
                    "height": config.get("height"),
                    "captureFps": config.get("captureFps"),
                    "preprocessVersion": config.get("preprocessVersion"),
                },
                "modelVersion": None,   # 训练后回填；不把模型猜测当人工真值
                "captureStartedAt": self.started_at,
            }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        self.frame_count = 0
        self.byte_count = 0
        self.first_pts_us: int | None = None
        self.last_pts_us: int | None = None
        self.last_index: int | None = None
        self.gaps: list[dict] = []
        self.missing: list[list[int]] = []    # [after, before] 序号缺口
        self._offset = 0

    def write_frame(self, header: dict, payload: bytes) -> None:
        idx = header["frameIndex"]
        if self.last_index is not None and idx != self.last_index + 1:
            self.missing.append([self.last_index, idx])
        self.last_index = idx
        if self.first_pts_us is None:
            self.first_pts_us = header["ptsUs"]
        self.last_pts_us = header["ptsUs"]
        self._frames.write(payload)
        self._meta.write(json.dumps({
            "frameIndex": idx,
            "ptsUs": header["ptsUs"],
            "streamGeneration": header.get("streamGeneration"),
            "width": header["width"],
            "height": header["height"],
            "pixelFormat": header["pixelFormat"],
            "preprocessVersion": header.get("preprocessVersion"),
            "offset": self._offset,
            "len": len(payload),
        }, ensure_ascii=False) + "\n")
        self._offset += len(payload)
        self.frame_count += 1
        self.byte_count += len(payload)

    def record_gap(self, header: dict) -> None:
        self.gaps.append({
            "reason": header.get("reason"),
            "lastFrameIndex": header.get("lastFrameIndex"),
            "startPtsUs": header.get("startPtsUs"),
            "endPtsUs": header.get("endPtsUs"),
        })

    def verify_end(self, header: dict) -> list[str]:
        """END 完整性校验（§9.3：序号连续性在 END 核对；COMPLETE 不得有丢失）"""
        problems = []
        status = header.get("status")
        if status not in ("COMPLETE", "INCOMPLETE"):
            problems.append(f"未知 END status：{status!r}")
        last_index = self.last_index if self.last_index is not None else -1
        if header.get("lastFrameIndex") != last_index:
            problems.append(
                f"END.lastFrameIndex={header.get('lastFrameIndex')!r} 与已收末帧 {last_index} 不符")
        if self.missing:
            problems.append(f"序号不连续：{self.missing}")
        if status == "COMPLETE" and self.gaps:
            problems.append(f"status=COMPLETE 但存在 GAP_EVENT：{[g['reason'] for g in self.gaps]}")
        return problems

    def close(self, summary: dict) -> None:
        self._frames.close()
        self._meta.close()
        summary["dir"] = str(self.dir)
        (self.dir / "summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


# --------------------------------------------------------------------- 主体


def check_frame(header: dict, payload: bytes) -> None:
    """FRAME 侧校验（§9.3：声明长度与尺寸×格式一致，拒绝未知格式）"""
    if header.get("pixelFormat") != "I420":
        raise ProtocolError(f"不支持的 pixelFormat：{header.get('pixelFormat')!r}")
    w, h = header.get("width"), header.get("height")
    if not isinstance(w, int) or not isinstance(h, int) or w <= 0 or h <= 0:
        raise ProtocolError(f"非法尺寸：{w}×{h}")
    expected = i420_size(w, h)
    if header.get("payloadLen") != len(payload) or len(payload) != expected:
        raise ProtocolError(
            f"FRAME payload 长度不符：payloadLen={header.get('payloadLen')}，"
            f"实际 {len(payload)}，{w}×{h} I420 应为 {expected}")


def run(args: argparse.Namespace, auth: dict) -> int:
    started = time.monotonic()
    try:
        sock = socket.create_connection((args.host, args.port), timeout=5.0)
    except OSError as e:
        print(f"连接失败（先 adb forward tcp:{args.port}？）：{e}", file=sys.stderr)
        return 1
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    store: SegmentStore | None = None
    result = ("INCOMPLETE", None, 3)   # (status, reason, exit_code)
    end_problems: list[str] = []       # END 校验问题（非 END 收尾时为空——段本就不完整）

    try:
        # 认证（§9.1：连接后 5s 内完成）——token 不入任何输出
        send(sock, {"type": "AUTH_REQ", "token": args.token, "clientInfo": CLIENT_INFO})
        header, _ = read_message(sock)
        require_type(header, "AUTH_RESULT")
        if not header.get("ok"):
            print(f"认证失败：{header.get('reason', '?')}", file=sys.stderr)
            return 2
        session_id = header.get("sessionId")
        if not isinstance(session_id, str) or not session_id:
            raise ProtocolError("AUTH_RESULT 缺 sessionId")

        header, _ = read_message(sock)
        require_type(header, "SESSION_CONFIG")
        config = header
        if config.get("pixelFormat") != "I420":
            raise ProtocolError(f"不支持的 pixelFormat：{config.get('pixelFormat')!r}")
        send(sock, {"type": "CONFIG_ACK", "accepted": True})

        store = SegmentStore(Path(args.out), session_id, dict(config), auth)
        last_send = last_recv = time.monotonic()

        # 主循环：select 收消息，空闲 2s 发心跳（§9.1）
        while True:
            now = time.monotonic()
            if args.duration is not None and now - started >= args.duration:
                result = ("CLIENT_STOP", f"--duration {args.duration}s 到时", 0)
                break
            if now - last_recv >= IDLE_DISCONNECT_S:
                raise Disconnected(f"{IDLE_DISCONNECT_S}s 无完整消息")
            wait = HEARTBEAT_INTERVAL_S - (now - last_send)
            if wait <= 0:
                send(sock, {"type": "HEARTBEAT", "t": time.monotonic_ns() // 1_000_000})
                last_send = time.monotonic()
                continue
            readable, _, _ = select.select([sock], [], [], wait)
            if not readable:
                continue    # 回到循环顶：判定心跳/时限
            header, payload = read_message(sock)
            last_recv = time.monotonic()
            mtype = header.get("type")
            if mtype == "HEARTBEAT":
                continue
            if header.get("proto") != PROTO:
                raise ProtocolError(f"协议版本不符：{header.get('proto')!r}")
            if header.get("sessionId") != session_id:
                raise ProtocolError("sessionId 与认证结果不符")
            if mtype == "FRAME":
                check_frame(header, payload)
                store.write_frame(header, payload)
            elif mtype == "GAP_EVENT":
                store.record_gap(header)
            elif mtype == "END":
                problems = store.verify_end(header)
                end_problems = problems
                status = header.get("status")
                for p in problems:
                    print(f"校验问题：{p}", file=sys.stderr)
                if problems and status == "COMPLETE":
                    result = ("VERIFICATION_FAILED", "END COMPLETE 但完整性校验未通过", 4)
                elif status == "COMPLETE":
                    result = ("COMPLETE", header.get("reason"), 0)
                else:
                    result = ("INCOMPLETE", header.get("reason"), 3)
                break
            else:
                print(f"忽略未知消息类型：{mtype!r}", file=sys.stderr)

    except ProtocolError as e:
        print(f"协议违规，断开：{e}", file=sys.stderr)
        result = ("PROTOCOL_ERROR", str(e), 4)
    except (Disconnected, OSError) as e:
        print(f"连接中断：{e}", file=sys.stderr)
        result = ("DISCONNECTED", str(e), 3)
    except KeyboardInterrupt:
        print("用户中断", file=sys.stderr)
        result = ("INCOMPLETE", "CLIENT_ABORT", 3)
    finally:
        try:
            sock.close()
        except OSError:
            pass
        if store is not None:
            elapsed = time.monotonic() - started
            status, reason, _ = result
            summary = {
                "sessionId": store.session_id,
                "status": status,
                "reason": reason,
                "authorization": {
                    "subjectId": store.auth["subjectId"],
                    "consentRef": store.auth["consentRef"],
                },
                "label": store.auth.get("label"),
                "startedAt": store.started_at,
                "endedAt": _wall_now(),
                "frames": store.frame_count,
                "bytes": store.byte_count,
                "elapsedS": round(elapsed, 3),
                "avgFps": round(store.frame_count / elapsed, 2) if elapsed > 0 else 0.0,
                "throughputMiBs": round(store.byte_count / elapsed / 1024 / 1024, 2) if elapsed > 0 else 0.0,
                "firstPtsUs": store.first_pts_us,
                "lastPtsUs": store.last_pts_us,
                "lastFrameIndex": store.last_index if store.last_index is not None else -1,
                "gaps": store.gaps,
                "missing": store.missing,
                "problems": end_problems,
                "config": store.config,
            }
            # END 校验结果记入 summary（非 END 收尾时 problems 为空——段本就不完整）
            store.close(summary)
            print(
                f"会话 {store.session_id[:8]}：{store.frame_count} 帧"
                f"（{store.byte_count / 1024 / 1024:.1f} MiB，"
                f"{summary['avgFps']:.1f} fps，{summary['throughputMiBs']:.1f} MiB/s）"
            )
            if store.gaps or store.missing:
                print(f"GAP {len(store.gaps)} 次，序号缺口 {len(store.missing)} 处", file=sys.stderr)
            print(f"状态：{status}" + (f"（{reason}）" if reason else ""))
            print(f"素材目录：{store.dir}")
    return result[2]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="CSL 训练采集 PC 客户端（API.md §9 + §2.8.3）")
    parser.add_argument("--token", required=True, help="训练版通知栏显示的配对令牌")
    parser.add_argument("--out", required=True, help="素材落盘目录")
    parser.add_argument("--host", default="127.0.0.1", help="默认 127.0.0.1（adb forward）")
    parser.add_argument("--port", type=int, default=9999)
    parser.add_argument("--duration", type=float, default=None,
                        help="到时主动断开（吞吐测试用，样本按 CLIENT_STOP 记）")
    # §2.8.3 授权要素（缺一不持久化）
    parser.add_argument("--subject", help="匿名受试者编号（必填）")
    parser.add_argument("--consent-ref", help="授权记录关联，如文件路径/编号（必填）")
    parser.add_argument("--access", help="访问者范围（必填）")
    parser.add_argument("--retention", help="保留期限：'90d' 或 ISO 日期（必填）")
    parser.add_argument("--delete-scope", help="到期删除范围（必填）")
    parser.add_argument("--label", help="人工标注文本（可空；不用模型猜测当真值）")
    parser.add_argument("--position", help="机位描述（可空）")
    args = parser.parse_args(argv)

    required = [
        ("--subject", args.subject, "匿名受试者编号"),
        ("--consent-ref", args.consent_ref, "授权记录关联"),
        ("--access", args.access, "访问者范围"),
        ("--retention", args.retention, "保留期限"),
        ("--delete-scope", args.delete_scope, "删除范围"),
    ]
    missing = [flag for flag, val, _ in required if not val]
    if missing:
        print(f"授权未成立，缺少 {', '.join(missing)}"
              f"（§2.8.3：未明确授权或期限不开始持久化，未连接未落盘）", file=sys.stderr)
        return 5
    retention = parse_retention(args.retention)
    if retention is None:
        print(f"保留期限无法解析：{args.retention!r}（支持 '90d' 或 ISO 日期）", file=sys.stderr)
        return 5
    auth = {
        "subjectId": args.subject,
        "consentRef": args.consent_ref,
        "accessors": args.access,
        "retention": retention,
        "deleteScope": args.delete_scope,
        "position": args.position,
        "label": args.label,
    }
    return run(args, auth)


if __name__ == "__main__":
    sys.exit(main())
