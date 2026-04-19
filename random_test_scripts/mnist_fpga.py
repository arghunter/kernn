#!/usr/bin/env python3
"""
Generic MNIST FPGA inference — loads ANY MLP saved as .npz.

Supports both the original 2-layer int8 format and the new generic
multi-layer format produced by train_ternary.py (or any other trainer
that follows the layout below).

.npz layout (generic format):
  num_layers   : int           — number of linear layers
  input_scale  : float         — raw float → int8 multiplier for the input
  W{i}         : int8 (M, K)   — weight matrix (already padded to N=4 multiples)
  b{i}         : int32 (M,)    — bias in accumulator domain
  shift{i}     : int           — right-shift after bias add
  activation{i}: int           — 0=linear  1=ReLU  2=leaky  3=ReLU6
  clamp{i}     : int           — 1=clamp output to int8
  M_true{i}    : int           — true (un-padded) output width
  K_true{i}    : int           — true (un-padded) input width  [optional]

Legacy 2-layer format (original script):
  W0, W1, b0, b1, input_scale, shift0
  (auto-detected when num_layers is absent)

Usage:
  python mnist_fpga.py train  [--model mnist_ternary.npz]
        → re-run the old 2-layer int8 trainer (for quick sanity check)
  python mnist_fpga.py test   [/dev/ttyUSB1] [--model FILE.npz]
  python mnist_fpga.py demo   [/dev/ttyUSB1] [--model FILE.npz]
  python mnist_fpga.py swtest [--model FILE.npz]
        → software-only reference run (no FPGA needed)
"""

import os
import sys
import time
import struct
import argparse
import numpy as np

# ── FPGA protocol constants ─────────────────────────────────────────────────
N     = 4          # systolic-array tile size
BAUD  = 115_200

# ── Low-level serialisation helpers ─────────────────────────────────────────

def u16(v):
    return bytes([(v >> 8) & 0xFF, v & 0xFF])

def s8(v):
    return bytes([int(v) & 0xFF])

def s32le(v):
    return struct.pack('<i', int(v))


# ── Protocol commands ────────────────────────────────────────────────────────

def send_config(ser, layers):
    ser.write(bytes([0x01, len(layers)]))
    for L in layers:
        ser.write(u16(L["weight_base"]))
        ser.write(u16(L["bias_base"]))
        ser.write(u16(L["M"]))
        ser.write(u16(L["K"]))
        ser.write(u16(L["N"]))
        ser.write(bytes([L["activation"] & 0x03]))
        ser.write(bytes([L["shift"]      & 0x1F]))
        ser.write(u16(L["relu6_thresh"]))
        ser.write(bytes([1 if L["clamp_en"] else 0]))


