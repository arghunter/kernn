import serial
import struct
import numpy as np
import time
from torchvision import datasets, transforms

N_TILE = 4


class FPGAAccelerator:
    def __init__(self, port='/dev/ttyUSB1', baud=115200):
        self.ser = serial.Serial(port, baud, timeout=10)
        self.n = N_TILE
        time.sleep(0.1)  # let FPGA settle

    def _send(self, data):
        """Send data in chunks to prevent FPGA UART buffer overflow"""
        if not isinstance(data, list):
            data = [data]
            
        b_data = bytes(data)
        chunk_size = 64
        
        for i in range(0, len(b_data), chunk_size):
            self.ser.write(b_data[i:i+chunk_size])
            self.ser.flush() # Force OS to push bytes
            time.sleep(0.005) # 5ms delay per chunk (~12kB/s throttle)

    def _u16(self, val):
        # FIXED: Changed to Little-Endian to match your 32-bit unpack logic
        return [val & 0xFF, (val >> 8) & 0xFF]

    def _s8(self, val):
        return int(val) & 0xFF

    def _s32_le(self, val):
        return list(struct.pack('<i', int(val)))

    def load_config(self, layers_config):
        payload = [0x01, len(layers_config)]
        for layer in layers_config:
            payload.extend(self._u16(layer['weight_base']))
            payload.extend(self._u16(layer['bias_base']))
            payload.extend(self._u16(layer['M']))
            payload.extend(self._u16(layer['K']))
            payload.extend(self._u16(layer['N']))
            payload.append(layer['activation'] & 0x03)
            payload.append(layer['shift'] & 0x1F)
            payload.extend(self._u16(layer['relu6_thresh']))
            payload.append(1 if layer['clamp_en'] else 0)
        self._send(payload)

    def load_weights(self, W, M, K, base_addr):
        n = self.n
        tilesM = M // n
        tilesK = K // n
        count = tilesM * tilesK * n
        
        payload = [0x02] + self._u16(base_addr) + self._u16(count)
        
        # Batch append to avoid a million function calls
        for tR in range(tilesM):
            for tK in range(tilesK):
                for t in range(n):
                    for r in range(n):
                        payload.append(self._s8(W[tR * n + r][tK * n + t]))
        self._send(payload)

    def load_bias(self, bias, N_out, base_addr):
        n = self.n
        count = N_out // n
        
        payload = [0x03] + self._u16(base_addr) + self._u16(count)
        
        for tC in range(count):
            for c in range(n):
                payload.extend(self._s32_le(bias[tC * n + c]))
        self._send(payload)

    def load_activations(self, A, K, N, base_addr):
        n = self.n
        tilesK = K // n
        tilesN = N // n
        count = tilesK * tilesN * n
        
        payload = [0x04] + self._u16(base_addr) + self._u16(count)
        
        for tK in range(tilesK):
            for tC in range(tilesN):
                for t in range(n):
                    for c in range(n):
                        payload.append(self._s8(A[tK * n + t][tC * n + c]))
        self._send(payload)

    def run(self, input_base=0, buffer_b_base=2048):
        # Give the FPGA a moment to process the last memory write
        time.sleep(0.05) 
        
        # --- NEW: Check for and clear leftover garbage in the buffer ---
        waiting = self.ser.in_waiting
        if waiting > 0:
            garbage = self.ser.read(waiting)
            print(f"⚠️ Warning: Found {waiting} unexpected bytes in RX buffer before running: {[hex(b) for b in garbage]}")
        self.ser.reset_input_buffer() # Nuke the buffer just to be safe
        # ---------------------------------------------------------------

        self._send([0x05])
        self._send(self._u16(input_base))
        self._send(self._u16(buffer_b_base))
        
        # Wait for 0xAA done acknowledgement
        resp = self.ser.read(1)
        if len(resp) == 0:
            raise TimeoutError("No response from FPGA. Parser likely stuck in WAITING_FOR_DATA state.")
        
        if resp[0] != 0xAA:
            # Let's read a few more bytes to see if the 0xAA is hiding right behind it
            time.sleep(0.01)
            extra_bytes = self.ser.read(self.ser.in_waiting)
            raise ValueError(f"Unexpected response: {resp[0]:#x}. Extra bytes in buffer: {[hex(b) for b in extra_bytes]}")

    def read_output(self, M, N, base_addr=0):
        n = self.n
        tilesM = M // n
        tilesN = N // n
        count = tilesM * tilesN * n
        
        self._send([0x06] + self._u16(base_addr) + self._u16(count))

        result = np.zeros((M, N), dtype=np.int32)
        for tR in range(tilesM):
            for tC in range(tilesN):
                for r in range(n):
                    raw = self.ser.read(n * 4)
                    if len(raw) < n * 4:
                        raise TimeoutError(f"Short read: got {len(raw)} bytes")
                    for c in range(n):
                        val = struct.unpack('<i', raw[c*4:(c+1)*4])[0]
                        result[tR * n + r][tC * n + c] = val

        # Read done marker 0xFF
        marker = self.ser.read(1)
        return result

    def close(self):
        self.ser.close()


