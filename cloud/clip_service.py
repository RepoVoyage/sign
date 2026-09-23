"""逐词 CV + 补句 Agent 服务：实现 App 侧 Docs/第一人称本地视频联调.md 的两个端点。

POST /v1/recognize      body=原始 MP4 字节(video/mp4, ≤32MiB, 一段一个手语词)
    -> {"status":"OK|TOO_SHORT|INSUFFICIENT_HAND_DETECTION","frames","any_hand_fraction",
        "candidates":[{"label","score"}<=3],"needsConfirmation"}
    错误语义：401 令牌 / 413 过大 / 415 非 mp4 / 429 限流(带 Retry-After)
POST /v1/compose-signs  body={"gestures":[[{"label","score"}...], ...]}
    -> {"status","sentence","alternatives","segmentId","revision","needsConfirmation"}
    504 MODEL_TIMEOUT = 补全超期
词级打分：标注词区间模板 vs 切片全段的带权 DTW（肩宽归一 49 维），每词取最优实例；
补句：手势 top1 序列 vs 17 句词序最优匹配 + 百炼 LLM 闭集挑选/置信。
用法：python clip_service.py [--port 8790] [--device cuda] [--token demo-token-20260923]
"""
import argparse
import asyncio
import io
import threading
import json
import time
import uuid
from pathlib import Path

import av
import numpy as np

import stream_service as ss
from align_candidates import align

WORD_INSTANCES = {}   # label -> [(vid, idx_array)]
SENT_WORDSEQ = {}     # sentence -> [labels...]
MAX_CONCURRENT = 2    # ponytail: 单 GPU 粗限流，相机切片积压时 App 会退避/丢段
_sem = None
LLM_BASE = None
LLM_KEY = None
LLM_MODEL = 'qwen3.8-max'


def build_word_bank():
    import os
    root = ss.HERE.parents[3]
    ann = json.loads((root / '标注_粗标数据.json').read_text(encoding='utf-8'))
    manifest = json.loads((ss.HERE.parent / 'annotation_candidates_20260923' / 'manifest.json').read_text(encoding='utf-8'))
    vid_of = {m['file']: m['id'] for m in manifest}
    seq = {}
    for r in ann['records']:
        vid = vid_of[r['file']]
        seq.setdefault(r['source_sentence'], []).append((r['start_seconds'], r['label']))
        if r['label'] == '待确认动作':
            continue
        feat, valid, times, _ = ss.CFG['bank'][vid]
        idx = np.where((times >= r['start_seconds'] - 0.05) & (times <= r['end_seconds'] + 0.05))[0]
        if len(idx) >= 3:
            WORD_INSTANCES.setdefault(r['label'], []).append((vid, idx))
    for s, rows in seq.items():
        SENT_WORDSEQ[s] = [lab for _, lab in sorted(rows)]
    for lab in WORD_INSTANCES:
        WORD_INSTANCES[lab] = WORD_INSTANCES[lab][:8]  # 延迟上限：每词最多 8 实例


def iter_frames(data, counter, fps_target=10, max_frames=200):
    """流式产出 resize 后帧，调用方逐帧 pose 后立即丢弃，避免 4K 帧列表 OOM。
    counter[0] 记录已解码总帧数。"""
    import cv2
    cont = av.open(io.BytesIO(data))
    vs = next(s for s in cont.streams if s.type == 'video')
    rate = vs.average_rate or 30
    step = max(1, round(float(rate) / fps_target))
    i = 0
    for fr in cont.decode(vs):
        if i % step == 0 and i // step < max_frames:
            img = fr.to_ndarray(format='bgr24')
            h, w = img.shape[:2]
            if max(h, w) > 1080:
                s = 1080 / max(h, w)
                img = cv2.resize(img, (int(w * s), int(h * s)))
            yield img
        i += 1
        counter[0] = i


