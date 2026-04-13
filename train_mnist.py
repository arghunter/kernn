import torch
import torch.nn as nn
import torch.optim as optim
from torchvision import datasets, transforms
import numpy as np
import json
import math
import struct

N_TILE = 4  # systolic array dimension


class MNISTNet(nn.Module):
    def __init__(self):
        super().__init__()
        self.fc1 = nn.Linear(784, 64)
        self.fc2 = nn.Linear(64, 12)  # padded from 10 to 12

    def forward(self, x):
        x = x.view(-1, 784)
        x = torch.relu(self.fc1(x))
        x = self.fc2(x)
        return x[:, :10]  # only first 10 outputs for loss


def train():
    transform = transforms.Compose([
        transforms.ToTensor(),
        transforms.Normalize((0.1307,), (0.3081,))
    ])

    train_dataset = datasets.MNIST('./data', train=True, download=True, transform=transform)
    test_dataset = datasets.MNIST('./data', train=False, transform=transform)

    train_loader = torch.utils.data.DataLoader(train_dataset, batch_size=64, shuffle=True)
    test_loader = torch.utils.data.DataLoader(test_dataset, batch_size=1000)

    model = MNISTNet()
    optimizer = optim.Adam(model.parameters(), lr=0.001)
    criterion = nn.CrossEntropyLoss()

    for epoch in range(5):
        model.train()
        total_loss = 0
        for batch_idx, (data, target) in enumerate(train_loader):
            optimizer.zero_grad()
            output = model(data)
            loss = criterion(output, target)
            loss.backward()
            optimizer.step()
            total_loss += loss.item()

        # Test accuracy
        model.eval()
        correct = 0
        total = 0
        with torch.no_grad():
            for data, target in test_loader:
                output = model(data)
                pred = output.argmax(dim=1)
                correct += (pred == target).sum().item()
                total += target.size(0)

        print(f"Epoch {epoch+1}: loss={total_loss/len(train_loader):.4f}, "
              f"accuracy={100*correct/total:.1f}%")

    torch.save(model.state_dict(), 'mnist_model.pth')
    print("Model saved to mnist_model.pth")
    return model

def quantize_model(model):
    """
    Post-training quantization to int8 weights, int32 bias.
    Returns quantized weights, biases, shifts, and input scale.
    """
    layers = []

    # Input scale: MNIST normalized to ~[-2.8, 2.8], map to int8 [-128, 127]
    input_scale = 2.8 / 127.0  

    current_scale = input_scale

    for name, param in [('fc1', model.fc1), ('fc2', model.fc2)]:
        weight = param.weight.data  # [out_features, in_features]
        bias = param.bias.data      # [out_features]

        # Quantize weights
        w_absmax = weight.abs().max().item()
        if w_absmax == 0:
            w_absmax = 1.0
        w_scale = w_absmax / 127.0
        w_int8 = torch.clamp(torch.round(weight / w_scale), -128, 127).to(torch.int8)

        # Accumulator scale = current_scale * w_scale
        acc_scale = current_scale * w_scale

        # Bias in accumulator scale
        b_int32 = torch.clamp(torch.round(bias / acc_scale), -2**31, 2**31 - 1).to(torch.int32)

        if name == 'fc2':
            # Last layer: no clamp, identity activation
            shift = 0
            output_scale = acc_scale
        else:
            # Hidden layer: relu + clamp to int8
            K = weight.shape[1]
            expected_acc_max = w_absmax * 128.0 * K * 0.3  # rough estimate
            if expected_acc_max > 0:
                shift = max(0, int(round(math.log2(expected_acc_max / 127.0))))
            else:
                shift = 0
            
            # The scale of the shifted output is exactly acc_scale * 2^shift.
            output_scale = acc_scale * (2 ** shift)
            
            # FIX: The current_scale for the next layer is just the output_scale.
            current_scale = output_scale 

        # Pad to multiples of N_TILE
        out_features = weight.shape[0]
        in_features = weight.shape[1]
        M_padded = ((out_features + N_TILE - 1) // N_TILE) * N_TILE

        w_padded = np.zeros((M_padded, in_features), dtype=np.int8)
        w_padded[:out_features, :] = w_int8.numpy()

        b_padded = np.zeros(M_padded, dtype=np.int32)
        b_padded[:out_features] = b_int32.numpy()

        layers.append({
            'name': name,
            'weight': w_padded,
            'bias': b_padded,
            'M': M_padded,
            'K': in_features,
            'shift': shift,
            'is_last': name == 'fc2',
        })

        print(f"  {name}: weight shape={w_padded.shape}, "
              f"w_scale={w_scale:.6f}, shift={shift}, "
              f"acc_scale={acc_scale:.6f}")

    return layers, input_scale

def quantize_input(image_tensor, input_scale):
    """Convert normalized MNIST image to int8"""
    flat = image_tensor.view(-1).numpy()
    quantized = np.clip(np.round(flat / input_scale), -128, 127).astype(np.int8)
    return quantized


def save_quantized_model(layers, input_scale, filename='mnist_quantized.npz'):
    """Save quantized model to file"""
    save_dict = {
        'input_scale': input_scale,
        'num_layers': len(layers),
    }
    for i, layer in enumerate(layers):
        save_dict[f'weight_{i}'] = layer['weight']
        save_dict[f'bias_{i}'] = layer['bias']
        save_dict[f'M_{i}'] = layer['M']
        save_dict[f'K_{i}'] = layer['K']
        save_dict[f'shift_{i}'] = layer['shift']
        save_dict[f'is_last_{i}'] = layer['is_last']

    np.savez(filename, **save_dict)
    print(f"Quantized model saved to {filename}")


def test_quantized_accuracy(model, layers, input_scale):
    """Test accuracy using software int8 inference"""
    transform = transforms.Compose([
        transforms.ToTensor(),
        transforms.Normalize((0.1307,), (0.3081,))
    ])
    test_dataset = datasets.MNIST('./data', train=False, transform=transform)
    test_loader = torch.utils.data.DataLoader(test_dataset, batch_size=1)

    correct = 0
    total = 0

    for data, target in test_loader:
        # Quantize input
        x = quantize_input(data, input_scale).astype(np.int32)

        # Layer 1: matmul + bias + relu + shift + clamp
        w1 = layers[0]['weight'].astype(np.int32)
        b1 = layers[0]['bias'].astype(np.int32)
        acc1 = w1 @ x  # [64, 1] = [64, 784] @ [784, 1]
        acc1 = acc1.flatten() + b1
        acc1 = acc1 >> layers[0]['shift']
        acc1 = np.maximum(acc1, 0)  # relu
        acc1 = np.clip(acc1, -128, 127)  # clamp to int8

        # Layer 2: matmul + bias (identity, no clamp)
        w2 = layers[1]['weight'].astype(np.int32)
        b2 = layers[1]['bias'].astype(np.int32)
        acc2 = w2 @ acc1  # [12, 1] = [12, 64] @ [64, 1]
        acc2 = acc2.flatten() + b2

        pred = acc2[:10].argmax()
        if pred == target.item():
            correct += 1
        total += 1

        if total >= 1000:  # test subset
            break

    print(f"Quantized accuracy: {100*correct/total:.1f}% ({correct}/{total})")


if __name__ == '__main__':
    print("Training MNIST model...")
    model = train()

    print("\nQuantizing model...")
    layers, input_scale = quantize_model(model)

    print("\nTesting quantized accuracy...")
    test_quantized_accuracy(model, layers, input_scale)

    save_quantized_model(layers, input_scale)