def load_quantized_model(filename='mnist_quantized.npz'):
    data = np.load(filename, allow_pickle=True)
    input_scale = float(data['input_scale'])
    num_layers = int(data['num_layers'])

    layers = []
    for i in range(num_layers):
        layers.append({
            'weight': data[f'weight_{i}'],
            'bias': data[f'bias_{i}'],
            'M': int(data[f'M_{i}']),
            'K': int(data[f'K_{i}']),
            'shift': int(data[f'shift_{i}']),
            'is_last': bool(data[f'is_last_{i}']),
        })

    return layers, input_scale


def build_layer_configs(layers, n=N_TILE):
    """Build hardware layer configs with memory layout addresses"""
    configs = []
    weight_offset = 0
    bias_offset = 0
    N = 4  # batch/padded width

    for i, layer in enumerate(layers):
        M = layer['M']
        K = layer['K']

        config = {
            'weight_base': weight_offset,
            'bias_base': bias_offset,
            'M': M,
            'K': K,
            'N': N,
            'activation': 0 if layer['is_last'] else 1,  # relu for hidden, identity for output
            'shift': layer['shift'],
            'relu6_thresh': 6,
            'clamp_en': 0 if layer['is_last'] else 1,
        }
        configs.append(config)

        tilesM = M // n
        tilesK = K // n
        weight_offset += tilesM * tilesK * n
        bias_offset += M // n

    return configs


def quantize_input(image_tensor, input_scale):
    flat = image_tensor.view(-1).numpy()
    quantized = np.clip(np.round(flat / input_scale), -128, 127).astype(np.int8)
    return quantized


def run_mnist_inference(port='/dev/ttyUSB0', baud=115200, num_images=10):
    # Load quantized model
    layers, input_scale = load_quantized_model()
    configs = build_layer_configs(layers)
    n = N_TILE
    N = 4  # padded activation width

    print(f"Model: {len(layers)} layers")
    for i, (layer, config) in enumerate(zip(layers, configs)):
        print(f"  Layer {i}: {config['M']}x{config['K']} → {config['M']}x{N}, "
              f"shift={config['shift']}, act={'relu' if config['activation']==1 else 'identity'}, "
              f"clamp={config['clamp_en']}")
        print(f"    weight_base={config['weight_base']}, bias_base={config['bias_base']}")

    # Connect to FPGA
    print(f"\nConnecting to {port} at {baud} baud...")
    acc = FPGAAccelerator(port, baud)

    try:
        # Load config
        print("Loading layer configs...")
        acc.load_config(configs)

        # Load weights
        for i, (layer, config) in enumerate(zip(layers, configs)):
            print(f"Loading weights for layer {i} ({config['M']}x{config['K']})...")
            acc.load_weights(layer['weight'], config['M'], config['K'], config['weight_base'])

        # Load biases
        for i, (layer, config) in enumerate(zip(layers, configs)):
            print(f"Loading bias for layer {i}...")
            acc.load_bias(layer['bias'], config['M'], config['bias_base'])

        # Load test images and run inference
        transform = transforms.Compose([
            transforms.ToTensor(),
            transforms.Normalize((0.1307,), (0.3081,))
        ])
        test_dataset = datasets.MNIST('./data', train=False, transform=transform)

        correct = 0
        total = 0

        for idx in range(num_images):
            image, label = test_dataset[idx]

            # Quantize input image
            input_q = quantize_input(image, input_scale)

            # Pad to K×N matrix (784×4, single image in column 0)
            input_matrix = np.zeros((784, N), dtype=np.int8)
            input_matrix[:, 0] = input_q

            # Load input activations
            acc.load_activations(input_matrix, 784, N, base_addr=0)

            # Run inference
            t0 = time.time()
            acc.run(input_base=0, buffer_b_base=2048)
            elapsed = time.time() - t0

            # Read output (last layer: M=12, N=4)
            last_config = configs[-1]
            result = acc.read_output(last_config['M'], N)

            # Prediction: first column (our image), first 10 rows (10 classes)
            scores = result[:10, 0]
            prediction = scores.argmax()

            if prediction == label:
                correct += 1
            total += 1

            print(f"  Image {idx}: label={label}, pred={prediction}, "
                  f"{'✓' if prediction == label else '✗'}, "
                  f"scores={scores.tolist()}, "
                  f"time={elapsed*1000:.1f}ms")

        print(f"\nAccuracy: {100*correct/total:.1f}% ({correct}/{total})")

    finally:
        acc.close()


