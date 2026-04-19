#!/usr/bin/env python3
"""
Train a BitNet-style TERNARY MLP for MNIST and save a generic .npz
that can be loaded by mnist_fpga.py.

Architecture (configurable at bottom):  784 -> 256 -> 64 -> 10
All weight matrices are quantized to {-1, 0, +1} (ternary).
Biases are int32.  Activations between layers are int8 (clamp after ReLU).

The saved .npz layout understood by mnist_fpga.py:
  num_layers   : int scalar
  W{i}         : int8  (M, K)   — ternary weights, values in {-1,0,1}
  b{i}         : int32 (M,)     — bias in accumulator domain
  shift{i}     : int scalar     — right-shift applied after bias add
  activation{i}: int scalar     — 0=linear 1=ReLU 3=ReLU6
  clamp{i}     : int scalar     — 1=clamp output to int8
  input_scale  : float          — multiply raw float input by this → int8

Usage:
  python train_ternary.py                        # train with defaults
  python train_ternary.py --arch 784,128,10      # custom architecture
  python train_ternary.py --epochs 20 --lr 5e-4
"""

import argparse
import sys
import numpy as np

# ---------------------------------------------------------------------------
# Straight-Through Estimator helpers (pure NumPy / used in eval only)
# The actual training uses PyTorch; these are for the quantized SW eval pass.
# ---------------------------------------------------------------------------

def ternary_quantize_np(W: np.ndarray) -> np.ndarray:
    """Quantize float weight matrix to {-1, 0, +1} using mean-absolute threshold."""
    threshold = 0.5 * np.mean(np.abs(W))
    Q = np.zeros_like(W, dtype=np.int8)
    Q[W >  threshold] =  1
    Q[W < -threshold] = -1
    return Q


# ---------------------------------------------------------------------------
# Training (PyTorch)
# ---------------------------------------------------------------------------

