"""Export a trained PyTorch checkpoint for lightweight NumPy server inference."""

import argparse
from pathlib import Path

import numpy as np
import torch

from extract import ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('checkpoint', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'outputs/classifier_numpy.npz')
    args = parser.parse_args()
    saved = torch.load(args.checkpoint, map_location='cpu', weights_only=True)
    if saved['feature_version'] != 1:
        raise ValueError('Unsupported feature version')
    weights = saved['state_dict']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(args.output,
                        feature_version=np.array(saved['feature_version']),
                        labels=np.array(saved['labels']),
                        conv1_weight=weights['net.0.weight'].numpy(),
                        conv1_bias=weights['net.0.bias'].numpy(),
                        conv2_weight=weights['net.3.weight'].numpy(),
                        conv2_bias=weights['net.3.bias'].numpy(),
                        linear_weight=weights['net.7.weight'].numpy(),
                        linear_bias=weights['net.7.bias'].numpy())
    print(args.output)


if __name__ == '__main__':
    main()
