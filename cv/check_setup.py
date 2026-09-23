"""Runtime checks, not a sign-recognition accuracy test."""
import json
from pathlib import Path
import cv2
import numpy as np
import torch
from extract import ROOT, extract
from classifier import TCN, features

out = ROOT/'outputs/setup_check'
out.mkdir(parents=True,exist_ok=True)
for name, frame in [('blank',np.zeros((240,320,3),dtype=np.uint8)),
                    ('hands',cv2.imread(str(ROOT/'outputs/official_hands.jpg')))]:
    if frame is None:
        continue
    h,w = frame.shape[:2]
    writer = cv2.VideoWriter(str(out/f'{name}.mp4'),cv2.VideoWriter_fourcc(*'mp4v'),10,(w,h))
    assert writer.isOpened()
    for _ in range(10):
        writer.write(frame)
    writer.release()
    report = extract(out/f'{name}.mp4',out/f'{name}.npz',out/f'{name}_overlay.mp4')
    print(name,report)
    if name=='blank':
        assert report['any_hand_fraction']==0
        try:
            features(out/f'{name}.npz')
        except ValueError:
            pass
        else:
            raise AssertionError('Empty hands must be rejected')
    else:
        assert report['both_hands_fraction'] > 0
        assert features(out/f'{name}.npz').shape == (132,64)
device='mps' if torch.backends.mps.is_available() else 'cpu'
model=TCN(5).to(device)
optimizer=torch.optim.AdamW(model.parameters())
x=torch.randn(4,132,64,device=device)
loss=torch.nn.functional.cross_entropy(model(x),torch.tensor([0,1,2,3],device=device))
loss.backward()
optimizer.step()
model.eval()
expected=model(x).detach().cpu()
checkpoint=out/'smoke_weights.pt'
torch.save(model.cpu().state_dict(),checkpoint)
restored=TCN(5).eval()
restored.load_state_dict(torch.load(checkpoint,weights_only=True))
assert torch.allclose(expected,restored(x.cpu()),atol=1e-4)
result={'device':device,'training_step':'passed','checkpoint_roundtrip':'passed',
        'note':'Synthetic runtime test, not real sign-language accuracy'}
(out/'result.json').write_text(json.dumps(result,indent=2))
print(result)