def software_inference(num_images=100):
    """Run software int8 inference for comparison"""
    layers, input_scale = load_quantized_model()

    transform = transforms.Compose([
        transforms.ToTensor(),
        transforms.Normalize((0.1307,), (0.3081,))
    ])
    test_dataset = datasets.MNIST('./data', train=False, transform=transform)

    correct = 0
    total = 0

    for idx in range(num_images):
        image, label = test_dataset[idx]
        x = quantize_input(image, input_scale).astype(np.int32)

        # Layer 1
        w1 = layers[0]['weight'].astype(np.int32)
        b1 = layers[0]['bias'].astype(np.int32)
        acc1 = (w1 @ x) + b1
        acc1 = acc1 >> layers[0]['shift']
        acc1 = np.maximum(acc1, 0)  # relu
        acc1 = np.clip(acc1, -128, 127)  # clamp

        # Layer 2
        w2 = layers[1]['weight'].astype(np.int32)
        b2 = layers[1]['bias'].astype(np.int32)
        acc2 = (w2 @ acc1) + b2

        pred = acc2[:10].argmax()
        if pred == label:
            correct += 1
        total += 1

    print(f"Software int8 accuracy: {100*correct/total:.1f}% ({correct}/{total})")


if __name__ == '__main__':
    import sys

    if len(sys.argv) > 1 and sys.argv[1] == 'software':
        software_inference(1000)
    else:
        # Note: Added sys.argv[1] default to ttyUSB1 based on your previous message!
        port = sys.argv[1] if len(sys.argv) > 1 else '/dev/ttyUSB1'
        baud = int(sys.argv[2]) if len(sys.argv) > 2 else 115200
        num = int(sys.argv[3]) if len(sys.argv) > 3 else 10
        run_mnist_inference(port, baud, num)



# import serial
# import time
# import sys

# def ping_parser(port='/dev/ttyUSB1', baud=115200):
#     try:
#         print(f"🔌 Opening {port} at {baud}...")
#         ser = serial.Serial(port, baud, timeout=2)
#         time.sleep(0.1) # Let the connection settle

#         # We are going to send the 0x06 (Read) command.
#         # Format: [0x06, base_addr_lo, base_addr_hi, count_lo, count_hi]
#         # Let's ask for 16 elements (count = 16 = 0x0010)
#         print("📡 Sending command 0x06 (Read 16 words from address 0)...")
#         cmd = bytes([0x06, 0x00, 0x00, 0x10, 0x00])
        
#         ser.write(cmd)
#         ser.flush()

#         # 16 elements * 4 bytes/element = 64 bytes.
#         # Plus the 0xFF end marker = 65 bytes total.
#         print("⏳ Waiting for 65 bytes of response...")
        
#         # We set a 2-second timeout above. If the FPGA is dead, this will return empty after 2s.
#         resp = ser.read(65)

#         if len(resp) == 0:
#             print("\n❌ TOTAL SILENCE.")
#             print("The FPGA didn't send a single bit back.")
#             print("Causes: The UART RX/TX lines are mapped to the wrong pins, the baud rate clock divider in Verilog is wrong, or the parser state machine is trapped in an idle/reset state.")
#         elif len(resp) < 65:
#             print(f"\n⚠️ PARTIAL RESPONSE. Received {len(resp)} bytes.")
#             print(f"First byte received: {hex(resp[0]) if resp else 'None'}")
#             print("Causes: The TX buffer in the FPGA overflowed, or it crashed mid-transmission.")
#         else:
#             print("\n✅ SUCCESS! The parser is ALIVE and talking!")
#             print(f"Last byte received (should be 0xff): {hex(resp[-1])}")
#             print("If you see this, the hardware is fine, and the issue is definitely that your memory-loading loop in the main script is overflowing the RX buffer and locking up the state machine.")

#     except Exception as e:
#         print(f"\n💥 Python Error: {e}")
#     finally:
#         if 'ser' in locals() and ser.is_open:
#             ser.close()

# if __name__ == '__main__':
#     # You can pass the port as a command line arg: python ping_fpga.py /dev/ttyUSB1
#     port = sys.argv[1] if len(sys.argv) > 1 else '/dev/ttyUSB1'
#     ping_parser(port)