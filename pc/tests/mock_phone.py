"""手机侧模拟服务器（仅测试用）：按脚本逐条发 §9 消息，同时收客户端全部消息供断言。

复用 csl_capture 的 encode/read_message——客户端的分帧实现与手机端 FrameCodec
逐字节一致，模拟器用它即等价于手机在发。
"""
import select
import socket
import threading
import time

from csl_capture import encode, read_message


def i420_frame(w: int, h: int, fill: int) -> bytes:
    """确定性内容的紧凑 I420 帧（每字节 = fill），便于核对落盘内容"""
    size = w * h + 2 * ((w + 1) // 2) * ((h + 1) // 2)
    return bytes([fill]) * size


class MockPhone:
    def __init__(self, token: str = "test1234"):
        self.token = token
        self.received: list[dict] = []          # 客户端发来的全部 header
        self.session_id = "11111111-2222-3333-4444-555555555555"
        self._srv = socket.socket()
        self._srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._srv.bind(("127.0.0.1", 0))
        self._srv.listen(1)
        self.port = self._srv.getsockname()[1]
        self._thread: threading.Thread | None = None

    def start(self, script: list[tuple]) -> None:
        self._thread = threading.Thread(target=self._run, args=(script,), daemon=True)
        self._thread.start()

    def wait(self, timeout: float = 10.0) -> None:
        if self._thread:
            self._thread.join(timeout)

    # ---------------------------------------------------------------- 内部

    def _run(self, script: list[tuple]) -> None:
        conn, _ = self._srv.accept()
        threading.Thread(target=self._read_loop, args=(conn,), daemon=True).start()
        try:
            self._script(conn, script)
        except OSError:
            pass    # 客户端已断开（协议违规测试等）
        finally:
            time.sleep(0.2)   # 留出收尾消息到达时间
            conn.close()
            self._srv.close()

    def _read_loop(self, conn: socket.socket) -> None:
        # select 等到有数据再读：read_message 的 2s 截止等价于「首字节起算」
        try:
            while True:
                readable, _, _ = select.select([conn], [], [], 20.0)
                if not readable:
                    return
                header, _ = read_message(conn)
                self.received.append(header)
        except Exception:
            pass    # 客户端断开/超截止时间即收尾

    def _send(self, conn: socket.socket, header: dict, payload: bytes = b"") -> None:
        conn.sendall(encode({**header, "proto": 1}, payload))

    def _script(self, conn: socket.socket, script: list[tuple]) -> None:
        for step in script:
            kind = step[0]
            if kind == "auth_ok":
                self._send(conn, {"type": "AUTH_RESULT", "ok": True, "sessionId": self.session_id})
            elif kind == "auth_bad":
                self._send(conn, {"type": "AUTH_RESULT", "ok": False, "reason": "BAD_TOKEN"})
                return
            elif kind == "config":
                _, w, h, fps = step
                self._send(conn, {
                    "type": "SESSION_CONFIG", "captureSpecVersion": "1",
                    "pixelFormat": "I420", "width": w, "height": h, "captureFps": fps,
                    "bufferTargetMs": 2000, "maxPayloadBytes": 33_554_432,
                    "preprocessVersion": "i420-compact-1",
                    "cameraModel": "GO 3S", "cameraFirmware": "v9.0.59",
                })
            elif kind == "frame":
                _, idx, pts_us, w, h, payload = step
                self._send(conn, {
                    "type": "FRAME", "sessionId": self.session_id,
                    "streamGeneration": 7, "frameIndex": idx, "ptsUs": pts_us, "ptsUnit": "us",
                    "width": w, "height": h, "pixelFormat": "I420",
                    "preprocessVersion": "i420-compact-1", "payloadLen": len(payload),
                }, payload)
            elif kind == "bad_frame":
                # 声明长度与实际 payload 不符（触发客户端 §9.3 校验路径）
                _, idx, pts_us, w, h, payload, declared_len = step
                self._send(conn, {
                    "type": "FRAME", "sessionId": self.session_id,
                    "streamGeneration": 7, "frameIndex": idx, "ptsUs": pts_us, "ptsUnit": "us",
                    "width": w, "height": h, "pixelFormat": "I420",
                    "preprocessVersion": "i420-compact-1", "payloadLen": declared_len,
                }, payload)
            elif kind == "gap":
                _, reason, last_idx, start_pts, end_pts = step
                self._send(conn, {
                    "type": "GAP_EVENT", "sessionId": self.session_id, "reason": reason,
                    "lastFrameIndex": last_idx, "startPtsUs": start_pts, "endPtsUs": end_pts,
                })
            elif kind == "end":
                _, status, reason, last_idx, start_pts, end_pts = step
                header = {
                    "type": "END", "sessionId": self.session_id, "status": status,
                    "lastFrameIndex": last_idx, "validStartPtsUs": start_pts,
                    "validEndPtsUs": end_pts,
                }
                if reason:
                    header["reason"] = reason
                self._send(conn, header)
            elif kind == "sleep":
                time.sleep(step[1])
            elif kind == "close":
                conn.close()
                return
            else:
                raise ValueError(f"未知脚本步骤：{kind!r}")
