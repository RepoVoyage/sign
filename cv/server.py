"""Small local HTTP service for one isolated sign-word video per request."""

import argparse
import json
import os
import secrets
import tempfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from extract import ROOT, extract
from inference_numpy import NumpyClassifier


MAX_BYTES = 32 * 1024 * 1024
MIN_HAND_FRACTION = 0.1


def recognize_video(video, model):
    feature = video.with_suffix('.npz')
    report = extract(video, feature)
    result = {'frames': report['frames'], 'any_hand_fraction': report['any_hand_fraction'],
              'needsConfirmation': True}
    if report['frames'] < 12:
        return {**result, 'status': 'TOO_SHORT', 'candidates': []}
    if report['any_hand_fraction'] < MIN_HAND_FRACTION:
        return {**result, 'status': 'INSUFFICIENT_HAND_DETECTION', 'candidates': []}
    return {**result, 'status': 'OK', 'candidates': model.predict_file(feature)}


class RecognizeServer(HTTPServer):
    def __init__(self, address, model, token):
        super().__init__(address, RecognizeHandler)
        self.model, self.token = model, token


class RecognizeHandler(BaseHTTPRequestHandler):
    def reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/health':
            self.reply(200, {'status': 'ok', 'model_loaded': True})
        else:
            self.reply(404, {'error': 'NOT_FOUND'})

    def do_POST(self):
        if self.path != '/v1/recognize':
            return self.reply(404, {'error': 'NOT_FOUND'})
        token = self.server.token
        credentials = self.headers.get('Authorization', '')
        if token and not secrets.compare_digest(credentials, f'Bearer {token}'):
            return self.reply(401, {'error': 'UNAUTHORIZED'})
        if self.headers.get('Content-Type', '').split(';')[0].strip() != 'video/mp4':
            return self.reply(415, {'error': 'EXPECTED_VIDEO_MP4'})
        try:
            length = int(self.headers.get('Content-Length', ''))
        except ValueError:
            return self.reply(411, {'error': 'CONTENT_LENGTH_REQUIRED'})
        if not 0 < length <= MAX_BYTES:
            return self.reply(413, {'error': 'VIDEO_TOO_LARGE'})
        self.connection.settimeout(20)
        try:
            data = self.rfile.read(length)
            if len(data) != length:
                return self.reply(400, {'error': 'INCOMPLETE_UPLOAD'})
            with tempfile.TemporaryDirectory(prefix='sign-cv-') as directory:
                video = Path(directory) / 'clip.mp4'
                video.write_bytes(data)
                result = recognize_video(video, self.server.model)
            self.reply(200, result)
        except (TimeoutError, OSError, ValueError) as exc:
            # Keep codec and filesystem details out of client responses.
            print(f'CV request failed: {type(exc).__name__}: {exc}', flush=True)
            self.reply(422, {'error': 'INVALID_VIDEO'})
        except RuntimeError as exc:
            print(f'CV inference failed: {type(exc).__name__}: {exc}', flush=True)
            self.reply(503, {'error': 'CV_INFERENCE_FAILED'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--model', type=Path, default=ROOT / 'outputs/classifier_numpy.npz')
    args = parser.parse_args()
    token = os.environ.get('CV_SERVICE_TOKEN', '')
    if args.host not in ('127.0.0.1', 'localhost', '::1') and not token:
        parser.error('Set CV_SERVICE_TOKEN before listening beyond localhost')
    model = NumpyClassifier(args.model)
    server = RecognizeServer((args.host, args.port), model, token)
    print(f'CV service listening on http://{args.host}:{args.port}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
