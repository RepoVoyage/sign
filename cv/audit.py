"""Run landmark coverage checks on local, labeled video clips."""
import argparse
import csv
import re
from pathlib import Path

from extract import ROOT, extract


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('videos', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'outputs' / 'audit')
    args = parser.parse_args()
    files = sorted(args.videos.glob('*.MP4'))
    if not files:
        parser.error('No MP4 videos found')
    args.output.mkdir(parents=True, exist_ok=True)
    report_file = args.output / 'coverage.csv'
    fields = ['video', 'label', 'frames', 'any_hand_fraction', 'both_hands_fraction', 'error']
    with report_file.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for index, source in enumerate(files, 1):
            label = re.sub(r'\d+$', '', source.stem)
            row = {'video': source.name, 'label': label, 'frames': '',
                   'any_hand_fraction': '', 'both_hands_fraction': '', 'error': ''}
            try:
                result = extract(source, args.output / 'features' / f'{source.stem}.npz')
                for key in ('frames', 'any_hand_fraction', 'both_hands_fraction'):
                    row[key] = result[key]
            except Exception as exc:
                row['error'] = str(exc)
            writer.writerow(row)
            stream.flush()
            print(f"{index}/{len(files)} {source.name}: any={row['any_hand_fraction']} "
                  f"both={row['both_hands_fraction']} {row['error']}", flush=True)
    print(f'Report: {report_file}')


if __name__ == '__main__':
    main()
