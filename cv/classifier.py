"""Small temporal baseline. No pretrained sign-language weights are bundled."""
import argparse
import csv
import json
from pathlib import Path
import numpy as np
import torch
from torch import nn
from extract import extract, ROOT


def features_array(p, m, length=64):
    if not m.any():
        raise ValueError('No detected hands')
    # Local hand shape plus original wrist position retain both shape and trajectory.
    local = p - p[:, :, :1, :]
    scale = np.linalg.norm(local[:, :, 9, :2], axis=-1)
    local = local / np.maximum(scale[..., None, None], 0.02)
    local *= m[..., None, None]
    x = np.concatenate([local.reshape(len(p), -1), p[:,:,0,:2].reshape(len(p),-1), m], axis=1)
    # Nearest temporal sampling avoids interpolating missing hands into fake positions.
    index = np.round(np.linspace(0, len(x)-1, length)).astype(int)
    return torch.tensor(x[index].T, dtype=torch.float32)


def features(path, length=64):
    with np.load(path) as data:
        p, m = data['landmarks'], data['present']
    return features_array(p, m, length)

class TCN(nn.Module):
    def __init__(self, classes):
        super().__init__()
        self.net = nn.Sequential(nn.Conv1d(132,64,5,padding=2), nn.ReLU(),
            nn.Dropout(0.2), nn.Conv1d(64,64,5,padding=2), nn.ReLU(),
            nn.AdaptiveAvgPool1d(1), nn.Flatten(), nn.Linear(64,classes))
    def forward(self, x):
        return self.net(x)


def train(manifest, output, epochs=80, allow_within_session_test=False):
    torch.manual_seed(42)
    rows = list(csv.DictReader(manifest.open()))
    if not rows or not {'video','label','signer','session','split'} <= rows[0].keys():
        raise ValueError('Manifest needs video,label,signer,session,split')
    groups, seen = {}, set()
    for r in rows:
        if r['split'] not in {'train','val','test'}:
            raise ValueError('split must be train/val/test')
        group = (r['signer'],r['session'])
        if group in groups and groups[group] != r['split'] and not allow_within_session_test:
            raise ValueError('Same signer/session must not cross dataset splits')
        groups[group] = r['split']
        path = (manifest.parent / r['video']).resolve()
        if path in seen:
            raise ValueError(f'Duplicate source video: {path}')
        seen.add(path)
        if not path.is_file():
            raise ValueError(f'Missing video: {path}')
    labels = sorted({r['label'] for r in rows if r['split']=='train'})
    if len(labels) < 2:
        raise ValueError('Need at least two training classes')
    if any(r['label'] not in labels for r in rows):
        raise ValueError('Every evaluation class must occur in training')
    if {r['label'] for r in rows if r['split']=='test'} != set(labels):
        raise ValueError('test must contain all classes')
    has_val = any(r['split']=='val' for r in rows)
    if has_val and {r['label'] for r in rows if r['split']=='val'} != set(labels):
        raise ValueError('val must contain all classes when present')
    output.mkdir(parents=True, exist_ok=True)
    samples = {s: [] for s in ('train','val','test')}
    sample_rows = {s: [] for s in ('train','val','test')}
    for i,r in enumerate(rows):
        path = manifest.parent / r['video']
        if r.get('feature_path'):
            target = manifest.parent / r['feature_path']
            if not target.is_file():
                raise ValueError(f'Missing features: {target}')
        else:
            target = output / 'features' / f'{i:05d}.npz'
            extract(path,target)
        samples[r['split']].append((features(target),labels.index(r['label'])))
        sample_rows[r['split']].append(r)
    device = 'mps' if torch.backends.mps.is_available() else 'cpu'
    model = TCN(len(labels)).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=0.001)
    criterion = nn.CrossEntropyLoss()
    def tensors(split):
        return (torch.stack([x for x,y in samples[split]]).to(device),
                torch.tensor([y for x,y in samples[split]],device=device))
    train_x, train_y = tensors('train')
    if has_val:
        val_x, val_y = tensors('val')
    best = float('inf')
    for epoch in range(epochs):
        model.train()
        for indices in torch.randperm(len(train_x), device=device).split(16):
            optimizer.zero_grad()
            loss = criterion(model(train_x[indices]),train_y[indices])
            loss.backward()
            optimizer.step()
        if has_val:
            model.eval()
            with torch.no_grad():
                val_loss = criterion(model(val_x),val_y).item()
        if not has_val or val_loss < best:
            if has_val:
                best = val_loss
            torch.save({'state_dict': {k:v.detach().cpu() for k,v in model.state_dict().items()},
                        'labels':labels,'feature_version':1}, output/'classifier.pt')
        if epoch % 10 == 0:
            print(f'epoch={epoch} ' + (f'val_loss={val_loss:.4f}' if has_val else 'fixed_epochs'), flush=True)
    saved = torch.load(output/'classifier.pt', weights_only=True)
    model.load_state_dict(saved['state_dict'])
    test_x, test_y = tensors('test')
    with torch.no_grad():
        predictions = model(test_x).argmax(1).cpu().numpy()
    truth = test_y.cpu().numpy()
    confusion = np.zeros((len(labels),len(labels)),dtype=int)
    np.add.at(confusion,(truth,predictions),1)
    report = dict(device=device, labels=labels, train_count=len(train_y),
                  validation_count=len(samples['val']), selection='best_validation_loss' if has_val else 'final_fixed_epoch',
                  epochs=epochs, test_accuracy=float((truth==predictions).mean()),
                  test_count=len(truth), confusion_true_rows_predicted_columns=confusion.tolist(),
                  within_session_split=allow_within_session_test,
                  test_predictions=[{'video':r['video'], 'actual':labels[int(y)],
                                     'predicted':labels[int(p)], 'correct':bool(y == p)}
                                    for r,y,p in zip(sample_rows['test'], truth, predictions)])
    (output/'evaluation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='command',required=True)
    t = commands.add_parser('train')
    t.add_argument('manifest',type=Path)
    t.add_argument('--output',type=Path,default=ROOT/'outputs/training')
    t.add_argument('--epochs',type=int,default=80)
    t.add_argument('--allow-within-session-test',action='store_true',
                   help='Exploratory clip holdout; results are not session-independent')
    p = commands.add_parser('predict')
    p.add_argument('features',type=Path)
    p.add_argument('--checkpoint',type=Path,required=True)
    args = parser.parse_args()
    if args.command == 'train':
        if args.epochs < 1:
            parser.error('epochs must be positive')
        train(args.manifest,args.output,args.epochs,args.allow_within_session_test)
    else:
        saved = torch.load(args.checkpoint,map_location='cpu',weights_only=True)
        if saved['feature_version'] != 1:
            raise ValueError('Unsupported feature version')
        model = TCN(len(saved['labels']))
        model.load_state_dict(saved['state_dict'])
        model.eval()
        with torch.no_grad():
            scores = model(features(args.features)[None]).softmax(-1)[0]
        print(json.dumps({'candidates':[{'label':saved['labels'][i], 'score':float(scores[i])}
            for i in scores.argsort(descending=True)[:3].tolist()],
            'needs_confirmation':True},ensure_ascii=False,indent=2))