def send_weights(ser, W, m, k, base_addr):
    """Send weight matrix W (m×k) in systolic tile order."""
    tiles_k = k // N
    count   = (m // N) * tiles_k * N
    ser.write(bytes([0x02]))
    ser.write(u16(base_addr))
    ser.write(u16(count))
    for tR in range(m // N):
        for tK in range(tiles_k):
            for t in range(N):
                for r in range(N):
                    ser.write(s8(W[tR * N + r][tK * N + t]))


def send_bias(ser, bias, nn, base_addr):
    count = nn // N
    ser.write(bytes([0x03]))
    ser.write(u16(base_addr))
    ser.write(u16(count))
    for tC in range(count):
        for c in range(N):
            ser.write(s32le(bias[tC * N + c]))


def send_activations(ser, A, k, nn, base_addr):
    tiles_n = nn // N
    count   = (k // N) * tiles_n * N
    ser.write(bytes([0x04]))
    ser.write(u16(base_addr))
    ser.write(u16(count))
    for tK in range(k // N):
        for tC in range(tiles_n):
            for t in range(N):
                for c in range(N):
                    ser.write(s8(A[tK * N + t][tC * N + c]))


def send_run(ser, input_base, buf_b_base):
    ser.write(bytes([0x05]))
    ser.write(u16(input_base))
    ser.write(u16(buf_b_base))


def read_output(ser, m, nn, base_addr=0):
    tiles_n = nn // N
    count   = (m // N) * tiles_n * N
    ser.write(bytes([0x06]))
    ser.write(u16(base_addr))
    ser.write(u16(count))
    result = np.zeros((m, nn), dtype=np.int64)
    for tR in range(m // N):
        for tC in range(tiles_n):
            for r in range(N):
                raw_bytes = ser.read(N * 4)
                if len(raw_bytes) != N * 4:
                    raise RuntimeError(
                        f"Timeout reading output (got {len(raw_bytes)} bytes)")
                for c in range(N):
                    val = struct.unpack_from('<i', raw_bytes, c * 4)[0]
                    result[tR * N + r][tC * N + c] = val
    marker = ser.read(1)
    if not marker or marker[0] != 0xFF:
        raise RuntimeError(f"Expected 0xFF marker, got {marker.hex() if marker else 'nothing'}")
    return result


# ── Software reference inference ─────────────────────────────────────────────

def sw_inference(weights, biases, input_act, configs):
    """Pure-NumPy reference that mirrors the FPGA pipeline exactly."""
    act = input_act.copy().astype(np.int64)
    for W, b, cfg in zip(weights, biases, configs):
        raw = W.astype(np.int64) @ act.astype(np.int64)   # (M, batch)
        for r in range(raw.shape[0]):
            for c in range(raw.shape[1]):
                val = (raw[r, c] + int(b[r])) >> cfg["shift"]
                fn  = cfg["activation"]
                if   fn == 1: val = max(0, val)
                elif fn == 2: val = val >> 3 if val < 0 else val
                elif fn == 3: val = min(max(0, val), cfg["relu6_thresh"])
                if cfg["clamp_en"]:
                    val = max(-128, min(127, val))
                raw[r, c] = val
        act = raw
    return act


# ── .npz model loader ─────────────────────────────────────────────────────────

class LayerConfig:
    """All information needed for one linear layer."""
    def __init__(self, W, b, shift, activation, clamp_en, M_true, K_true):
        self.W          = W           # int8 (M_pad, K_pad)
        self.b          = b           # int32 (M_pad,)
        self.shift      = int(shift)
        self.activation = int(activation)
        self.clamp_en   = bool(clamp_en)
        self.M_pad      = W.shape[0]
        self.K_pad      = W.shape[1]
        self.M_true     = int(M_true)
        self.K_true     = int(K_true)


def load_model(path: str) -> tuple[list[LayerConfig], float]:
    """
    Load a .npz model file.  Returns (layers, input_scale).
    Handles both legacy 2-layer format and the new generic format.
    """
    if not os.path.exists(path):
        print(f"Model file '{path}' not found.")
        print("  For ternary model:   python train_ternary.py")
        print("  For int8 model:      python mnist_fpga.py train")
        sys.exit(1)

    data = np.load(path)
    keys = set(data.keys())

    # ── Generic multi-layer format ──────────────────────────────────────────
    if "num_layers" in keys:
        num_layers  = int(data["num_layers"])
        input_scale = float(data["input_scale"])
        layers      = []
        for i in range(num_layers):
            W  = data[f"W{i}"].astype(np.int8)
            b  = data[f"b{i}"].astype(np.int32)
            sh = int(data[f"shift{i}"])
            ac = int(data[f"activation{i}"])
            cl = int(data[f"clamp{i}"])
            Mt = int(data[f"M_true{i}"]) if f"M_true{i}" in keys else W.shape[0]
            Kt = int(data[f"K_true{i}"]) if f"K_true{i}" in keys else W.shape[1]
            layers.append(LayerConfig(W, b, sh, ac, cl, Mt, Kt))
        return layers, input_scale

    # ── Legacy 2-layer int8 format (original script) ────────────────────────
    print("  [info] Legacy 2-layer .npz detected — adapting …")
    W0 = data["W0"].astype(np.int8)    # (64,  784)
    W1 = data["W1"].astype(np.int8)    # (12,  64)
    b0 = data["b0"].astype(np.int32)   # (64,)
    b1 = data["b1"].astype(np.int32)   # (12,)
    sh0         = int(data["shift0"])
    input_scale = float(data["input_scale"])

    # Pad to multiples of N if needed (legacy files already padded W1 to 12)
    def _pad(W, b):
        M, K   = W.shape
        M_true = M
        K_true = K
        Mp = ((M + N - 1) // N) * N
        Kp = ((K + N - 1) // N) * N
        Wp = np.zeros((Mp, Kp), dtype=np.int8)
        bp = np.zeros(Mp, dtype=np.int32)
        Wp[:M, :K] = W
        bp[:M]     = b
        return Wp, bp, M_true, K_true

    W0p, b0p, M0t, K0t = _pad(W0, b0)
    W1p, b1p, M1t, K1t = _pad(W1, b1)

    layers = [
        LayerConfig(W0p, b0p, sh0, activation=1, clamp_en=True,  M_true=M0t, K_true=K0t),
        LayerConfig(W1p, b1p, 0,   activation=0, clamp_en=False, M_true=M1t, K_true=K1t),
    ]
    return layers, input_scale


# ── Build FPGA layer descriptors from loaded LayerConfig list ────────────────

def build_fpga_configs(layers: list[LayerConfig]):
    """
    Compute memory addresses for weights and biases and return the list of
    per-layer config dicts consumed by send_config / send_weights / send_bias.

    Weight memory is laid out consecutively.
    Bias  memory is laid out consecutively (separate address space).
    """
    fpga_cfgs   = []
    w_base      = 0
    b_base      = 0

    for lc in layers:
        M, K = lc.M_pad, lc.K_pad
        w_tiles = (M // N) * (K // N) * N   # elements sent (= send_weights count)
        b_tiles = M // N                     # groups of N biases

        fpga_cfgs.append({
            "weight_base":  w_base,
            "bias_base":    b_base,
            "M":            M,
            "K":            K,
            "N":            N,
            "activation":   lc.activation,
            "shift":        lc.shift,
            "relu6_thresh": 6,
            "clamp_en":     lc.clamp_en,
        })

        w_base += w_tiles
        b_base += b_tiles

    return fpga_cfgs


# ── FPGA model upload ────────────────────────────────────────────────────────

def upload_model_to_fpga(ser, layers: list[LayerConfig]):
    fpga_cfgs = build_fpga_configs(layers)

    print("  Sending config …")
    send_config(ser, fpga_cfgs)

    w_base = 0
    b_base = 0
    for i, lc in enumerate(layers):
        M, K = lc.M_pad, lc.K_pad
        print(f"  Layer {i}: weights ({M}×{K}) …", end=" ", flush=True)
        t = time.time()
        send_weights(ser, lc.W.tolist(), M, K, w_base)
        print(f"{time.time()-t:.1f}s")

        send_bias(ser, lc.b.tolist(), M, b_base)

        w_base += (M // N) * (K // N) * N
        b_base +=  M // N

    return fpga_cfgs


# ── FPGA batch inference (always 4 images at a time) ────────────────────────

def classify_batch_fpga(ser, images_int8, layers: list[LayerConfig], fpga_cfgs):
    """
    images_int8: (4, K_input) int8
    Returns (4,) predicted classes and the raw final-layer output matrix.
    """
    K_in = layers[0].K_pad
    A    = images_int8.T.astype(np.int8)   # (K_in, 4)
    send_activations(ser, A.tolist(), K_in, N, 0)
    send_run(ser, 0, 2048)

    ack = ser.read(1)
    if not ack:
        raise RuntimeError("Timeout waiting for ACK")
    if ack[0] != 0xAA:
        raise RuntimeError(f"Expected 0xAA, got 0x{ack[0]:02X}")

    last = layers[-1]
    result = read_output(ser, last.M_pad, N)   # (M_pad, 4)
    # Only look at the true output neurons for argmax
    preds = np.argmax(result[:last.M_true, :], axis=0)
    return preds, result


# ── Helpers ──────────────────────────────────────────────────────────────────

def quantize_input(img_np, input_scale):
    return np.clip(np.round(img_np * input_scale), -128, 127).astype(np.int8)


def print_digit(pixels, label=None, predicted=None):
    chars = " .:-=+*#%@"
    img   = pixels.astype(np.int16).reshape(28, 28)
    for row in img:
        line = ""
        for px in row:
            idx   = int(np.clip((px + 128) / 256 * len(chars), 0, len(chars) - 1))
            line += chars[idx] * 2
        print(f"  {line}")
    if label is not None and predicted is not None:
        status = "✓ CORRECT" if label == predicted else "✗ WRONG"
        print(f"  Label: {label}  Predicted: {predicted}  [{status}]")


# ── Commands ─────────────────────────────────────────────────────────────────

def cmd_train_legacy():
    """Quick re-run of the original 2-layer int8 training (for reference)."""
    try:
        import torch, torch.nn as nn, torch.optim as optim
        from torchvision import datasets, transforms
    except ImportError:
        print("pip install torch torchvision"); sys.exit(1)

    print("=" * 60)
    print("Training (legacy) 2-layer int8 MNIST model")
    print("  784 -> 64 (ReLU+clamp) -> 12 (linear, padded from 10)")
    print("=" * 60)

    class Net(nn.Module):
        def __init__(self):
            super().__init__()
            self.fc1 = nn.Linear(784, 64)
            self.fc2 = nn.Linear(64, 12)
        def forward(self, x):
            x = torch.relu(self.fc1(x.view(-1, 784)))
            return self.fc2(x)[:, :10]

    transform = transforms.Compose([
        transforms.ToTensor(), transforms.Normalize((0.1307,), (0.3081,))])
    train_ds = datasets.MNIST('./mnist_data', train=True,  download=True, transform=transform)
    test_ds  = datasets.MNIST('./mnist_data', train=False, download=True, transform=transform)
    train_ld = torch.utils.data.DataLoader(train_ds, batch_size=128, shuffle=True)
    test_ld  = torch.utils.data.DataLoader(test_ds,  batch_size=1000)

    model = Net()
    opt   = optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    crit  = nn.CrossEntropyLoss()

    for epoch in range(10):
        model.train()
        tr_c = tr_t = loss_sum = 0
        for data, target in train_ld:
            opt.zero_grad()
            out  = model(data)
            loss = crit(out, target)
            loss.backward(); opt.step()
            with torch.no_grad():
                for p in model.parameters(): p.clamp_(-2, 2)
            loss_sum += loss.item(); tr_c += out.argmax(1).eq(target).sum().item(); tr_t += len(target)
        model.eval(); te_c = te_t = 0
        with torch.no_grad():
            for data, target in test_ld:
                out = model(data); te_c += out.argmax(1).eq(target).sum().item(); te_t += len(target)
        print(f"  Epoch {epoch+1}/10  train {100*tr_c/tr_t:.1f}%  test {100*te_c/te_t:.1f}%  loss {loss_sum/len(train_ld):.4f}")

    # quantize & save using the generic format
    model.eval()
    with torch.no_grad():
        W0f = model.fc1.weight.data.numpy()
        b0f = model.fc1.bias.data.numpy()
        W1f = model.fc2.weight.data.numpy()
        b1f = model.fc2.bias.data.numpy()

    input_scale = 127.0 / 2.8
    w0s = 127.0 / max(abs(W0f.max()), abs(W0f.min()))
    w1s = 127.0 / max(abs(W1f.max()), abs(W1f.min()))
    W0i = np.clip(np.round(W0f * w0s), -128, 127).astype(np.int8)
    W1i = np.clip(np.round(W1f * w1s), -128, 127).astype(np.int8)

    test_inp    = next(iter(test_ld))[0][0].view(784).numpy()
    test_i8     = np.clip(np.round(test_inp * input_scale), -128, 127)
    acc0        = W0i.astype(np.int64) @ test_i8.astype(np.int64)
    acc_max     = max(abs(acc0.max()), abs(acc0.min()), 1)
    shift0      = min(max(0, int(np.log2(acc_max / 127))), 15)

    b0i = np.round(b0f * w0s * input_scale).astype(np.int32)
    b1i = np.round(b1f * w1s).astype(np.int32)
    b1i = np.pad(b1i, (0, max(0, 12 - len(b1i))), constant_values=0)

    W1_pad = np.zeros((12, 64), dtype=np.int8)
    W1_pad[:W1i.shape[0], :] = W1i

    save = {
        "num_layers":   np.int32(2),
        "input_scale":  np.float32(input_scale),
        "W0": W0i,  "b0": b0i,  "shift0": np.int32(shift0),
        "activation0":  np.int32(1), "clamp0": np.int32(1),
        "M_true0": np.int32(64),  "K_true0": np.int32(784),
        "W1": W1_pad, "b1": b1i, "shift1": np.int32(0),
        "activation1":  np.int32(0), "clamp1": np.int32(0),
        "M_true1": np.int32(10),  "K_true1": np.int32(64),
    }
    np.savez("mnist_quantized.npz", **save)
    print("\nSaved → mnist_quantized.npz")


def cmd_swtest(model_path: str):
    """Software-only inference on the full MNIST test set (no FPGA required)."""
    try:
        import torch
        from torchvision import datasets, transforms
    except ImportError:
        print("pip install torch torchvision"); sys.exit(1)

    layers, input_scale = load_model(model_path)
    print(f"Loaded model: {model_path}")
    print(f"  {len(layers)} layers   input_scale={input_scale:.3f}")
    for i, lc in enumerate(layers):
        print(f"  Layer {i}: W={lc.M_pad}×{lc.K_pad} (true {lc.M_true}×{lc.K_true})"
              f"  shift={lc.shift}  act={lc.activation}  clamp={lc.clamp_en}")

    transform = transforms.Compose([
        transforms.ToTensor(), transforms.Normalize((0.1307,), (0.3081,))])
    test_ds = datasets.MNIST('./mnist_data', train=False, download=True, transform=transform)
    test_ld = torch.utils.data.DataLoader(test_ds, batch_size=1000)

    correct = total = 0
    t_start = time.time()
    for data, labels in test_ld:
        batch_np  = data.view(data.size(0), -1).numpy()
        batch_i8  = np.clip(np.round(batch_np * input_scale), -128, 127).astype(np.int64)
        act       = batch_i8.T   # (K, B)
        for lc in layers:
            W   = lc.W.astype(np.int64)
            raw = W @ act
            raw = (raw + lc.b[:, None]) >> lc.shift
            fn  = lc.activation
            if   fn == 1: raw = np.maximum(0, raw)
            elif fn == 2: raw = np.where(raw < 0, raw >> 3, raw)
            elif fn == 3: raw = np.clip(raw, 0, 6)
            if lc.clamp_en: raw = np.clip(raw, -128, 127)
            act = raw
        last = layers[-1]
        preds    = np.argmax(act[:last.M_true, :], axis=0)
        labs     = labels.numpy()
        correct += int((preds == labs).sum())
        total   += len(labs)

    elapsed = time.time() - t_start
    print(f"\nSoftware accuracy: {correct}/{total}  ({100*correct/total:.2f}%)")
    print(f"Elapsed: {elapsed:.2f}s  ({total/elapsed:.0f} images/s)")


def cmd_test_fpga(port: str, model_path: str):
    try:
        from torchvision import datasets, transforms
        import serial as ser_mod
    except ImportError:
        print("pip install torchvision pyserial"); sys.exit(1)

    layers, input_scale = load_model(model_path)
    K_in = layers[0].K_true

    transform = transforms.Compose([
        transforms.ToTensor(), transforms.Normalize((0.1307,), (0.3081,))])
    test_ds = datasets.MNIST('./mnist_data', train=False, download=True, transform=transform)

    ser = ser_mod.Serial(port, BAUD, timeout=30)
    time.sleep(0.1); ser.reset_input_buffer(); ser.reset_output_buffer()

    try:
        print(f"\n{'='*60}")
        print(f"MNIST FPGA Classification  [{model_path}]")
        print(f"  {len(layers)} layers   port={port}")
        print(f"{'='*60}")

        print("\nUploading model …")
        t0 = time.time()
        fpga_cfgs = upload_model_to_fpga(ser, layers)
        print(f"  Done in {time.time()-t0:.1f}s")

        num_batches = 50   # 200 images
        correct = total = 0
        fpga_time = 0.0

        print(f"\nClassifying {num_batches * N} images in batches of {N} …\n")

        for bi in range(num_batches):
            imgs = np.zeros((N, layers[0].K_pad), dtype=np.int8)
            labs = np.zeros(N, dtype=np.int64)
            for j in range(N):
                img, label = test_ds[bi * N + j]
                imgs[j, :K_in] = quantize_input(img.view(-1).numpy(), input_scale)
                labs[j] = label

            t = time.time()
            preds, _ = classify_batch_fpga(ser, imgs, layers, fpga_cfgs)
            fpga_time += time.time() - t

            for j in range(N):
                if preds[j] == labs[j]: correct += 1
                total += 1

            if (bi + 1) % 10 == 0:
                print(f"  Batch {bi+1}/{num_batches}: {correct}/{total} ({100*correct/total:.1f}%)")

        print(f"\n{'='*60}")
        print(f"Accuracy  : {correct}/{total} ({100*correct/total:.1f}%)")
        print(f"FPGA time : {fpga_time:.2f}s for {total} images")
        print(f"Throughput: {total/fpga_time:.1f} images/sec")
        print(f"{'='*60}")

        # Show 4 sample digits
        print("\nSample classifications:")
        imgs2 = np.zeros((N, layers[0].K_pad), dtype=np.int8)
        labs2 = np.zeros(N, dtype=np.int64)
        for j in range(N):
            img, label = test_ds[j]
            imgs2[j, :K_in] = quantize_input(img.view(-1).numpy(), input_scale)
            labs2[j] = label
        preds2, raw = classify_batch_fpga(ser, imgs2, layers, fpga_cfgs)
        for j in range(N):
            print(f"\n--- Sample {j} ---")
            print_digit(imgs2[j, :784], labs2[j], preds2[j])
            Mt = layers[-1].M_true
            print(f"  Logits: {raw[:Mt, j]}")

    finally:
        ser.close()


def cmd_demo(port: str, model_path: str):
    try:
        from torchvision import datasets, transforms
        import serial as ser_mod
    except ImportError:
        print("pip install torchvision pyserial"); sys.exit(1)

    layers, input_scale = load_model(model_path)
    K_in = layers[0].K_true

    transform = transforms.Compose([
        transforms.ToTensor(), transforms.Normalize((0.1307,), (0.3081,))])
    test_ds = datasets.MNIST('./mnist_data', train=False, download=True, transform=transform)

    ser = ser_mod.Serial(port, BAUD, timeout=30)
    time.sleep(0.1); ser.reset_input_buffer(); ser.reset_output_buffer()

    try:
        print(f"\n{'='*60}")
        print(f"MNIST FPGA Demo  [{model_path}]")
        print(f"{'='*60}\n")

        print("Uploading model …")
        fpga_cfgs = upload_model_to_fpga(ser, layers)
        print("  Ready!\n")

        rng = np.random.default_rng()
        Mt  = layers[-1].M_true

        while True:
            idxs = rng.integers(0, len(test_ds), size=N)
            imgs = np.zeros((N, layers[0].K_pad), dtype=np.int8)
            labs = np.zeros(N, dtype=np.int64)
            for j, idx in enumerate(idxs):
                img, label = test_ds[int(idx)]
                imgs[j, :K_in] = quantize_input(img.view(-1).numpy(), input_scale)
                labs[j] = label

            t = time.time()
            preds, raw = classify_batch_fpga(ser, imgs, layers, fpga_cfgs)
            elapsed = time.time() - t

            for j in range(N):
                print(f"\n--- Image #{idxs[j]} ---")
                print_digit(imgs[j, :784], labs[j], preds[j])
                logits   = raw[:Mt, j]
                top3_idx = np.argsort(-logits)[:3]
                print(f"  Top 3: " + "  ".join(f"{ci}({logits[ci]})" for ci in top3_idx))

            print(f"\n  Inference: {elapsed*1000:.0f}ms for {N} images")
            if input("\nEnter for next batch, 'q' to quit: ").strip().lower() == 'q':
                break

    finally:
        ser.close()


# ── Entry point ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="MNIST FPGA inference — loads any MLP .npz",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    parser.add_argument("cmd",   nargs="?", default="help",
                        choices=["train", "test", "demo", "swtest", "help"],
                        help="Command to run")
    parser.add_argument("port",  nargs="?", default="/dev/ttyUSB1",
                        help="Serial port (default: /dev/ttyUSB1)")
    parser.add_argument("--model", default=None,
                        help="Path to .npz model file "
                             "(default: mnist_ternary.npz if exists, else mnist_quantized.npz)")
    args = parser.parse_args()

    # Default model selection
    if args.model is None:
        if os.path.exists("mnist_ternary.npz"):
            args.model = "mnist_quantized.npz"
        else:
            args.model = "mnist_quantized.npz"

    if args.cmd == "train":
        cmd_train_legacy()
    elif args.cmd == "swtest":
        cmd_swtest(args.model)
    elif args.cmd == "test":
        cmd_test_fpga(args.port, args.model)
    elif args.cmd == "demo":
        cmd_demo(args.port, args.model)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()