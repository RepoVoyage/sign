"""Build a reproducible, exploratory one-clip-per-word session04 holdout."""

import argparse
import csv
import json
import os
import random
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SOURCES = (
    ('s01', ROOT / 'data/videos/session01', ROOT / 'outputs/audit/coverage.csv'),
    ('s02', ROOT / 'data/videos/session02', ROOT / 'outputs/audit_session02_labeled/coverage.csv'),
    ('s03', ROOT / 'data/videos/session03', ROOT / 'outputs/audit_session03/coverage.csv'),
    ('s04', ROOT / 'data/videos/session04', ROOT / 'outputs/audit_session04/coverage.csv'),
)
FIELDS = ('video', 'feature_path', 'label', 'signer', 'session', 'split')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--output', type=Path, default=ROOT / 'data/manifest_s04_holdout_seed42.csv')
    args = parser.parse_args()

    clips = []
    for session, directory, report in SOURCES:
        with report.open(newline='') as stream:
            for item in csv.DictReader(stream):
                if item['error'] or float(item['any_hand_fraction'] or 0) <= 0:
                    continue
                video = directory / item['video']
                feature = report.parent / 'features' / f'{video.stem}.npz'
                if not video.is_file() or not feature.is_file():
                    raise ValueError(f'Missing video or features: {video} / {feature}')
                clips.append((session, item['label'], video, feature))

    by_label = defaultdict(list)
    for session, label, video, _ in clips:
        if session == 's04':
            by_label[label].append(video)
    rng = random.Random(args.seed)
    selected = {rng.choice(sorted(videos)) for _, videos in sorted(by_label.items())}
    train_labels = {label for session, label, video, _ in clips if video not in selected}
    if train_labels != set(by_label):
        raise ValueError('Every holdout label must also appear in training')

    rows = []
    for session, label, video, feature in clips:
        rows.append({
            'video': os.path.relpath(video, args.output.parent),
            'feature_path': os.path.relpath(feature, args.output.parent),
            'label': label,
            'signer': 'p01',
            'session': session,
            'split': 'test' if video in selected else 'train',
        })
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    selection_path = args.output.with_suffix('.selection.json')
    selection_path.write_text(json.dumps({
        'seed': args.seed,
        'method': 'one random session04 clip per label; all other clips train',
        'warning': 'Same signer and recording session cross the split; exploratory only.',
        'holdout': {label: str(next(video for video in selected if video in videos).name)
                    for label, videos in sorted(by_label.items())},
    }, ensure_ascii=False, indent=2) + '\n')
    print(f'Manifest: {args.output}')
    print(f'Holdout selection: {selection_path}')
    print(f'Classes: {len(by_label)}; train: {len(rows) - len(selected)}; test: {len(selected)}')


if __name__ == '__main__':
    main()
