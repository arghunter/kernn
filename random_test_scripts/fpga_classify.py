#!/usr/bin/env python3
"""
Train quantized classifiers and run inference on FPGA.

Supported models:
  mnist          784 -> 64 (ReLU) -> 12    Handwritten digits 0-9
  fashion        784 -> 128 (ReLU) -> 64 (ReLU) -> 12    Fashion items
  cifar10        3072 -> 256 (ReLU) -> 64 (ReLU) -> 12   CIFAR-10 objects

Usage:
  python fpga_classify.py train mnist
  python fpga_classify.py train fashion
  python fpga_classify.py train cifar10
  python fpga_classify.py test [port]           # choose model interactively
  python fpga_classify.py test [port] mnist
  python fpga_classify.py demo [port]           # choose model interactively
  python fpga_classify.py demo [port] fashion
"""

import os
import sys
import time
import struct
import numpy as np

# ═══════════════════════════════════════════════════════════════
#  FPGA Protocol
# ═══════════════════════════════════════════════════════════════

N = 12
BAUD = 115200

def u16(v):
    return bytes([(v >> 8) & 0xFF, v & 0xFF])

def s8(v):
    return bytes([v & 0xFF])

def s32le(v):
    return struct.pack('<i', int(v))

def send_config(ser, layers):
    ser.write(bytes([0x01, len(layers)]))
    for L in layers:
        ser.write(u16(L["weight_base"]))
        ser.write(u16(L["bias_base"]))
        ser.write(u16(L["M"]))
        ser.write(u16(L["K"]))
        ser.write(u16(L["N"]))
        ser.write(bytes([L["activation"] & 0x03]))
        ser.write(bytes([L["shift"] & 0x1F]))
        ser.write(u16(L["relu6_thresh"]))
        ser.write(bytes([1 if L["clamp_en"] else 0]))

