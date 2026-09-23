"""CPU-only inference for the exported TCN; no PyTorch needed on the server."""

import numpy as np


def features_array(points, present, length=64):
    if not present.any():
        raise ValueError('No detected hands')
    local = points - points[:, :, :1, :]
    scale = np.linalg.norm(local[:, :, 9, :2], axis=-1)
    local = local / np.maximum(scale[..., None, None], 0.02)
    local *= present[..., None, None]
    x = np.concatenate([local.reshape(len(points), -1),
                        points[:, :, 0, :2].reshape(len(points), -1), present], axis=1)
    index = np.round(np.linspace(0, len(x) - 1, length)).astype(int)
    return x[index].T.astype(np.float32)


def conv1d(x, weight, bias):
    padded = np.pad(x, ((0, 0), (2, 2)))
    windows = np.lib.stride_tricks.sliding_window_view(padded, 5, axis=1)
    return np.einsum('ock,ctk->ot', weight, windows, optimize=True) + bias[:, None]


class NumpyClassifier:
    def __init__(self, path):
        with np.load(path) as data:
            if int(data['feature_version']) != 1:
                raise ValueError('Unsupported feature version')
            self.labels = data['labels'].tolist()
            self.weights = {name: data[name].copy() for name in
                            ('conv1_weight', 'conv1_bias', 'conv2_weight', 'conv2_bias',
                             'linear_weight', 'linear_bias')}

    def predict(self, points, present):
        x = features_array(points, present)
        x = np.maximum(conv1d(x, self.weights['conv1_weight'], self.weights['conv1_bias']), 0)
        x = np.maximum(conv1d(x, self.weights['conv2_weight'], self.weights['conv2_bias']), 0)
        logits = self.weights['linear_weight'] @ x.mean(axis=1) + self.weights['linear_bias']
        exp = np.exp(logits - logits.max())
        scores = exp / exp.sum()
        return [{'label': self.labels[index], 'score': float(scores[index])}
                for index in scores.argsort()[::-1][:3]]

    def predict_file(self, path):
        with np.load(path) as data:
            return self.predict(data['landmarks'], data['present'])
