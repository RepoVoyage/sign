"""联调示例客户端：把一段 MP4 以 10fps 推给 stream_service，打印词流事件。

用法：python client_example.py <video.mp4> [http://127.0.0.1:8788]
安卓侧照此契约实现：JPEG(base64) + 帧时间戳(秒, 会话内单调) 逐帧 POST /v1/sign/frame；
事件数组非空即渲染/转发 LLM；needs_repeat=true 时提示重打。
"""
import base64
import json
import sys
import urllib.request

import cv2


def main():
    path = sys.argv[1]
    base = sys.argv[2] if len(sys.argv) > 2 else 'http://127.0.0.1:8788'
    sid = 'demo-' + path.replace('\\', '/').split('/')[-1]
    post(base + '/v1/sign/reset', {'session_id': sid})
    cap = cv2.VideoCapture(path)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    step = max(1, round(fps / 10))
    i = t = 0
    n_ev = 0
    while True:
        ok, fr = cap.read()
        if not ok:
            break
        if i % step == 0:
            _, buf = cv2.imencode('.jpg', fr, [cv2.IMWRITE_JPEG_QUALITY, 85])
            resp = post(base + '/v1/sign/frame',
                        {'session_id': sid, 'ts': round(t, 3), 'jpeg_b64': base64.b64encode(buf).decode()})
            for ev in resp.get('events', []):
                n_ev += 1
                print(json.dumps(ev, ensure_ascii=False))
            t += step / fps
        i += 1
    cap.release()
    resp = post(base + '/v1/sign/frame', {'session_id': sid, 'ts': round(t, 3),
                                          'jpeg_b64': base64.b64encode(buf).decode(), 'flush': True})
    for ev in resp.get('events', []):
        n_ev += 1
        print(json.dumps(ev, ensure_ascii=False))
    print(f'-- {n_ev} events from {path}')


def post(url, obj):
    req = urllib.request.Request(url, data=json.dumps(obj).encode('utf-8'),
                                 headers={'content-type': 'application/json'})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode('utf-8'))


if __name__ == '__main__':
    main()
