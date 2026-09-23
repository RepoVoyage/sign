"""连续流原型：模拟 GO 3S 连续拍摄 → 停顿分割 → 滑窗整句识别 → 词流事件。

离线模拟：把缓存 10fps 关键点序列按会话拼接，clip 间插 1.5s 静止段（重复末帧）。
在线算法：腕速状态机——静止(腕速<阈值持续>=0.4s)→起签 开新段；起签→静止 关闭段并识别。
评估：检出率、边界误差、句子准确率、误报数、处理耗时/模拟时长（realtime factor）。
用法：python continuous_stream.py
"""
import json
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'annotation_candidates_20260923'))
from align_candidates import align, load_features  # noqa: E402

V_THR = 0.015     # 腕速阈值（肩宽归一单位/帧）
PAUSE_FRAMES = 4  # 0.4s @10fps
MIN_SEG = 1.0

CACHE = {}


def feats(vid):
    if vid not in CACHE:
        CACHE[vid] = load_features(vid)
    return CACHE[vid]


def speed(prev_f, f):
    w = np.stack([prev_f, f])[:, 5:7]  # 特征列 5,6 = 双腕
    return float(np.linalg.norm(np.diff(w, axis=0), axis=-1).mean())


def recognize(fs, vs, seg_times, classes):
    """整句模板最近邻 + 词边界转移，返回 (pred, margin, tvid, stream)。"""
    best = {}
    for c, members in classes.items():
        scored = sorted((align(fs, vs, feats(m)[0], feats(m)[1])[1], m) for m in members)
        best[c] = scored[0]
    ranked = sorted(best.items(), key=lambda kv: kv[1][0])
    pred = ranked[0][0]
    tvid = ranked[0][1][1]
    margin = ranked[1][1][0] - ranked[0][1][0]
    tf, tv, tt_, _ = feats(tvid)
    path, _ = align(tf, tv, fs, vs)
    mapped = np.array([np.median(np.asarray(seg_times)[path[path[:, 0] == i, 1]])
                       for i in range(len(tt_))])
    stream = []
    for w in sorted([w for w in WORDS.get(tvid, [])], key=lambda w: w['start']):
        s = round(max(0.0, float(np.interp(w['start'], tt_, mapped))), 2)
        e = round(float(np.interp(w['end'], tt_, mapped)), 2)
        if e - s >= 0.08:
            stream.append({'word': w['label'], 'start': s, 'end': e})
    return pred, margin, tvid, stream


WORDS = {}


def main():
    global WORDS
    manifest = json.loads((HERE.parent / 'annotation_candidates_20260923' / 'manifest.json').read_text(encoding='utf-8'))
    sent_of = {m['id']: m['sentence'] for m in manifest}
    root = HERE.parents[3]
    ann = json.loads((root / '标注_粗标数据.json').read_text(encoding='utf-8'))
    vid_of = {m['file']: m['id'] for m in manifest}
    for r in ann['records']:
        WORDS.setdefault(vid_of[r['file']], []).append(
            {'label': r['label'], 'start': r['start_seconds'], 'end': r['end_seconds']})
    classes = {}
    for vid, s in sent_of.items():
        classes.setdefault(s, []).append(vid)

    sessions = [[1, 21, 46], [56, 71, 96], [11, 61, 86], [31, 51, 76]]
    tot_sim = tot_proc = n_det = n_miss = n_fp = correct = 0
    bmae = []
    for si, sess in enumerate(sessions):
        stream, truth = [], []
        t = 0.0
        for vid in sess:
            f, v, times, dur = feats(vid)
            truth.append({'vid': vid, 'true': sent_of[vid], 'start': t, 'end': t + dur})
            for i in range(len(times)):
                stream.append((t + times[i], f[i], v[i]))
            t += dur
            for _ in range(15):  # 1.5s 静止
                t += 0.1
                stream.append((t, f[-1], v[-1]))
        tot_sim += t
        t0 = time.time()
        # 在线状态机
        signing, seg_start, low_run, segs_out = False, 0.0, 0, []
        for k, (tt, f, v) in enumerate(stream):
            sp = speed(stream[k - 1][1], f) if k else 9.9
            low = sp < V_THR
            low_run = low_run + 1 if low else 0
            if not signing and not low:
                signing, seg_start = True, tt
            elif signing and low_run >= PAUSE_FRAMES:
                if tt - 0.4 - seg_start >= MIN_SEG:
                    segs_out.append([seg_start, tt - 0.4])
                signing, low_run = False, 0
        if signing:
            segs_out.append([seg_start, stream[-1][0]])
        matched = set()
        for si2, (a, b) in enumerate(segs_out):
            if b - a < 0.3:  # 已被合并吞掉的空段
                continue
            idx = [i for i, (tt, _, _) in enumerate(stream) if a <= tt <= b]
            fs = np.stack([stream[i][1] for i in idx])
            vs = np.stack([stream[i][2] for i in idx])
            seg_times = [stream[i][0] - a for i in idx]
            pred, margin, tvid, wstream = recognize(fs, vs, seg_times, classes)
            # 低 margin：疑似句内停顿过分割，尝试与下一段合并重认
            if margin < 0.05 and si2 + 1 < len(segs_out) and segs_out[si2 + 1][0] - b <= 1.0:
                a2, b2 = a, segs_out[si2 + 1][1]
                idx2 = [i for i, (tt, _, _) in enumerate(stream) if a2 <= tt <= b2]
                fs2 = np.stack([stream[i][1] for i in idx2])
                vs2 = np.stack([stream[i][2] for i in idx2])
                pred2, margin2, tvid2, wstream2 = recognize(
                    fs2, vs2, [stream[i][0] - a2 for i in idx2], classes)
                if margin2 > margin:
                    pred, margin, tvid, wstream = pred2, margin2, tvid2, wstream2
                    a, b = a2, b2
                    segs_out[si2 + 1] = [b2, b2]  # 吞并下一段，置空
                    idx = idx2
            # 终点回收到段内最后运动帧
            moves = [i for i in idx if i > 0 and speed(stream[i - 1][1], stream[i][1]) >= V_THR]
            if moves:
                b = min(b, stream[moves[-1]][0] + 0.1)
            hit = next((g for g in truth if g['start'] - 0.6 <= a <= g['end']
                        and g['start'] <= b <= g['end'] + 0.9), None)
            if hit is None:
                n_fp += 1
                print(f's{si} FP [{a:.1f},{b:.1f}] pred={pred}')
                continue
            matched.add(hit['vid'])
            n_det += 1
            correct += pred == hit['true']
            bmae.append((abs(a - hit['start']), abs(b - hit['end'])))
            print(f"s{si} [{a:.1f},{b:.1f}] true={hit['true']} pred={pred} margin={margin:.3f} "
                  f"stream={'/'.join(w['word'] for w in wstream)}")
        n_miss += len(truth) - len(matched)
        tot_proc += time.time() - t0
    bmae = np.array(bmae) if bmae else np.zeros((1, 2))
    print(f'detect {n_det}/{n_det + n_miss}  sent_acc {correct / max(n_det, 1):.3f}  fp {n_fp}  '
          f'boundary MAE start {bmae[:, 0].mean():.2f}s end {bmae[:, 1].mean():.2f}s  '
          f'realtime factor {tot_sim / max(tot_proc, 1e-6):.1f}x')


if __name__ == '__main__':
    main()
