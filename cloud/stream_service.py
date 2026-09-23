"""连续流识别 HTTP 服务：帧进 → 词流事件出（安卓联调参考实现）。

契约见 `实时识别与安卓适配构思_2026-09-23.md` §3.5：
  POST /v1/sign/frame  {"session_id", "ts", "jpeg_b64"} -> {"events": [...]}
  POST /v1/sign/reset  {"session_id"} -> {"ok": true}
  GET  /v1/sign/health -> {"ok", "model", "long_edge", "templates"}
事件：{"sentence_candidate", "confidence_margin", "low_confidence", "needs_repeat",
       "stream": [{"word","start","end"}], "seg_start", "seg_end"}
运行：python stream_service.py [--port 8788] [--long-edge 1080] [--device cuda]
"""
import argparse
import base64
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'annotation_candidates_20260923'))
from align_candidates import JOINTS, WEIGHTS, align  # noqa: E402

V_THR, PAUSE_FRAMES, MIN_SEG, MARGIN_THR = 0.015, 4, 1.0, 0.03


class StreamRecognizer:
    """单会话在线状态机：pose -> 腕速分割 -> 整句模板最近邻 -> 词流事件。"""

    def __init__(self, bank, classes, words_of, pose, long_edge):
        self.bank = bank            # vid -> (feat, valid, times, dur)
        self.classes = classes      # sentence -> [vid]
        self.words_of = words_of    # vid -> [{label,start,end}]
        self.pose = pose            # rtmlib Wholebody
        self.long_edge = long_edge
        self.tpl = {vid: (bank[vid][0], bank[vid][1]) for vid in
                    {v for ms in classes.values() for v in ms}}
        self.reset()

    def reset(self):
        self.buf, self.signing, self.seg_start, self.low_run = [], False, 0.0, 0
        self.scale, self.events = None, []

    def _feat(self, frame):
        h, w = frame.shape[:2]
        if max(h, w) > self.long_edge:
            s = self.long_edge / max(h, w)
            frame = cv2.resize(frame, (int(w * s), int(h * s)))
        kp, sc = self.pose(frame)
        kp, sc = np.asarray(kp, np.float64), np.asarray(sc, np.float64)
        if kp.ndim == 3:  # (N,133,2)：取第一人；无人检出则跳过帧
            if kp.shape[0] == 0:
                return None, None
            kp, sc = kp[0], sc[0]
        centers = (kp[5] + kp[6]) / 2
        width = np.linalg.norm(kp[5] - kp[6])
        reliable = sc[5] > 0.3 and sc[6] > 0.3 and width > 5
        if reliable:
            self.scale = width
        if not self.scale:
            return None, None
        feat = np.clip((kp[JOINTS] - centers) / self.scale, -5, 5)
        valid = ((sc[JOINTS] > 0.3) & bool(reliable)).astype(bool)
        return feat, valid

    def push(self, frame, ts):
        feat, valid = self._feat(frame)
        if feat is None:
            return []
        out = []
        if self.buf:
            w = np.stack([self.buf[-1][1], feat])[:, 5:7]
            sp = float(np.linalg.norm(np.diff(w, axis=0), axis=-1).mean())
            low = sp < V_THR
            self.low_run = self.low_run + 1 if low else 0
        else:
            sp, low = 9.9, False
            self.low_run = 0
        self.buf.append((ts, feat, valid))
        if not self.signing and not low:
            self.signing, self.seg_start = True, ts
        elif self.signing and self.low_run >= PAUSE_FRAMES:
            if ts - 0.4 - self.seg_start >= MIN_SEG:
                ev = self._recognize(self.seg_start, ts - 0.4)
                if ev:
                    out.append(ev)
            self.signing, self.low_run, self.buf = False, 0, [(ts, feat, valid)]
        return out

    def flush(self):
        if self.signing and self.buf and self.buf[-1][0] - self.seg_start >= MIN_SEG:
            ev = self._recognize(self.seg_start, self.buf[-1][0])
            self.signing = False
            return [ev] if ev else []
        return []

    def _recognize(self, a, b):
        seg = [(t, f, v) for (t, f, v) in self.buf if a <= t <= b]
        if len(seg) < 5:
            return None
        fs = np.stack([f for _, f, _ in seg])
        vs = np.stack([v for _, _, v in seg]).astype(bool)  # 三元组 (t, feat, valid)
        seg_times = np.array([t - a for t, _, _ in seg])
        best = {}
        for c, members in self.classes.items():
            scored = sorted((align(fs, vs, self.tpl[m][0], self.tpl[m][1])[1], m) for m in members)
            best[c] = scored[0]
        ranked = sorted(best.items(), key=lambda kv: kv[1][0])
        pred, (dist, tvid) = ranked[0][0], ranked[0][1]
        margin = ranked[1][1][0] - dist
        candidates = [{'label': c, 'score': round(float(np.exp(-3 * d)), 3)} for c, (d, _) in ranked[:3]]
        tf, tv, tt, _ = self.bank[tvid]
        path, _ = align(tf, tv, fs, vs)
        mapped = np.array([np.median(seg_times[path[path[:, 0] == i, 1]]) for i in range(len(tt))])
        stream = []
        for w in sorted(self.words_of.get(tvid, []), key=lambda w: w['start']):
            s = round(max(0.0, float(np.interp(w['start'], tt, mapped))), 2)
            e = round(float(np.interp(w['end'], tt, mapped)), 2)
            if e - s >= 0.08:
                stream.append({'word': w['label'], 'start': s, 'end': e})
        sentence, conf = sentence_confidence(pred, candidates, stream)
        ev = {'sentence_candidate': sentence, 'confidence_margin': round(margin, 4),
              'sentence_confidence': conf, 'candidates': candidates,
              'low_confidence': margin < MARGIN_THR, 'needs_repeat': margin < MARGIN_THR,
              'stream': stream, 'seg_start': round(a, 2), 'seg_end': round(b, 2)}
        self.events.append(ev)
        return ev