def train(arch: list[int], epochs: int, lr: float, batch_size: int,
          output_path: str, relu6_thresh: int = 6):

    try:
        import torch
        import torch.nn as nn
        import torch.nn.functional as F
        import torch.optim as optim
        from torchvision import datasets, transforms
    except ImportError:
        print("PyTorch + torchvision required:  pip install torch torchvision")
        sys.exit(1)

    # -----------------------------------------------------------------------
    # Ternary straight-through quantizer
    # -----------------------------------------------------------------------
    class TernaryQuantize(torch.autograd.Function):
        @staticmethod
        def forward(ctx, W):
            threshold = 0.5 * W.abs().mean()
            Q = torch.zeros_like(W)
            Q[W >  threshold] =  1.0
            Q[W < -threshold] = -1.0
            ctx.save_for_backward(W, threshold.unsqueeze(0))
            return Q

        @staticmethod
        def backward(ctx, grad_output):
            W, thr = ctx.saved_tensors
            # STE: pass gradient through where |W| <= 1
            mask = (W.abs() <= 1.0).float()
            return grad_output * mask

    ternary = TernaryQuantize.apply

    # -----------------------------------------------------------------------
    # Model: arbitrary depth MLP with ternary weights
    # -----------------------------------------------------------------------
    class TernaryMLP(nn.Module):
        def __init__(self, arch):
            super().__init__()
            self.layers = nn.ModuleList()
            for i in range(len(arch) - 1):
                self.layers.append(nn.Linear(arch[i], arch[i+1]))
            self._arch = arch

        def forward(self, x, quantize=True):
            x = x.view(x.size(0), -1)
            for i, layer in enumerate(self.layers):
                is_last = (i == len(self.layers) - 1)
                W = ternary(layer.weight) if quantize else layer.weight
                x = F.linear(x, W, layer.bias)
                if not is_last:
                    x = F.relu(x)
                    # Simulate int8 clamp during training so biases stay realistic
                    x = x.clamp(-128, 127)
            return x

    # -----------------------------------------------------------------------
    # Data
    # -----------------------------------------------------------------------
    transform = transforms.Compose([
        transforms.ToTensor(),
        transforms.Normalize((0.1307,), (0.3081,))
    ])

    print("Downloading MNIST …")
    train_ds = datasets.MNIST('./mnist_data', train=True,  download=True, transform=transform)
    test_ds  = datasets.MNIST('./mnist_data', train=False, download=True, transform=transform)
    train_ld = torch.utils.data.DataLoader(train_ds, batch_size=batch_size, shuffle=True,  num_workers=2)
    test_ld  = torch.utils.data.DataLoader(test_ds,  batch_size=1000,       shuffle=False, num_workers=2)

    device = "cpu"
    print(f"Using device: {device}")

    model     = TernaryMLP(arch).to(device)
    optimizer = optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    criterion = nn.CrossEntropyLoss()

    # -----------------------------------------------------------------------
    # Training loop
    # -----------------------------------------------------------------------
    print(f"\nArchitecture: {' -> '.join(map(str, arch))}")
    print(f"Epochs: {epochs}   LR: {lr}   Batch: {batch_size}\n")
    print("-" * 60)

    best_acc = 0.0
    for epoch in range(1, epochs + 1):
        model.train()
        train_loss = train_correct = train_total = 0

        for data, target in train_ld:
            data, target = data.to(device), target.to(device)
            optimizer.zero_grad()
            out  = model(data, quantize=True)
            loss = criterion(out, target)
            loss.backward()
            # Gradient clipping helps with STE training
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()

            # Keep latent weights bounded so STE remains meaningful
            with torch.no_grad():
                for p in model.parameters():
                    p.clamp_(-1.5, 1.5)

            train_loss    += loss.item()
            train_correct += out.argmax(1).eq(target).sum().item()
            train_total   += len(target)

        scheduler.step()

        # Test accuracy
        model.eval()
        test_correct = test_total = 0
        with torch.no_grad():
            for data, target in test_ld:
                data, target = data.to(device), target.to(device)
                out = model(data, quantize=True)
                test_correct += out.argmax(1).eq(target).sum().item()
                test_total   += len(target)

        tr_acc = 100 * train_correct / train_total
        te_acc = 100 * test_correct  / test_total
        best_acc = max(best_acc, te_acc)
        print(f"Epoch {epoch:>2}/{epochs}  "
              f"train {tr_acc:.1f}%  test {te_acc:.1f}%  "
              f"loss {train_loss/len(train_ld):.4f}  "
              f"lr {scheduler.get_last_lr()[0]:.2e}")

    print(f"\nBest test accuracy: {best_acc:.2f}%")

    # -----------------------------------------------------------------------
    # Extract quantized parameters (CPU numpy)
    # -----------------------------------------------------------------------
    model.eval()
    model.cpu()

    float_weights = []
    float_biases  = []
    with torch.no_grad():
        for layer in model.layers:
            W_q = ternary(layer.weight).numpy().astype(np.int8)   # {-1,0,1}
            float_weights.append(W_q)
            float_biases.append(layer.bias.detach().numpy())

    # -----------------------------------------------------------------------
    # Quantization strategy (post-training quantization with calibration)
    # -----------------------------------------------------------------------
    # We assign a per-layer output scale S_i such that:
    #   int8_output ≈ clamp(float_output * S_i, -128, 127)
    #
    # S_i is chosen by running the *float* ternary model over the full test
    # set and mapping the 99.9th-percentile absolute activation to 127.
    #
    # FPGA pipeline per layer i:
    #   acc_int  = W_int @ act_int              (ternary W, int8 act)
    #   combined = acc_int + b_int              (b_int in accumulator units)
    #   out_int  = combined >> shift_i
    #   out_int  = clamp(relu(out_int), -128, 127)  [hidden layers only]
    #
    # Scaling invariant (S_{-1} ≡ input_scale):
    #   acc_int  ≈ float_acc  * S_{i-1}        (because W is ternary, scale=1)
    #   b_int    = round(b_float * S_{i-1})    (match accumulator units)
    #   shift_i  = round(log2(S_{i-1} / S_i))  (rescale to output int8 range)
    #
    # For the output layer: shift=0, no activation, no clamp.

    # ── Step 1: calibrate input_scale and per-layer output scales ───────────
    print("  Calibrating scales on full test set …")

    # input_scale: map 99.9th-percentile |pixel| to 127
    all_inputs = np.concatenate(
        [d.view(d.size(0), -1).numpy() for d, _ in test_ld], axis=0)
    p999_in    = np.percentile(np.abs(all_inputs), 99.9)
    input_scale = 127.0 / max(float(p999_in), 1e-6)

    # Run float ternary model, record per-layer output activations
    layer_acts = [[] for _ in range(len(float_weights))]
    model.eval()
    with torch.no_grad():
        for cal_data, _ in test_ld:
            x = cal_data.view(cal_data.size(0), -1)
            for j, layer in enumerate(model.layers):
                is_last_l = (j == len(model.layers) - 1)
                x = F.linear(x, ternary(layer.weight), layer.bias)
                if not is_last_l:
                    x = torch.relu(x).clamp(-128, 127)
                layer_acts[j].append(x.detach().numpy())

    output_scales = []
    for j, acts in enumerate(layer_acts):
        vals = np.concatenate([a.reshape(-1) for a in acts])
        p999 = np.percentile(np.abs(vals), 99.9)
        output_scales.append(127.0 / max(float(p999), 1e-6))

    print(f"  input_scale = {input_scale:.4f}")
    for j, s in enumerate(output_scales):
        print(f"  layer {j} output_scale = {s:.4f}")

    # ── Step 2: derive shift_i and b_int for each layer ─────────────────────
    shifts      = []
    activations = []
    clamps      = []
    bias_int32  = []

    prev_scale = input_scale   # S_{i-1}

    for i, (W, b_float) in enumerate(zip(float_weights, float_biases)):
        is_last = (i == len(float_weights) - 1)

        if is_last:
            shift_i = 0
            # Don't rescale output logits; keep prev_scale for bias
        else:
            S_out   = output_scales[i]
            ratio   = prev_scale / max(S_out, 1e-9)
            # Round to nearest integer shift; 0 means no rescaling
            shift_i = max(0, min(15, int(round(np.log2(max(ratio, 1.0))))))

        # Bias lives in accumulator space (before the >> shift)
        b_int = np.round(b_float * prev_scale).astype(np.int32)

        shifts.append(shift_i)
        bias_int32.append(b_int)
        activations.append(0 if is_last else 1)
        clamps.append(0 if is_last else 1)

        if not is_last:
            prev_scale = output_scales[i]
        # output layer: prev_scale unchanged (unused)

    # -----------------------------------------------------------------------
    # Software accuracy check — integer pipeline (mirrors FPGA exactly)
    # -----------------------------------------------------------------------
    print("\nRunning quantized integer software eval …")
    q_correct = q_total = 0

    for cal_data, cal_labels in test_ld:
        batch_np = cal_data.view(cal_data.size(0), -1).numpy()
        act_sw   = np.clip(np.round(batch_np * input_scale), -128, 127).astype(np.int64).T

        for i, (W, b_i) in enumerate(zip(float_weights, bias_int32)):
            is_last = (i == len(float_weights) - 1)
            raw_sw  = W.astype(np.int64) @ act_sw
            biased  = (raw_sw + b_i[:, None]) >> shifts[i]
            if not is_last:
                biased = np.maximum(0, biased)
                biased = np.clip(biased, -128, 127)
            act_sw = biased

        preds = np.argmax(act_sw, axis=0)
        labs  = cal_labels.numpy()
        q_correct += int((preds == labs).sum())
        q_total   += len(labs)

    print(f"Quantized SW accuracy : {100*q_correct/q_total:.2f}%  ({q_correct}/{q_total})")

    # Sanity: float ternary accuracy
    f_correct = f_total = 0
    model.eval()
    with torch.no_grad():
        for cal_data, cal_labels in test_ld:
            out = model(cal_data, quantize=True)
            f_correct += out.argmax(1).eq(cal_labels).sum().item()
            f_total   += len(cal_labels)
    print(f"Float ternary accuracy: {100*f_correct/f_total:.2f}%  (gap = {(f_correct-q_correct)/f_total*100:.2f}%)")

    # -----------------------------------------------------------------------
    # Pad layers to multiples of N=4 and save
    # -----------------------------------------------------------------------
    N = 4
    save_dict = {
        "num_layers":   np.array(len(float_weights), dtype=np.int32),
        "input_scale":  np.array(input_scale,        dtype=np.float32),
    }

    for i, (W, b_i) in enumerate(zip(float_weights, bias_int32)):
        M, K = W.shape
        # Pad rows (M) and cols (K) to multiples of N
        M_pad = ((M + N - 1) // N) * N
        K_pad = ((K + N - 1) // N) * N
        W_pad = np.zeros((M_pad, K_pad), dtype=np.int8)
        W_pad[:M, :K] = W
        b_pad = np.zeros(M_pad, dtype=np.int32)
        b_pad[:M] = b_i

        save_dict[f"W{i}"]          = W_pad
        save_dict[f"b{i}"]          = b_pad
        save_dict[f"shift{i}"]      = np.array(shifts[i],      dtype=np.int32)
        save_dict[f"activation{i}"] = np.array(activations[i], dtype=np.int32)
        save_dict[f"clamp{i}"]      = np.array(clamps[i],      dtype=np.int32)
        # Store true (un-padded) output dimension so inference knows where to stop
        save_dict[f"M_true{i}"]     = np.array(M,              dtype=np.int32)
        save_dict[f"K_true{i}"]     = np.array(K,              dtype=np.int32)

    np.savez(output_path, **save_dict)
    print(f"\nSaved → {output_path}")
    print("Run:  python mnist_fpga.py test  (or demo / train is already done)")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train ternary MNIST MLP for FPGA")
    parser.add_argument("--arch",       default="784,256,64,10",
                        help="Layer sizes comma-separated (default: 784,256,64,10)")
    parser.add_argument("--epochs",     type=int,   default=15)
    parser.add_argument("--lr",         type=float, default=1e-3)
    parser.add_argument("--batch-size", type=int,   default=128)
    parser.add_argument("--output",     default="mnist_ternary.npz")
    parser.add_argument("--relu6",      type=int,   default=6,
                        help="Threshold for ReLU6 (unused if activation=ReLU)")
    args = parser.parse_args()

    arch = list(map(int, args.arch.split(",")))
    if arch[0] != 784:
        print("Warning: first layer should be 784 for MNIST; got", arch[0])

    train(arch, args.epochs, args.lr, args.batch_size, args.output, args.relu6)