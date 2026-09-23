"""Build a session-separated baseline manifest from audited local videos."""
import argparse
import csv
import os
from pathlib import Path

from extract import ROOT


def load(report):
    with report.open(newline='') as stream:
        return list(csv.DictReader(stream))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--first', type=Path, default=ROOT/'outputs/audit/coverage.csv')
    parser.add_argument('--first-dir', type=Path, default=ROOT/'data/videos/session01')
    parser.add_argument('--first-session-id', default='s01')
    parser.add_argument('--second', type=Path, default=ROOT/'outputs/audit_session02_labeled/coverage.csv')
    parser.add_argument('--second-dir', type=Path, default=ROOT/'data/videos/session02')
    parser.add_argument('--second-session-id', default='s02')
    parser.add_argument('--third', type=Path)
    parser.add_argument('--third-dir', type=Path, default=ROOT/'data/videos/session03')
    parser.add_argument('--third-session-id', default='s03')
    parser.add_argument('--output', type=Path, default=ROOT/'data/manifest.csv')
    args = parser.parse_args()
    first, second = load(args.first), load(args.second)
    reports = [first, second] + ([load(args.third)] if args.third else [])
    label_sets = [{row['label'] for row in report if not row['error']
                   and float(row['any_hand_fraction'] or 0) > 0} for report in reports]
    labels = set.intersection(*label_sets)
    if len(labels) < 2:
        parser.error('Need at least two labels shared by both sessions')
    rows = []
    sources = [
        (args.first_dir, args.first, 'train', args.first_session_id),
        (args.second_dir, args.second, 'train' if args.third else 'test', args.second_session_id)]
    if args.third:
        sources.append((args.third_dir, args.third, 'test', args.third_session_id))
    for source, report, split, session in sources:
        for item in load(report):
            if item['error'] or item['label'] not in labels or float(item['any_hand_fraction']) == 0:
                continue
            video = source/item['video']
            feature = report.parent/'features'/f"{video.stem}.npz"
            if not video.is_file() or not feature.is_file():
                raise ValueError(f'Missing source or features for {video}')
            rows.append({'video': os.path.relpath(video,args.output.parent),
                         'feature_path': os.path.relpath(feature,args.output.parent),
                         'label': item['label'], 'signer': 'p01', 'session': session, 'split': split})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('w',newline='') as stream:
        writer = csv.DictWriter(stream,fieldnames=['video','feature_path','label','signer','session','split'])
        writer.writeheader()
        writer.writerows(rows)
    print(f'Manifest: {args.output}')
    print(f'Classes: {sorted(labels)}')
    print(f"Train: {sum(r['split']=='train' for r in rows)}; test: {sum(r['split']=='test' for r in rows)}")
    print(f'{args.first_session_id}+{args.second_session_id} train; {args.third_session_id} test.'
          if args.third else f'{args.first_session_id} train; {args.second_session_id} test.')
    print('No validation split; fixed training epochs.')


if __name__ == '__main__':
    main()