def sentence_confidence(pred, candidates, stream):
    """P6 契约§3：服务侧组合 LLM 挑词成句并给句子置信度；LLM 不可用回落 CV top1+margin 校准。"""
    import os, urllib.request
    fallback_conf = round(min(0.99, 0.5 + 2 * (candidates[0]['score'] - (candidates[1]['score'] if len(candidates) > 1 else 0))), 2)
    try:
        base = os.environ['ANTHROPIC_BASE_URL'].rstrip('/')
        key = os.environ['ANTHROPIC_AUTH_TOKEN']
        words = ' '.join(w['word'] for w in stream) or '(无)'
        prompt = (f'手语识别词流（按时间序）：{words}\nCV 整句候选：'
                  + '、'.join(f"{c['label']}({c['score']})" for c in candidates)
                  + '\n请选出最可能的一句并给置信度(0-1)，只输出：句名|置信度')
        body = json.dumps({'model': 'qwen3.8-max', 'max_tokens': 120,
                           'messages': [{'role': 'user', 'content': prompt}]}).encode('utf-8')
        req = urllib.request.Request(base + '/v1/messages', data=body, headers={
            'content-type': 'application/json', 'x-api-key': key,
            'authorization': f'Bearer {key}', 'anthropic-version': '2023-06-01'})
        with urllib.request.urlopen(req, timeout=6) as resp:
            out = json.loads(resp.read().decode('utf-8'))
        text = ''.join(b.get('text', '') for b in out.get('content', []) if b.get('type') == 'text').strip()
        name, _, cs = text.rpartition('|')
        name = name.strip() or text.strip()
        conf = float(cs) if cs else fallback_conf
        known = [c['label'] for c in candidates]
        sentence = next((k for k in known if k in name or name in k), pred)
        return sentence, round(min(0.99, max(0.05, conf)), 2)
    except Exception:
        return pred, fallback_conf


SESSIONS = {}
CFG = {}


def make_recognizer():
    return StreamRecognizer(CFG['bank'], CFG['classes'], CFG['words_of'], CFG['pose'], CFG['long_edge'])


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('content-type', 'application/json; charset=utf-8')
        self.send_header('content-length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/v1/sign/health':
            self._json({'ok': True, 'model': 'wholebody-dtw-v0', 'long_edge': CFG['long_edge'],
                        'templates': sum(len(v) for v in CFG['classes'].values()),
                        'sentences': len(CFG['classes'])})
        else:
            self._json({'error': 'not found'}, 404)

    def do_POST(self):
        n = int(self.headers.get('content-length', 0))
        req = json.loads(self.rfile.read(n) or b'{}')
        sid = req.get('session_id', 'default')
        if self.path == '/v1/sign/reset':
            SESSIONS.pop(sid, None)
            return self._json({'ok': True})
        if self.path != '/v1/sign/frame':
            return self._json({'error': 'not found'}, 404)
        rec = SESSIONS.setdefault(sid, make_recognizer())
        img = np.frombuffer(base64.b64decode(req['jpeg_b64']), np.uint8)
        frame = cv2.imdecode(img, cv2.IMREAD_COLOR)
        if frame is None:
            return self._json({'error': 'bad jpeg'}, 400)
        events = rec.push(frame, float(req['ts']))
        if req.get('flush'):
            events += rec.flush()
        self._json({'events': events})


def load_cfg(long_edge, device):
    from rtmlib import Wholebody
    manifest = json.loads((HERE.parent / 'annotation_candidates_20260923' / 'manifest.json').read_text(encoding='utf-8'))
    bank, words_of, classes = {}, {}, {}
    for m in manifest:
        with np.load(HERE.parent / 'annotation_candidates_20260923' / f"v{m['id']:03d}_pose.npz", allow_pickle=False) as d:
            kp, sc, times = d['keypoints'].copy(), d['scores'].copy(), d['times'].copy()
            dur = float(d['duration'])
        centers = (kp[:, 5] + kp[:, 6]) / 2
        widths = np.linalg.norm(kp[:, 5] - kp[:, 6], axis=-1)
        rel = (sc[:, 5] > 0.3) & (sc[:, 6] > 0.3) & (widths > 5)
        scale = float(np.median(widths[rel]))
        feat = np.clip((kp[:, JOINTS] - centers[:, None]) / scale, -5, 5)
        valid = ((sc[:, JOINTS] > 0.3) & rel[:, None]).astype(bool)
        bank[m['id']] = (feat, valid, times, dur)
        classes.setdefault(m['sentence'], []).append(m['id'])
    ann = json.loads((HERE.parents[3] / '标注_粗标数据.json').read_text(encoding='utf-8'))
    vid_of = {m['file']: m['id'] for m in manifest}
    for r in ann['records']:
        words_of.setdefault(vid_of[r['file']], []).append(
            {'label': r['label'], 'start': r['start_seconds'], 'end': r['end_seconds']})
    CFG.update(bank=bank, classes=classes, words_of=words_of, long_edge=long_edge,
               pose=Wholebody(mode='lightweight', backend='onnxruntime', device=device))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=8788)
    ap.add_argument('--long-edge', type=int, default=1080)
    ap.add_argument('--device', default='cuda')
    args = ap.parse_args()
    load_cfg(args.long_edge, args.device)
    print(f'stream service on :{args.port} long_edge={args.long_edge} device={args.device}', flush=True)
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == '__main__':
    main()
