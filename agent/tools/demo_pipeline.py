"""Send isolated-word MP4 clips through the deployed CV and Agent demo APIs."""

import argparse
import json
import os
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4


def post(url, content, content_type, token, timeout):
    request = Request(url, data=content, method='POST', headers={
        'Authorization': f'Bearer {token}', 'Content-Type': content_type})
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except HTTPError as exc:
        detail = exc.read(4096).decode('utf-8', errors='replace')
        raise RuntimeError(f'{url} returned HTTP {exc.code}: {detail}') from exc
    except URLError as exc:
        raise RuntimeError(f'Cannot connect to {url}: {exc.reason}') from exc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('videos', nargs='+', type=Path, help='One isolated sign word per MP4, in sentence order')
    parser.add_argument('--base-url', default='https://101.37.234.129')
    args = parser.parse_args()
    if not 1 <= len(args.videos) <= 12:
        parser.error('Provide 1–12 word clips')
    cv_token, agent_token = os.environ.get('CV_SERVICE_TOKEN'), os.environ.get('SERVICE_API_KEY')
    if not cv_token or not agent_token:
        parser.error('Set CV_SERVICE_TOKEN and SERVICE_API_KEY in your environment')
    gestures = []
    for video in args.videos:
        if video.suffix.lower() != '.mp4' or not video.is_file():
            parser.error(f'Not an MP4 file: {video}')
        if video.stat().st_size > 32 * 1024 * 1024:
            parser.error(f'Video exceeds 32 MiB: {video}')
        result = post(args.base_url + '/v1/recognize', video.read_bytes(),
                      'video/mp4', cv_token, timeout=60)
        print(json.dumps({'video': str(video), **result}, ensure_ascii=False), flush=True)
        if result.get('status') != 'OK' or not result.get('candidates'):
            raise RuntimeError(f'CV did not produce candidates for {video.name}; retry this word')
        gestures.append({'candidates': result['candidates']})
    body = {'sessionId': str(uuid4()), 'segmentId': str(uuid4()),
            'revision': 0, 'gestures': gestures}
    result = post(args.base_url + '/v1/compose-signs',
                  json.dumps(body, ensure_ascii=False).encode('utf-8'),
                  'application/json', agent_token, timeout=15)
    print(json.dumps({'compose': result}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1) from exc
