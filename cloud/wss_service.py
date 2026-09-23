"""WSS 识别服务：实现 App 侧契约（交接报告-手语识别模型云端部署契约-2026-09-23.md §4/§5）。

上行：SESSION_INIT(text) → binary[13B头: uint64 ptsUs | uint32 len | uint8 flags] H.264 AU(length-prefixed)
      flags bit0=keyframe bit1=含参数集(CSD, 设为 decoder extradata, 不解码)
      控制：GAP_EVENT / EPOCH_RESET / HEARTBEAT / END
下行：RECOGNITION(RecognitionUpdate) / ERROR / HEARTBEAT
边界：腕速停顿分割（真实边界能力，非固定静默）；margin>=0.03 → RELIABLE，否则 UNCERTAIN（App 转人工核对）。
草稿：签约期内每 0.5s 一次部分识别（每类限 3 模板、缓冲限 30 帧控成本）。
model-b（第一视角）无数据 → ERROR MODEL_NOT_READY。
限制：Wholebody pose 实例跨会话共享，首版按单会话联调（多并发需每会话独立 pose 实例）。
用法：python wss_service.py [--port 8789] [--long-edge 1080] [--device cuda]
启动前 PATH 需含 nvidia/cudnn/bin 与 nvidia/cublas/bin（见安卓联调手册§2）。
"""
import argparse
import asyncio
import json
import struct

import av
import numpy as np
from websockets.asyncio.server import serve

import stream_service as ss
from align_candidates import align

DRAFT_HZ = 2.0
TPL_PER_CLASS = 3
BUF_CAP = 30


def conf_of(margin):
    return round(min(0.99, 0.5 + 2 * margin), 2)


class Session:
    def __init__(self, rec):
        self.rec = rec
        self.epoch = 0
        self.t0 = None
        self.last_pts_us = 0
        self.codec = None
        self.seg_id = 0

    def ts_of(self, pts_us):
        if self.t0 is None:
            self.t0 = pts_us
        return (pts_us - self.t0) / 1e6

    def draft(self):
        """部分识别：当前签约缓冲的最近邻句子（轻量配置）。"""
        buf = self.rec.buf
        if not self.rec.signing or len(buf) < 5:
            return None
        seg = buf[-BUF_CAP:]
        fs = np.stack([f for _, f, _ in seg])
        vs = np.stack([v for _, _, v in seg]).astype(bool)
        best = {}
        for c, members in self.rec.classes.items():
            scored = sorted((align(fs, vs, self.rec.tpl[m][0], self.rec.tpl[m][1])[1], m)
                            for m in members[:TPL_PER_CLASS])
            best[c] = scored[0]
        ranked = sorted(best.items(), key=lambda kv: kv[1][0])
        return ranked[0][0], ranked[1][1][0] - ranked[0][1][0]

    def update(self, draft_text, conf, margin, spans, boundary):
        return {'proto': 1, 'type': 'RECOGNITION', 'sequenceEpoch': self.epoch,
                'segmentId': self.seg_id, 'draftText': draft_text, 'confidence': conf,
                'tokenSpans': spans, 'boundary': boundary}


def _decode(codec, payload, pts_us):
    pk = av.Packet(payload)
    pk.pts = pts_us
    pk.dts = pts_us
    return codec.decode(pk)


async def emit_final(send, sess, ev):
    base_us = sess.t0 or 0
    spans = [{'text': w['word'],
              'startPtsUs': int(base_us + (ev['seg_start'] + w['start']) * 1e6),
              'endPtsUs': int(base_us + (ev['seg_start'] + w['end']) * 1e6),
              'stable': True} for w in ev['stream']]
    rel = 'UNCERTAIN' if ev['low_confidence'] else 'RELIABLE'
    await send(sess.update(ev['sentence_candidate'], conf_of(ev['confidence_margin']),
                           ev['confidence_margin'], spans,
                           {'cutoffPtsUs': int(base_us + ev['seg_end'] * 1e6),
                            'requiredFutureContextUs': 0, 'reliability': rel, 'source': 'MODEL'}))


async def handler(ws, cfg):
    sess = None
    loop = asyncio.get_event_loop()

    async def send(obj):
        await ws.send(json.dumps(obj, ensure_ascii=False))

    async def draft_loop():
        while True:
            await asyncio.sleep(1 / DRAFT_HZ)
            if sess is None or sess.codec is None:
                continue
            d = await loop.run_in_executor(None, sess.draft)
            if d is None:
                continue
            text, margin = d
            await send(sess.update(text, conf_of(margin), round(margin, 4), None,
                                   {'cutoffPtsUs': sess.last_pts_us, 'requiredFutureContextUs': 500000,
                                    'reliability': 'UNCERTAIN', 'source': 'MODEL'}))

    ticker = asyncio.create_task(draft_loop())
    try:
        async for msg in ws:
            if isinstance(msg, str):
                req = json.loads(msg)
                t = req.get('type')
                if t == 'SESSION_INIT':
                    if req.get('modelId') == 'model-b':
                        await send({'type': 'ERROR', 'code': 'MODEL_NOT_READY',
                                    'message': '第一视角模型无训练数据'})
                        continue
                    sess = Session(ss.StreamRecognizer(cfg['bank'], cfg['classes'],
                                                       cfg['words_of'], cfg['pose'], cfg['long_edge']))
                elif t == 'HEARTBEAT' and sess is not None:
                    await send({'type': 'HEARTBEAT', 't': req.get('t')})
                elif t == 'GAP_EVENT' and sess is not None:
                    sess.rec.signing, sess.rec.buf, sess.rec.low_run = False, [], 0
                elif t == 'EPOCH_RESET' and sess is not None:
                    sess.epoch = req.get('sequenceEpoch', sess.epoch + 1)
                    sess.rec.reset()
                elif t == 'END' and sess is not None:
                    for ev in await loop.run_in_executor(None, sess.rec.flush):
                        sess.seg_id += 1
                        await emit_final(send, sess, ev)
                    sess = None
                continue
            if sess is None or len(msg) < 13:
                continue
            pts_us, plen, flags = struct.unpack('>QIB', msg[:13])
            payload = msg[13:13 + plen]
            sess.last_pts_us = pts_us
            if flags & 0b10:  # CSD：设为 extradata，不解码
                sess.codec = av.CodecContext.create('h264', 'r')
                sess.codec.extradata = payload
                continue
            if sess.codec is None:
                continue
            for fr in await loop.run_in_executor(None, _decode, sess.codec, payload, pts_us):
                img = fr.to_ndarray(format='bgr24')
                for ev in await loop.run_in_executor(None, sess.rec.push, img, sess.ts_of(pts_us)):
                    sess.seg_id += 1
                    await emit_final(send, sess, ev)
    finally:
        ticker.cancel()


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=8789)
    ap.add_argument('--long-edge', type=int, default=1080)
    ap.add_argument('--device', default='cuda')
    ap.add_argument('--token', default='demo-token-20260923')
    args = ap.parse_args()
    ss.load_cfg(args.long_edge, args.device)
    cfg = dict(ss.CFG)

    async def process_request(conn, request):
        if request.headers.get('Authorization', '') != f'Bearer {args.token}':
            return conn.respond(401, 'unauthorized')
        return None

    print(f'wss service on :{args.port}', flush=True)
    async with serve(lambda w: handler(w, cfg), '0.0.0.0', args.port,
                     process_request=process_request):
        await asyncio.Future()


if __name__ == '__main__':
    asyncio.run(main())