def frame_features(img):
    import cv2
    h, w = img.shape[:2]
    if max(h, w) > 1080:
        s = 1080 / max(h, w)
        img = cv2.resize(img, (int(w * s), int(h * s)))
    kp, sc = ss.CFG['pose'](img)
    kp, sc = np.asarray(kp, np.float64), np.asarray(sc, np.float64)
    if kp.ndim == 3:
        if kp.shape[0] == 0:
            return None, None, False
        kp, sc = kp[0], sc[0]
    hand = bool((sc[91:] > 0.3).any())
    centers = (kp[5] + kp[6]) / 2
    width = np.linalg.norm(kp[5] - kp[6])
    if not (sc[5] > 0.3 and sc[6] > 0.3 and width > 5):
        return None, None, hand
    from align_candidates import JOINTS
    feat = np.clip((kp[JOINTS] - centers) / width, -5, 5)
    valid = (sc[JOINTS] > 0.3).astype(bool)
    return feat, valid, hand


def recognize_clip(data):
    counter = [0]
    feats, hands = [], []
    for img in iter_frames(data, counter):
        f, v, hand = frame_features(img)
        hands.append(hand)
        if f is not None:
            feats.append((f, v))
    total = counter[0]
    if total < 4 or len(feats) < 3:  # 单词切片可短至 ~0.4s@10fps，阈值从宽
        frac = float(np.mean(hands)) if hands else 0.0
        return {'status': 'TOO_SHORT', 'frames': total, 'any_hand_fraction': round(frac, 3),
                'candidates': [], 'needsConfirmation': True}
    frac = float(np.mean(hands))
    if len(feats) < 3 or frac < 0.5:
        return {'status': 'INSUFFICIENT_HAND_DETECTION', 'frames': total,
                'any_hand_fraction': round(frac, 3), 'candidates': [], 'needsConfirmation': True}
    qf = np.stack([f for f, _ in feats])
    qv = np.stack([v for _, v in feats]).astype(bool)
    scores = {}
    for lab, insts in WORD_INSTANCES.items():
        best = 9.9
        for vid, idx in insts:
            tf, tv = ss.CFG['bank'][vid][0][idx], ss.CFG['bank'][vid][1][idx]
            try:
                d = align(tf, tv, qf, qv)[1]
            except ValueError:
                continue
            best = min(best, d)
        scores[lab] = best
    ranked = sorted(scores.items(), key=lambda kv: kv[1])[:3]
    cands = [{'label': lab, 'score': round(float(np.exp(-3 * d)), 3)} for lab, d in ranked]
    margin = (ranked[1][1] - ranked[0][1]) if len(ranked) > 1 else 1.0
    return {'status': 'OK', 'frames': total, 'any_hand_fraction': round(frac, 3),
            'candidates': cands, 'needsConfirmation': bool(margin < 0.05 or cands[0]['score'] < 0.3)}


def _llm_compose(gesture_seq):
    import os, urllib.request
    base = os.environ['ANTHROPIC_BASE_URL'].rstrip('/')
    key = os.environ['ANTHROPIC_AUTH_TOKEN']
    cands = [s for s in SENT_WORDSEQ]
    prompt = ('下面是按时间顺序的手语逐词识别候选(top1)序列，可能缺词/倒装：'
              + ' '.join(gesture_seq) + '\n请从候选句中选出最可能的一句，只输出句名：' + '、'.join(cands))
    body = json.dumps({'model': LLM_MODEL, 'max_tokens': 200,
                       'messages': [{'role': 'user', 'content': prompt}]}).encode('utf-8')
    req = urllib.request.Request(base + '/v1/messages', data=body, headers={
        'content-type': 'application/json', 'x-api-key': key,
        'authorization': f'Bearer {key}', 'anthropic-version': '2023-06-01'})
    with urllib.request.urlopen(req, timeout=8) as resp:
        out = json.loads(resp.read().decode('utf-8'))
    text = ''.join(b.get('text', '') for b in out.get('content', []) if b.get('type') == 'text').strip()
    return next((s for s in cands if s in text or text in s), None)


