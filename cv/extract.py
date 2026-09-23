"""Extract image-space landmarks without discarding wrist trajectories."""
import argparse
import json
from pathlib import Path
import cv2
import mediapipe as mp
import numpy as np

ROOT = Path(__file__).resolve().parent
EDGES = [(0,1),(1,2),(2,3),(3,4),(0,5),(5,6),(6,7),(7,8),(5,9),(9,10),(10,11),(11,12),(9,13),(13,14),(14,15),(15,16),(13,17),(0,17),(17,18),(18,19),(19,20)]

def extract(video, output, overlay=None, model=ROOT/'models/hand_landmarker.task'):
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise ValueError(f'Cannot open video: {video}')
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not np.isfinite(fps) or fps <= 0 or fps > 1000:
        cap.release()
        raise ValueError('Invalid video FPS')
    options = mp.tasks.vision.HandLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(model)),
        running_mode=mp.tasks.vision.RunningMode.VIDEO, num_hands=2)
    frames, masks, times = [], [], []
    writer = None
    try:
        with mp.tasks.vision.HandLandmarker.create_from_options(options) as detector:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                # Bound inference image size, preserving aspect ratio.
                h, w = frame.shape[:2]
                if max(h, w) > 1280:
                    frame = cv2.resize(frame, (round(w*1280/max(h,w)), round(h*1280/max(h,w))))
                h, w = frame.shape[:2]
                timestamp = round(len(frames)*1000/fps)
                result = detector.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB,
                    data=cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)), timestamp)
                points = np.zeros((2,21,3), dtype=np.float32)
                present = np.zeros(2, dtype=np.float32)
                scores = np.zeros(2)
                for hand, categories in zip(result.hand_landmarks, result.handedness):
                    slot = 0 if categories[0].category_name == 'Left' else 1
                    # Duplicate handedness: retain higher-score result, do not invent identity.
                    if present[slot] and categories[0].score <= scores[slot]:
                        continue
                    points[slot] = [[p.x,p.y,p.z] for p in hand]
                    present[slot], scores[slot] = 1, categories[0].score
                if overlay:
                    if writer is None:
                        Path(overlay).parent.mkdir(parents=True, exist_ok=True)
                        writer = cv2.VideoWriter(str(overlay), cv2.VideoWriter_fourcc(*'mp4v'), fps, (w,h))
                        if not writer.isOpened():
                            raise ValueError('Cannot create overlay video')
                    for slot in range(2):
                        if not present[slot]:
                            continue
                        xy = (points[slot,:,:2]*[w,h]).astype(int)
                        color = (0,220,0) if slot == 0 else (0,160,255)
                        for a,b in EDGES:
                            cv2.line(frame, tuple(xy[a]), tuple(xy[b]), color, 2)
                        for p in xy:
                            cv2.circle(frame, tuple(p), 3, color, -1)
                    writer.write(frame)
                frames.append(points)
                masks.append(present)
                times.append(timestamp)
    finally:
        cap.release()
        if writer:
            writer.release()
    if not frames:
        raise ValueError('No decoded frames')
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    mask = np.asarray(masks)
    np.savez_compressed(output, landmarks=np.asarray(frames), present=mask,
                        timestamps_ms=np.asarray(times), fps=fps)
    report = dict(frames=len(frames), fps=fps,
                  any_hand_fraction=float(mask.any(axis=1).mean()),
                  both_hands_fraction=float(mask.all(axis=1).mean()),
                  source=str(Path(video).resolve()))
    output.with_suffix('.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    return report

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('video', type=Path)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--overlay', type=Path)
    a = p.parse_args()
    if a.overlay and a.overlay.resolve() == a.video.resolve():
        p.error('Overlay must not overwrite input video')
    print(json.dumps(extract(a.video, a.output, a.overlay), indent=2))
