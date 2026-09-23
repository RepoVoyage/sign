"""Try the isolated-word classifier with a webcam or a recorded video."""

import argparse
import json
import time
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
import torch

from classifier import TCN, features_array
from extract import EDGES, ROOT


def load_classifier(checkpoint):
    saved = torch.load(checkpoint, map_location='cpu', weights_only=True)
    if saved['feature_version'] != 1:
        raise ValueError('Unsupported feature version')
    model = TCN(len(saved['labels']))
    model.load_state_dict(saved['state_dict'])
    model.eval()
    return model, saved['labels']


def hand_points(result):
    points = np.zeros((2, 21, 3), dtype=np.float32)
    present = np.zeros(2, dtype=np.float32)
    scores = np.zeros(2)
    for hand, categories in zip(result.hand_landmarks, result.handedness):
        slot = 0 if categories[0].category_name == 'Left' else 1
        if present[slot] and categories[0].score <= scores[slot]:
            continue
        points[slot] = [[p.x, p.y, p.z] for p in hand]
        present[slot], scores[slot] = 1, categories[0].score
    return points, present


def draw_hands(frame, points, present):
    h, w = frame.shape[:2]
    for slot in range(2):
        if not present[slot]:
            continue
        xy = (points[slot, :, :2] * [w, h]).astype(int)
        color = (0, 220, 0) if slot == 0 else (0, 160, 255)
        for a, b in EDGES:
            cv2.line(frame, tuple(xy[a]), tuple(xy[b]), color, 2)
        for point in xy:
            cv2.circle(frame, tuple(point), 3, color, -1)


def predict_clip(model, labels, frames, masks, min_hand_fraction):
    coverage = float(np.asarray(masks).any(axis=1).mean()) if masks else 0.0
    if len(frames) < 12:
        return {'status': 'too_short', 'frames': len(frames), 'any_hand_fraction': coverage}
    if coverage < min_hand_fraction:
        return {'status': 'insufficient_hand_detection', 'frames': len(frames),
                'any_hand_fraction': coverage}
    x = features_array(np.stack(frames), np.stack(masks))
    with torch.no_grad():
        scores = model(x[None]).softmax(-1)[0]
    return {'status': 'ok', 'frames': len(frames), 'any_hand_fraction': coverage,
            'candidates': [{'label': labels[i], 'score': float(scores[i])}
                           for i in scores.argsort(descending=True)[:3].tolist()],
            'needs_confirmation': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkpoint', type=Path,
                        default=ROOT / 'outputs/training_s01_s02_s03_s04_holdout_seed42/classifier.pt')
    parser.add_argument('--model', type=Path, default=ROOT / 'models/hand_landmarker.task')
    parser.add_argument('--camera', type=int, default=0)
    parser.add_argument('--video', type=Path, help='Headless check using one prerecorded word clip')
    parser.add_argument('--min-hand-fraction', type=float, default=0.1)
    parser.add_argument('--max-seconds', type=float, default=6.0)
    args = parser.parse_args()
    if not 0 <= args.min_hand_fraction <= 1 or args.max_seconds <= 0:
        parser.error('Invalid hand fraction or clip duration')
    model, labels = load_classifier(args.checkpoint)
    cap = cv2.VideoCapture(str(args.video) if args.video else args.camera)
    if not cap.isOpened():
        raise ValueError(f'Cannot open video source: {args.video or args.camera}. Check camera permission/index.')
    fps = cap.get(cv2.CAP_PROP_FPS) if args.video else 0
    if args.video and (not np.isfinite(fps) or fps <= 0):
        cap.release()
        raise ValueError('Invalid video FPS')
    options = mp.tasks.vision.HandLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(args.model)),
        running_mode=mp.tasks.vision.RunningMode.VIDEO, num_hands=2)
    frames, masks = [], []
    recording = bool(args.video)
    start = time.monotonic()
    last_timestamp = -1
    processed = 0
    if not args.video:
        print('Space: start/stop one word. Q or Esc: quit. Results appear here in the terminal.', flush=True)
    try:
        with mp.tasks.vision.HandLandmarker.create_from_options(options) as detector:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                h, w = frame.shape[:2]
                if max(h, w) > 1280:
                    frame = cv2.resize(frame, (round(w * 1280 / max(h, w)),
                                               round(h * 1280 / max(h, w))))
                timestamp = round(processed * 1000 / fps) if args.video else round((time.monotonic() - start) * 1000)
                timestamp = max(timestamp, last_timestamp + 1)
                last_timestamp = timestamp
                processed += 1
                result = detector.detect_for_video(mp.Image(
                    image_format=mp.ImageFormat.SRGB,
                    data=cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)), timestamp)
                points, present = hand_points(result)
                if recording:
                    frames.append(points)
                    masks.append(present)
                if args.video:
                    continue
                draw_hands(frame, points, present)
                if recording and (time.monotonic() - clip_start) >= args.max_seconds:
                    print(json.dumps(predict_clip(model, labels, frames, masks, args.min_hand_fraction),
                                     ensure_ascii=False, indent=2), flush=True)
                    frames, masks, recording = [], [], False
                status = f'RECORDING {len(frames)} frames' if recording else 'READY'
                cv2.putText(frame, f'{status}  |  Space: start/stop  Q: quit',
                            (15, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.65, (0, 255, 255), 2)
                cv2.imshow('Sign recognition demo', frame)
                key = cv2.waitKey(1) & 0xFF
                if key in (ord('q'), 27):
                    break
                if key == 32:
                    if recording:
                        print(json.dumps(predict_clip(model, labels, frames, masks, args.min_hand_fraction),
                                         ensure_ascii=False, indent=2), flush=True)
                        frames, masks, recording = [], [], False
                    else:
                        frames, masks, recording = [], [], True
                        clip_start = time.monotonic()
                        print('Recording one word...', flush=True)
        if args.video:
            print(json.dumps(predict_clip(model, labels, frames, masks, args.min_hand_fraction),
                             ensure_ascii=False, indent=2))
        elif processed == 0:
            print('No frames received from camera. Check camera permission and --camera index.', flush=True)
    finally:
        cap.release()
        if not args.video:
            cv2.destroyAllWindows()


if __name__ == '__main__':
    main()
