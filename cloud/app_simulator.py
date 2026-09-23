"""App 侧模拟器：按 sign 仓库 P6 契约 §3/§4 语义组装并测试全链路（无 Android SDK 时的替身）。

链路（对照 Docs/P6-App侧对接契约-基于联调手册v0）：
  FrameSampler(每3帧取1@30fps→10fps, JPEG q85 base64)
  → SignStreamClient(POST /v1/sign/frame, ts=pts/1e6, 末尾 flush, 会话 reset)
  → EventMapper(segmentId=seg-<seg_start>; tokenSpans=(seg_start+start)*1e6;
                confidence=sentence_confidence; boundary RELIABLE 直传)
  → P7 置信度流程(conf<0.7 → 待核实标志+震动桶; needs_repeat → 重打提示, 不震不冻)
  → SentenceManager(finalizeDelayMs=500, 模拟中记时不睡)
  → LanguageProcessor(sentence_candidate 作 rawChinese 润色, §4)
用法：python app_simulator.py <video.mp4> [service_url]
"""
import base64
import json
import sys
import urllib.request

import cv2

SERVICE = sys.argv[2] if len(sys.argv) > 2 else 'http://127.0.0.1:8788'
NEEDS_VERIFY_THR = 0.7  # P7 用户定稿初始值


def post(path, obj):
    req = urllib.request.Request(SERVICE + path, data=json.dumps(obj).encode('utf-8'),
                                 headers={'content-type': 'application/json'})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode('utf-8'))


def sample_frames(path):
    cap = cv2.VideoCapture(path)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    step = max(1, round(fps / 10))
    i, t, out = 0, 0.0, []
    while True:
        ok, fr = cap.read()
        if not ok:
            break
        if i % step == 0:
            _, buf = cv2.imencode('.jpg', fr, [cv2.IMWRITE_JPEG_QUALITY, 85])
            out.append((t, base64.b64encode(buf).decode()))
            t += step / fps
        i += 1
    cap.release()
    return out


def language_processor(raw_chinese):
    """§4：sentence_candidate 直接作 rawChinese 进既有润色链（保真+整理）。"""
    import os
    try:
        base = os.environ['ANTHROPIC_BASE_URL'].rstrip('/')
        key = os.environ['ANTHROPIC_AUTH_TOKEN']
        prompt = ('把这句手语识别结果整理为自然中文口语，保持原意不增删事实，只输出整理后句子：'
                  + raw_chinese)
        body = json.dumps({'model': 'qwen3.8-max', 'max_tokens': 120,
                           'messages': [{'role': 'user', 'content': prompt}]}).encode('utf-8')
        req = urllib.request.Request(base + '/v1/messages', data=body, headers={
            'content-type': 'application/json', 'x-api-key': key,
            'authorization': f'Bearer {key}', 'anthropic-version': '2023-06-01'})
        with urllib.request.urlopen(req, timeout=6) as resp:
            out = json.loads(resp.read().decode('utf-8'))
        return ''.join(b.get('text', '') for b in out.get('content', [])
                       if b.get('type') == 'text').strip() or raw_chinese
    except Exception:
        return raw_chinese


def event_mapper(ev):
    return {'sequenceEpoch': 1,
            'segmentId': 'seg-' + str(ev['seg_start']),
            'draftText': ev['sentence_candidate'],
            'tokenSpans': [{'text': w['word'],
                            'startPtsUs': int((ev['seg_start'] + w['start']) * 1e6),
                            'endPtsUs': int((ev['seg_start'] + w['end']) * 1e6),
                            'stable': True} for w in ev['stream']],
            'confidence': ev.get('sentence_confidence', 0.5),
            'boundary': {'cutoffPtsUs': int(ev['seg_end'] * 1e6),
                         'requiredFutureContextUs': 0, 'reliability': 'RELIABLE',
                         'source': 'MODEL'},
            'needs_repeat': ev.get('needs_repeat', False)}


def main():
    path = sys.argv[1]
    sid = 'sim-' + path.replace('\\', '/').split('/')[-1]
    post('/v1/sign/reset', {'session_id': sid})
    frames = sample_frames(path)
    events = []
    for t, b64 in frames:
        resp = post('/v1/sign/frame', {'session_id': sid, 'ts': round(t, 3), 'jpeg_b64': b64})
        events += [event_mapper(e) for e in resp.get('events', [])]
    resp = post('/v1/sign/frame', {'session_id': sid, 'ts': round(frames[-1][0] + 0.1, 3),
                                   'jpeg_b64': frames[-1][1], 'flush': True})
    events += [event_mapper(e) for e in resp.get('events', [])]
    print(f'== {path}  frames={len(frames)} events={len(events)}')
    for u in events:
        verify = u['confidence'] < NEEDS_VERIFY_THR
        final = language_processor(u['draftText'])
        print(json.dumps({'segmentId': u['segmentId'], 'draft': u['draftText'],
                          'confidence': u['confidence'], 'needs_verify(待核实+震动)': verify,
                          'needs_repeat(重打提示)': u['needs_repeat'],
                          'subtitle(润色后)': final,
                          'spans': [s['text'] for s in u['tokenSpans']]}, ensure_ascii=False))


if __name__ == '__main__':
    main()
