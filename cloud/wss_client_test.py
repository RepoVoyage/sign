"""WSS 契约 loopback 测试：把 MP4 的 H.264 包按契约§4格式推给 wss_service，打印§5事件。

用法：python wss_client_test.py <video.mp4> [ws://127.0.0.1:8789]
"""
import asyncio
import json
import struct
import sys

import av
import ctypes
from websockets.asyncio.client import connect


async def main():
    path = sys.argv[1]
    url = sys.argv[2] if len(sys.argv) > 2 else 'ws://127.0.0.1:8789'
    cont = av.open(path)
    vs = next(s for s in cont.streams if s.type == 'video')
    tb = float(vs.time_base)
    hdr = {'Authorization': 'Bearer ' + (sys.argv[3] if len(sys.argv) > 3 else 'demo-token-20260923')}
    async with connect(url, max_size=1 << 26, additional_headers=hdr) as ws:
        await ws.send(json.dumps({'proto': 1, 'type': 'SESSION_INIT', 'sessionId': 'test-1',
                                  'streamGeneration': 1, 'modelId': 'model-a',
                                  'media': {'codec': 'H264', 'packing': 'length-prefixed-au', 'fps': 30},
                                  'clientInfo': 'loopback-test/0.1'}))
        # CSD 首送：extradata 作为 flags bit1 的 AU
        if vs.extradata:
            await ws.send(struct.pack('>QIB', 0, len(vs.extradata), 0b10) + bytes(vs.extradata))
        n_ev = 0
        for pk in cont.demux(vs):
            if pk.size == 0:
                continue
            pts_us = int(pk.pts * tb * 1e6) if pk.pts is not None else 0
            flags = (0b01 if pk.is_keyframe else 0)
            data = ctypes.string_at(pk.buffer_ptr, pk.buffer_size)
            await ws.send(struct.pack('>QIB', pts_us, len(data), flags) + data)
            while True:  #  drain 下行
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=0.01)
                except (asyncio.TimeoutError, TimeoutError):
                    break
                ev = json.loads(msg)
                if ev.get('type') == 'RECOGNITION':
                    n_ev += 1
                    print(json.dumps(ev, ensure_ascii=False))
        await ws.send(json.dumps({'proto': 1, 'type': 'END', 'reason': 'test-done'}))
        while True:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=2)
            except (asyncio.TimeoutError, TimeoutError):
                break
            ev = json.loads(msg)
            if ev.get('type') == 'RECOGNITION':
                n_ev += 1
                print(json.dumps(ev, ensure_ascii=False))
        print(f'-- {n_ev} RECOGNITION events from {path}')


if __name__ == '__main__':
    asyncio.run(main())