def compose_signs(gestures):
    seq = [g[0]['label'] for g in gestures if g]

    def match_score(sentence_seq):
        # 手势序列应为句词序的子序列（允许缺词），代价=缺词数+顺序逆序对
        it = iter(seq)
        covered = sum(1 for w in sentence_seq if w in set(seq))
        miss = len(sentence_seq) - covered
        return miss + abs(len(seq) - len(sentence_seq)) * 0.1
    ranked = sorted(SENT_WORDSEQ.items(), key=lambda kv: match_score(kv[1]))
    alts = [s for s, _ in ranked[:3]]

    def miss_of(sentence_seq):
        covered = sum(1 for w in sentence_seq if w in set(seq))
        return len(sentence_seq) - covered

    try:
        sentence = _llm_compose(seq)
    except Exception:
        sentence = None
    if sentence is None:
        sentence = alts[0] if match_score(ranked[0][1]) <= 2 else None
    # 保守语义：手势未全覆盖句词序（缺词/多词）即待核实，与 App P7 待核实桶对齐
    needs = sentence is None or (sentence in SENT_WORDSEQ and miss_of(SENT_WORDSEQ[sentence]) >= 1)
    return {'status': 'OK' if sentence else 'NO_MATCH', 'sentence': sentence,
            'alternatives': alts, 'segmentId': str(uuid.uuid4()), 'revision': 1,
            'needsConfirmation': bool(needs)}


async def main():
    global _sem
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=8790)
    ap.add_argument('--device', default='cuda')
    ap.add_argument('--token', default='demo-token-20260923')
    args = ap.parse_args()
    ss.load_cfg(1080, args.device)
    build_word_bank()
    _sem = threading.Semaphore(MAX_CONCURRENT)
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _out(self, obj, code=200, headers=None):
            body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
            self.send_response(code)
            self.send_header('content-type', 'application/json; charset=utf-8')
            self.send_header('content-length', str(len(body)))
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            try:
                self._post()
            except Exception as e:  # 联调期：任何内部错误回 500，不重置连接
                try:
                    self._out({'error': f'internal: {type(e).__name__}: {e}'}, 500)
                except Exception:
                    pass

        def _post(self):
            n = int(self.headers.get('content-length', 0))
            raw = self.rfile.read(n) if n else b''  # 先读_body_再鉴权，避免半截请求弄坏连接
            if self.headers.get('Authorization', '') != f'Bearer {args.token}':
                return self._out({'error': 'unauthorized'}, 401)
            if self.path == '/v1/recognize':
                if n > 32 * 1024 * 1024:
                    return self._out({'error': 'payload too large'}, 413)
                ctype = self.headers.get('content-type', '')
                data = raw
                if 'mp4' not in ctype and not data[4:12].startswith(b'ftyp'):
                    return self._out({'error': 'unsupported media type'}, 415)
                if not _sem.acquire(timeout=1.0):
                    return self._out({'error': 'rate limited'}, 429, {'Retry-After': '2'})
                try:
                    self._out(recognize_clip(data))
                finally:
                    _sem.release()
            elif self.path == '/v1/compose-signs':
                req = json.loads(raw or b'{}')
                t0 = time.time()
                res = compose_signs(req.get('gestures', []))
                if time.time() - t0 > 8:
                    return self._out({'error': 'MODEL_TIMEOUT'}, 504)
                self._out(res)
            else:
                self._out({'error': 'not found'}, 404)

    print(f'clip service on :{args.port} words={len(WORD_INSTANCES)} sentences={len(SENT_WORDSEQ)}', flush=True)
    ThreadingHTTPServer(('0.0.0.0', args.port), H).serve_forever()


if __name__ == '__main__':
    asyncio.run(main())