def send_weights(ser, W, m, k, base_addr):
    tiles_k = k // N
    count = (m // N) * tiles_k * N
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
    count = (k // N) * tiles_n * N
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
    count = (m // N) * tiles_n * N
    ser.write(bytes([0x06]))
    ser.write(u16(base_addr))
    ser.write(u16(count))
    result = np.zeros((m, nn), dtype=np.int64)
    for tR in range(m // N):
        for tC in range(tiles_n):
            for r in range(N):
                raw_bytes = ser.read(N * 4)
                if len(raw_bytes) != N * 4:
                    raise RuntimeError(f"Timeout reading output, got {len(raw_bytes)} bytes")
                for c in range(N):
                    val = struct.unpack_from('<i', raw_bytes, c * 4)[0]
                    result[tR * N + r][tC * N + c] = val
    marker = ser.read(1)
    if len(marker) != 1 or marker[0] != 0xFF:
        raise RuntimeError(f"Expected 0xFF marker, got {marker.hex() if marker else 'nothing'}")
    return result


# ═══════════════════════════════════════════════════════════════
#  Model Definitions
# ═══════════════════════════════════════════════════════════════

MODEL_REGISTRY = {
    "mnist": {
        "name": "MNIST Digits",
        "dataset": "MNIST",
        "input_dim": 784,
        "num_classes": 10,
        "class_names": ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9"],
        "layers": [
            {"in": 784, "out": 64, "activation": 1, "shift": "auto", "clamp": True},
            {"in": 64,  "out": 12, "activation": 0, "shift": 0,      "clamp": False},
        ],
        "epochs": 10,
        "lr": 1e-3,
        "weight_clamp": 2.0,
        "img_shape": (28, 28),
        "channels": 1,
        "normalize": ((0.1307,), (0.3081,)),
        "file": "model_mnist.npz",
    },
    "fashion": {
        "name": "Fashion-MNIST",
        "dataset": "FashionMNIST",
        "input_dim": 784,
        "num_classes": 10,
        "class_names": ["T-shirt", "Trouser", "Pullover", "Dress", "Coat",
                        "Sandal", "Shirt", "Sneaker", "Bag", "Boot"],
        "layers": [
            {"in": 784, "out": 128, "activation": 1, "shift": "auto", "clamp": True},
            {"in": 128, "out": 64,  "activation": 1, "shift": "auto", "clamp": True},
            {"in": 64,  "out": 12,  "activation": 0, "shift": 0,      "clamp": False},
        ],
        "epochs": 15,
        "lr": 1e-3,
        "weight_clamp": 2.0,
        "img_shape": (28, 28),
        "channels": 1,
        "normalize": ((0.2860,), (0.3530,)),
        "file": "model_fashion.npz",
    },
    "cifar10": {
        "name": "CIFAR-10",
        "dataset": "CIFAR10",
        "input_dim": 3072,
        "num_classes": 10,
        "class_names": ["airplane", "automobile", "bird", "cat", "deer",
                        "dog", "frog", "horse", "ship", "truck"],
        "layers": [
            {"in": 3072, "out": 256, "activation": 1, "shift": "auto", "clamp": True},
            {"in": 256,  "out": 64,  "activation": 1, "shift": "auto", "clamp": True},
            {"in": 64,   "out": 12,  "activation": 0, "shift": 0,      "clamp": False},
        ],
        "epochs": 20,
        "lr": 1e-3,
        "weight_clamp": 2.0,
        "img_shape": (32, 32),
        "channels": 3,
        "normalize": ((0.4914, 0.4822, 0.4465), (0.2470, 0.2435, 0.2616)),
        "file": "model_cifar10.npz",
    },
}


def pad_to_n(size):
    """Round up to next multiple of N."""
    return size if size % N == 0 else size + (N - size % N)


# ═══════════════════════════════════════════════════════════════
#  Training (generic for any model spec)
# ═══════════════════════════════════════════════════════════════

def train(model_key):
    try:
        import torch
        import torch.nn as nn
        import torch.optim as optim
        from torchvision import datasets, transforms
    except ImportError:
        print("PyTorch and torchvision required. Install with: pip install torch torchvision")
        sys.exit(1)

    spec = MODEL_REGISTRY[model_key]
    layer_specs = spec["layers"]

    print("=" * 60)
    print(f"Training {spec['name']} for FPGA deployment")
    arch = " -> ".join([str(spec["input_dim"])] + [str(l["out"]) for l in layer_specs])
    print(f"  Architecture: {arch}")
    print("=" * 60)

    # Build dynamic model
    class Net(nn.Module):
        def __init__(self):
            super().__init__()
            self.fcs = nn.ModuleList()
            for l in layer_specs:
                self.fcs.append(nn.Linear(l["in"], l["out"]))

        def forward(self, x):
            x = x.view(-1, spec["input_dim"])
            for i, fc in enumerate(self.fcs):
                x = fc(x)
                if layer_specs[i]["activation"] == 1:
                    x = torch.relu(x)
                elif layer_specs[i]["activation"] == 2:
                    x = torch.nn.functional.leaky_relu(x, 0.125)
            return x[:, :spec["num_classes"]]

    # Dataset
    mean, std = spec["normalize"]
    transform = transforms.Compose([
        transforms.ToTensor(),
        transforms.Normalize(mean, std),
    ])
    if spec["channels"] == 3:
        transform = transforms.Compose([
            transforms.ToTensor(),
            transforms.Normalize(mean, std),
            transforms.Lambda(lambda x: x.view(-1)),  # flatten
        ])

    ds_class = getattr(datasets, spec["dataset"])
    print(f"\nDownloading {spec['dataset']}...")
    train_ds = ds_class('./data', train=True, download=True, transform=transform)
    test_ds = ds_class('./data', train=False, download=True, transform=transform)
    train_loader = torch.utils.data.DataLoader(train_ds, batch_size=128, shuffle=True)
    test_loader = torch.utils.data.DataLoader(test_ds, batch_size=1000, shuffle=False)

    model = Net()
    optimizer = optim.Adam(model.parameters(), lr=spec["lr"], weight_decay=1e-4)
    criterion = nn.CrossEntropyLoss()

    print("\nTraining...")
    for epoch in range(spec["epochs"]):
        model.train()
        total_loss = 0; correct = 0; total = 0
        for data, target in train_loader:
            if spec["channels"] == 3:
                data = data.view(data.size(0), -1)
            optimizer.zero_grad()
            output = model(data)
            loss = criterion(output, target)
            loss.backward()
            optimizer.step()
            with torch.no_grad():
                for param in model.parameters():
                    param.clamp_(-spec["weight_clamp"], spec["weight_clamp"])
            total_loss += loss.item()
            pred = output.argmax(dim=1)
            correct += pred.eq(target).sum().item()
            total += len(target)

        model.eval()
        tc = 0; tt = 0
        with torch.no_grad():
            for data, target in test_loader:
                if spec["channels"] == 3:
                    data = data.view(data.size(0), -1)
                output = model(data)
                tc += output.argmax(dim=1).eq(target).sum().item()
                tt += len(target)
        print(f"  Epoch {epoch+1}/{spec['epochs']} — "
              f"Train: {100*correct/total:.1f}%, Test: {100*tc/tt:.1f}%, "
              f"Loss: {total_loss/len(train_loader):.4f}")

    # ── Quantize ──
    print("\nQuantizing to int8...")
    model.eval()
    float_weights = []
    float_biases = []
    with torch.no_grad():
        for fc in model.fcs:
            float_weights.append(fc.weight.data.numpy())
            float_biases.append(fc.bias.data.numpy())

    # Input scale from normalization stats
    if spec["channels"] == 1:
        input_range = max(abs((0.0 - mean[0]) / std[0]), abs((1.0 - mean[0]) / std[0]))
    else:
        input_range = max(abs((0.0 - m) / s) for m, s in zip(mean, std))
        input_range = max(input_range, max(abs((1.0 - m) / s) for m, s in zip(mean, std)))
    input_scale = 127.0 / input_range

    int_weights = []
    int_biases = []
    shifts = []
    w_scales = []

    # Quantize layer by layer
    prev_scale = input_scale  # scale of the input to current layer
    for i, ls in enumerate(layer_specs):
        Wf = float_weights[i]
        bf = float_biases[i]

        w_scale = 127.0 / max(abs(Wf.max()), abs(Wf.min()), 1e-8)
        W_int = np.clip(np.round(Wf * w_scale), -128, 127).astype(np.int8)
        w_scales.append(w_scale)

        # Pad output dim to multiple of N
        out_padded = pad_to_n(ls["out"])
        if W_int.shape[0] < out_padded:
            W_int = np.pad(W_int, ((0, out_padded - W_int.shape[0]), (0, 0)))
        if len(bf) < out_padded:
            bf = np.pad(bf, (0, out_padded - len(bf)))

        # Bias in accumulator scale
        b_int = np.round(bf * w_scale * prev_scale).astype(np.int32)

        # Auto shift
        if ls["shift"] == "auto":
            # Estimate accumulator range on a sample
            sample = next(iter(test_loader))[0][0]
            if spec["channels"] == 3:
                sample = sample.view(-1)
            sample = sample.view(spec["input_dim"]).numpy()
            test_int = np.clip(np.round(sample * input_scale), -128, 127).astype(np.int8)
            # Run through previous layers to get this layer's input
            act = test_int.astype(np.int64)
            for j in range(i):
                acc = int_weights[j].astype(np.int64) @ act + int_biases[j].astype(np.int64)
                acc = acc >> shifts[j]
                if layer_specs[j]["activation"] == 1:
                    acc = np.maximum(0, acc)
                elif layer_specs[j]["activation"] == 2:
                    acc = np.where(acc < 0, acc >> 3, acc)
                if layer_specs[j]["clamp"]:
                    acc = np.clip(acc, -128, 127)
                act = acc
            test_acc = W_int[:out_padded].astype(np.int64) @ act + b_int.astype(np.int64)
            acc_max = max(abs(test_acc.max()), abs(test_acc.min()), 1)
            shift = max(0, int(np.log2(acc_max / 127)))
            shift = min(shift, 15)
        else:
            shift = ls["shift"]

        shifts.append(shift)
        int_weights.append(W_int)
        int_biases.append(b_int)

        # Next layer's input scale
        if ls["clamp"]:
            prev_scale = 1.0  # already int8
        else:
            prev_scale = prev_scale * w_scale / (1 << shift) if shift > 0 else prev_scale * w_scale

    # ── Evaluate quantized accuracy ──
    print("\nEvaluating quantized accuracy...")
    q_correct = 0; q_total = 0
    for data, target in test_loader:
        for idx in range(len(target)):
            if spec["channels"] == 3:
                inp = data[idx].view(-1).numpy()
            else:
                inp = data[idx].view(spec["input_dim"]).numpy()
            act = np.clip(np.round(inp * input_scale), -128, 127).astype(np.int64)
            for i, ls in enumerate(layer_specs):
                acc = int_weights[i].astype(np.int64) @ act + int_biases[i].astype(np.int64)
                acc = acc >> shifts[i]
                if ls["activation"] == 1:
                    acc = np.maximum(0, acc)
                elif ls["activation"] == 2:
                    acc = np.where(acc < 0, acc >> 3, acc)
                if ls["clamp"]:
                    acc = np.clip(acc, -128, 127)
                act = acc
            if np.argmax(act[:spec["num_classes"]]) == target[idx].item():
                q_correct += 1
            q_total += 1
    print(f"  Quantized accuracy: {100*q_correct/q_total:.1f}% ({q_correct}/{q_total})")

    # ── Save ──
    save_dict = {
        "model_key": model_key,
        "input_scale": input_scale,
        "num_layers": len(layer_specs),
        "num_classes": spec["num_classes"],
    }
    for i in range(len(layer_specs)):
        save_dict[f"W{i}"] = int_weights[i]
        save_dict[f"b{i}"] = int_biases[i]
        save_dict[f"shift{i}"] = shifts[i]
        save_dict[f"activation{i}"] = layer_specs[i]["activation"]
        save_dict[f"clamp{i}"] = layer_specs[i]["clamp"]
        save_dict[f"M{i}"] = int_weights[i].shape[0]
        save_dict[f"K{i}"] = int_weights[i].shape[1]

    np.savez(spec["file"], **save_dict)
    print(f"\nSaved to {spec['file']}")
    print(f"Run: python fpga_classify.py test [port] {model_key}")


# ═══════════════════════════════════════════════════════════════
#  FPGA Upload / Inference
# ═══════════════════════════════════════════════════════════════

def load_model(model_key):
    spec = MODEL_REGISTRY[model_key]
    path = spec["file"]
    if not os.path.exists(path):
        print(f"Model '{path}' not found. Train first: python fpga_classify.py train {model_key}")
        sys.exit(1)
    data = np.load(path, allow_pickle=True)
    num_layers = int(data["num_layers"])
    model = {
        "key": model_key,
        "spec": spec,
        "input_scale": float(data["input_scale"]),
        "num_classes": int(data["num_classes"]),
        "num_layers": num_layers,
        "weights": [],
        "biases": [],
        "shifts": [],
        "activations": [],
        "clamps": [],
        "M": [],
        "K": [],
    }
    for i in range(num_layers):
        model["weights"].append(data[f"W{i}"])
        model["biases"].append(data[f"b{i}"])
        model["shifts"].append(int(data[f"shift{i}"]))
        model["activations"].append(int(data[f"activation{i}"]))
        model["clamps"].append(bool(data[f"clamp{i}"]))
        model["M"].append(int(data[f"M{i}"]))
        model["K"].append(int(data[f"K{i}"]))
    return model


def upload_model(ser, model):
    """Upload full model to FPGA, return configs list."""
    num_layers = model["num_layers"]

    # Compute memory layout
    wt_offset = 0
    bias_offset = 0
    configs = []

    for i in range(num_layers):
        m = model["M"][i]
        k = model["K"][i]
        wt_base = wt_offset
        bias_base = bias_offset
        wt_offset += (m // N) * (k // N) * N
        bias_offset += m // N  # bias is indexed by output tiles

        configs.append({
            "weight_base": wt_base,
            "bias_base": bias_base,
            "M": m,
            "K": k,
            "N": N,
            "activation": model["activations"][i],
            "shift": model["shifts"][i],
            "relu6_thresh": 6,
            "clamp_en": 1 if model["clamps"][i] else 0,
        })

    print(f"  {num_layers} layers, weight mem: {wt_offset} tiles, bias mem: {bias_offset} tiles")
    print("  Sending config...")
    send_config(ser, configs)

    for i in range(num_layers):
        m, k = model["M"][i], model["K"][i]
        print(f"  Sending layer {i} weights ({m}x{k})...", end=" ", flush=True)
        t = time.time()
        send_weights(ser, model["weights"][i].tolist(), m, k, configs[i]["weight_base"])
        print(f"{time.time()-t:.1f}s")

    for i in range(num_layers):
        m = model["M"][i]
        send_bias(ser, model["biases"][i].tolist(), m, configs[i]["bias_base"])
    print("  Biases sent.")

    return configs


def classify_batch(ser, images_int8, model, configs):
    """
    Classify a batch of N images on the FPGA.
    images_int8: (N, input_dim) int8
    Returns: predictions (N,), raw_output (M_last, N)
    """
    input_dim = model["K"][0]
    A = images_int8.T.astype(np.int8).tolist()  # (input_dim, N)

    send_activations(ser, A, input_dim, N, 0)
    send_run(ser, 0, 2048)

    ack = ser.read(1)
    if len(ack) != 1:
        raise RuntimeError("Timeout waiting for ACK")
    if ack[0] != 0xAA:
        raise RuntimeError(f"Expected 0xAA, got 0x{ack[0]:02X}")

    m_last = model["M"][-1]
    result = read_output(ser, m_last, N)
    nc = model["num_classes"]
    predictions = np.argmax(result[:nc, :], axis=0)
    return predictions, result


# ═══════════════════════════════════════════════════════════════
#  Display helpers
# ═══════════════════════════════════════════════════════════════

def print_image(pixels, shape, channels, label=None, predicted=None, class_names=None):
    """Render image as ASCII art."""
    chars = " .:-=+*#%@"
    h, w = shape
    if channels == 1:
        img = pixels.astype(np.int16).reshape(h, w)
        for row in img:
            line = ""
            for px in row:
                idx = int(np.clip((px + 128) / 256 * len(chars), 0, len(chars) - 1))
                line += chars[idx] * 2
            print(f"  {line}")
    else:
        # RGB: average channels for grayscale ASCII
        img = pixels.astype(np.int16).reshape(channels, h, w)
        gray = img.mean(axis=0)
        for row in gray:
            line = ""
            for px in row:
                idx = int(np.clip((px + 128) / 256 * len(chars), 0, len(chars) - 1))
                line += chars[idx] * 2
            print(f"  {line}")

    if label is not None and predicted is not None:
        lname = class_names[label] if class_names else str(label)
        pname = class_names[predicted] if class_names else str(predicted)
        status = "CORRECT" if label == predicted else "WRONG"
        print(f"  Label: {lname} ({label}), Predicted: {pname} ({predicted}) [{status}]")


# ═══════════════════════════════════════════════════════════════
#  Test / Demo commands
# ═══════════════════════════════════════════════════════════════

def choose_model():
    """Interactively choose a model."""
    available = []
    for key, spec in MODEL_REGISTRY.items():
        exists = os.path.exists(spec["file"])
        available.append((key, spec, exists))

    print("\nAvailable models:")
    valid = []
    for i, (key, spec, exists) in enumerate(available):
        status = "trained" if exists else "not trained"
        print(f"  {i+1}. {key:10s} — {spec['name']:20s} [{status}]")
        if exists:
            valid.append(key)

    if not valid:
        print("\nNo trained models found. Run 'python fpga_classify.py train <model>' first.")
        sys.exit(1)

    while True:
        choice = input(f"\nSelect model ({', '.join(valid)}): ").strip().lower()
        if choice in valid:
            return choice
        # Try by number
        try:
            idx = int(choice) - 1
            key = available[idx][0]
            if key in valid:
                return key
        except (ValueError, IndexError):
            pass
        print(f"  Invalid choice. Options: {', '.join(valid)}")


def get_dataset(model_key):
    """Load test dataset for a model."""
    from torchvision import datasets, transforms

    spec = MODEL_REGISTRY[model_key]
    mean, std = spec["normalize"]

    if spec["channels"] == 1:
        transform = transforms.Compose([
            transforms.ToTensor(),
            transforms.Normalize(mean, std),
        ])
    else:
        transform = transforms.Compose([
            transforms.ToTensor(),
            transforms.Normalize(mean, std),
        ])

    ds_class = getattr(datasets, spec["dataset"])
    return ds_class('./data', train=False, download=True, transform=transform)


def quantize_input(img_tensor, model):
    """Convert a single image tensor to int8."""
    spec = model["spec"]
    if spec["channels"] == 3:
        flat = img_tensor.view(-1).numpy()
    else:
        flat = img_tensor.view(spec["input_dim"]).numpy()
    return np.clip(np.round(flat * model["input_scale"]), -128, 127).astype(np.int8)


def test_fpga(port, model_key):
    import serial as ser_mod

    model = load_model(model_key)
    spec = model["spec"]
    test_ds = get_dataset(model_key)

    ser = ser_mod.Serial(port, BAUD, timeout=30)
    time.sleep(0.1)
    ser.reset_input_buffer()
    ser.reset_output_buffer()

    try:
        print(f"\n{'='*60}")
        print(f"FPGA Classification: {spec['name']}")
        print(f"{'='*60}")

        print("\nUploading model...")
        t = time.time()
        configs = upload_model(ser, model)
        print(f"  Done in {time.time()-t:.1f}s")

        num_batches = 50
        correct = 0; total = 0; fpga_time = 0

        print(f"\nClassifying {num_batches * N} images in batches of {N}...\n")

        for batch_idx in range(num_batches):
            images_int8 = np.zeros((N, spec["input_dim"]), dtype=np.int8)
            labels = np.zeros(N, dtype=np.int64)
            for i in range(N):
                idx = batch_idx * N + i
                img, label = test_ds[idx]
                images_int8[i] = quantize_input(img, model)
                labels[i] = label

            t = time.time()
            predictions, _ = classify_batch(ser, images_int8, model, configs)
            fpga_time += time.time() - t

            for i in range(N):
                if predictions[i] == labels[i]:
                    correct += 1
                total += 1

            if (batch_idx + 1) % 10 == 0:
                print(f"  Batch {batch_idx+1}/{num_batches}: "
                      f"{correct}/{total} correct ({100*correct/total:.1f}%)")

        print(f"\n{'='*60}")
        print(f"RESULTS — {spec['name']}")
        print(f"  Accuracy: {correct}/{total} ({100*correct/total:.1f}%)")
        print(f"  FPGA time: {fpga_time:.2f}s for {total} images")
        print(f"  Throughput: {total/fpga_time:.1f} images/sec")
        print(f"{'='*60}")

        # Show samples
        print("\nSample classifications:")
        images_int8 = np.zeros((N, spec["input_dim"]), dtype=np.int8)
        labels = np.zeros(N, dtype=np.int64)
        for i in range(N):
            img, label = test_ds[i]
            images_int8[i] = quantize_input(img, model)
            labels[i] = label

        predictions, raw = classify_batch(ser, images_int8, model, configs)
        for i in range(N):
            print(f"\n--- Sample {i} ---")
            print_image(images_int8[i], spec["img_shape"], spec["channels"],
                        int(labels[i]), int(predictions[i]), spec["class_names"])
            logits = raw[:spec["num_classes"], i]
            print(f"  Logits: {logits}")

    finally:
        ser.close()


def demo(port, model_key):
    import serial as ser_mod

    model = load_model(model_key)
    spec = model["spec"]
    test_ds = get_dataset(model_key)

    ser = ser_mod.Serial(port, BAUD, timeout=30)
    time.sleep(0.1)
    ser.reset_input_buffer()
    ser.reset_output_buffer()

    try:
        print(f"\n{'='*60}")
        print(f"FPGA Demo: {spec['name']} — Interactive Classifier")
        print(f"{'='*60}")

        print("\nUploading model...")
        configs = upload_model(ser, model)
        print("  Ready!\n")

        rng = np.random.RandomState()

        while True:
            indices = rng.randint(0, len(test_ds), size=N)
            images_int8 = np.zeros((N, spec["input_dim"]), dtype=np.int8)
            labels = np.zeros(N, dtype=np.int64)
            for i, idx in enumerate(indices):
                img, label = test_ds[idx]
                images_int8[i] = quantize_input(img, model)
                labels[i] = label

            t = time.time()
            predictions, raw = classify_batch(ser, images_int8, model, configs)
            elapsed = time.time() - t

            for i in range(N):
                print(f"\n--- Image #{indices[i]} ---")
                print_image(images_int8[i], spec["img_shape"], spec["channels"],
                            int(labels[i]), int(predictions[i]), spec["class_names"])
                logits = raw[:spec["num_classes"], i]
                sorted_idx = np.argsort(-logits)
                print(f"  Top 3: ", end="")
                for rank in range(3):
                    ci = sorted_idx[rank]
                    cname = spec["class_names"][ci] if ci < len(spec["class_names"]) else f"?{ci}"
                    print(f"{cname}({logits[ci]})", end="  ")
                print()

            print(f"\n  Inference: {elapsed*1000:.0f}ms for {N} images")
            resp = input("\nEnter for next batch, 'q' to quit: ").strip()
            if resp.lower() == 'q':
                break

    finally:
        ser.close()


# ═══════════════════════════════════════════════════════════════
#  Main
# ═══════════════════════════════════════════════════════════════

def main():
    if len(sys.argv) < 2 or sys.argv[1] in ["-h", "--help", "help"]:
        print(__doc__)
        print("Trained models:")
        for key, spec in MODEL_REGISTRY.items():
            exists = "ready" if os.path.exists(spec["file"]) else "not trained"
            print(f"  {key:10s} {spec['name']:20s} [{exists}]")
        sys.exit(0)

    cmd = sys.argv[1]

    if cmd == "train":
        if len(sys.argv) < 3:
            print("Usage: python fpga_classify.py train <model>")
            print(f"Models: {', '.join(MODEL_REGISTRY.keys())}")
            sys.exit(1)
        model_key = sys.argv[2]
        if model_key not in MODEL_REGISTRY:
            print(f"Unknown model '{model_key}'. Options: {', '.join(MODEL_REGISTRY.keys())}")
            sys.exit(1)
        train(model_key)

    elif cmd in ("test", "demo"):
        # Parse: cmd [port] [model]
        port = "/dev/ttyUSB1"
        model_key = None

        for arg in sys.argv[2:]:
            if arg.startswith("/dev/") or arg.startswith("COM") or arg.startswith("com"):
                port = arg
            elif arg in MODEL_REGISTRY:
                model_key = arg

        if model_key is None:
            model_key = choose_model()

        if cmd == "test":
            test_fpga(port, model_key)
        else:
            demo(port, model_key)

    else:
        print(f"Unknown command '{cmd}'. Use: train, test, demo")
        sys.exit(1)


if __name__ == "__main__":
    main()