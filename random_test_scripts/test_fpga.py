# import serial
# import struct
# import numpy as np
# import time

# # === Configuration ===
# PORT = "/dev/ttyUSB1"  # Change to your port (COM3 on Windows, etc.)
# BAUD = 115200
# N = 4

# ser = serial.Serial(PORT, BAUD, timeout=5)
# time.sleep(0.1)
# ser.reset_input_buffer()
# ser.reset_output_buffer()

# # === Protocol helpers ===

# def u16(v):
#     return bytes([(v >> 8) & 0xFF, v & 0xFF])

# def s8(v):
#     return bytes([v & 0xFF])

# def s32le(v):
#     return struct.pack('<i', v)

# def send_config(layers):
#     ser.write(bytes([0x01, len(layers)]))
#     for L in layers:
#         ser.write(u16(L["weight_base"]))
#         ser.write(u16(L["bias_base"]))
#         ser.write(u16(L["M"]))
#         ser.write(u16(L["K"]))
#         ser.write(u16(L["N"]))
#         ser.write(bytes([L["activation"] & 0x03]))
#         ser.write(bytes([L["shift"] & 0x1F]))
#         ser.write(u16(L["relu6_thresh"]))
#         ser.write(bytes([1 if L["clamp_en"] else 0]))

# def send_weights(W, m, k, base_addr):
#     tiles_k = k // N
#     count = (m // N) * tiles_k * N
#     ser.write(bytes([0x02]))
#     ser.write(u16(base_addr))
#     ser.write(u16(count))
#     for tR in range(m // N):
#         for tK in range(tiles_k):
#             for t in range(N):
#                 for r in range(N):
#                     ser.write(s8(W[tR * N + r][tK * N + t]))

# def send_bias(bias, nn, base_addr):
#     count = nn // N
#     ser.write(bytes([0x03]))
#     ser.write(u16(base_addr))
#     ser.write(u16(count))
#     for tC in range(count):
#         for c in range(N):
#             ser.write(s32le(bias[tC * N + c]))

# def send_activations(A, k, nn, base_addr):
#     tiles_n = nn // N
#     count = (k // N) * tiles_n * N
#     ser.write(bytes([0x04]))
#     ser.write(u16(base_addr))
#     ser.write(u16(count))
#     for tK in range(k // N):
#         for tC in range(tiles_n):
#             for t in range(N):
#                 for c in range(N):
#                     ser.write(s8(A[tK * N + t][tC * N + c]))

# def send_run(input_base, buf_b_base):
#     ser.write(bytes([0x05]))
#     ser.write(u16(input_base))
#     ser.write(u16(buf_b_base))

# def read_output(m, nn, base_addr=0):
#     tiles_n = nn // N
#     count = (m // N) * tiles_n * N
#     ser.write(bytes([0x06]))
#     ser.write(u16(base_addr))
#     ser.write(u16(count))

#     result = np.zeros((m, nn), dtype=np.int64)
#     for tR in range(m // N):
#         for tC in range(tiles_n):
#             for r in range(N):
#                 raw_bytes = ser.read(N * 4)
#                 if len(raw_bytes) != N * 4:
#                     raise RuntimeError(f"Timeout reading output data, got {len(raw_bytes)} bytes")
#                 for c in range(N):
#                     val = struct.unpack_from('<i', raw_bytes, c * 4)[0]
#                     result[tR * N + r][tC * N + c] = val

#     marker = ser.read(1)
#     if len(marker) != 1 or marker[0] != 0xFF:
#         raise RuntimeError(f"Expected 0xFF marker, got {marker.hex() if marker else 'nothing'}")
#     return result

# # === Test: 4x4 Identity Matmul ===

# def test_identity_4x4():
#     m, k, nn = 4, 4, 4

#     W = [[1 if r == c else 0 for c in range(k)] for r in range(m)]
#     A = [[r * nn + c + 1 for c in range(nn)] for r in range(k)]
#     bias = [0] * nn

#     # Expected: identity × A = A
#     expected = np.array(A, dtype=np.int64)

#     print("[PC] Sending config...")
#     send_config([{
#         "weight_base": 0, "bias_base": 0,
#         "M": m, "K": k, "N": nn,
#         "activation": 0, "shift": 0,
#         "relu6_thresh": 6, "clamp_en": 0
#     }])

#     print("[PC] Sending weights...")
#     send_weights(W, m, k, 0)

#     print("[PC] Sending bias...")
#     send_bias(bias, nn, 0)

#     print("[PC] Sending activations...")
#     send_activations(A, k, nn, 0)

#     print("[PC] Sending RUN...")
#     send_run(0, 2048)

#     print("[PC] Waiting for ACK...")
#     ack = ser.read(1)
#     if len(ack) != 1:
#         raise RuntimeError("Timeout waiting for ACK")
#     if ack[0] != 0xAA:
#         raise RuntimeError(f"Expected 0xAA ACK, got 0x{ack[0]:02X}")
#     print("[PC] Got ACK (0xAA)")

#     print("[PC] Reading output...")
#     result = read_output(m, nn)

#     print("\nResult:")
#     print(result)
#     print("\nExpected:")
#     print(expected)

#     if np.array_equal(result, expected):
#         print("\n=== PASS ===")
#     else:
#         print("\n=== FAIL ===")
#         diff = np.where(result != expected)
#         for r, c in zip(diff[0], diff[1]):
#             print(f"  ({r},{c}): got {result[r][c]}, expected {expected[r][c]}")

# if __name__ == "__main__":
#     try:
#         test_identity_4x4()
#     finally:
#         ser.close()


# import serial
# import struct
# import numpy as np
# import time
# import sys

# # === Configuration ===
# PORT = "/dev/ttyUSB1"  # Change to your port
# BAUD = 115200
# N = 4

# # === Protocol helpers ===

# def u16(v):
#     return bytes([(v >> 8) & 0xFF, v & 0xFF])

# def s8(v):
#     return bytes([v & 0xFF])

# def s32le(v):
#     return struct.pack('<i', v)

# def send_config(ser, layers):
#     ser.write(bytes([0x01, len(layers)]))
#     for L in layers:
#         ser.write(u16(L["weight_base"]))
#         ser.write(u16(L["bias_base"]))
#         ser.write(u16(L["M"]))
#         ser.write(u16(L["K"]))
#         ser.write(u16(L["N"]))
#         ser.write(bytes([L["activation"] & 0x03]))
#         ser.write(bytes([L["shift"] & 0x1F]))
#         ser.write(u16(L["relu6_thresh"]))
#         ser.write(bytes([1 if L["clamp_en"] else 0]))

# def send_weights(ser, W, m, k, base_addr):
#     tiles_k = k // N
#     count = (m // N) * tiles_k * N
#     ser.write(bytes([0x02]))
#     ser.write(u16(base_addr))
#     ser.write(u16(count))
#     for tR in range(m // N):
#         for tK in range(tiles_k):
#             for t in range(N):
#                 for r in range(N):
#                     ser.write(s8(W[tR * N + r][tK * N + t]))

# def send_bias(ser, bias, nn, base_addr):
#     count = nn // N
#     ser.write(bytes([0x03]))
#     ser.write(u16(base_addr))
#     ser.write(u16(count))
#     for tC in range(count):
#         for c in range(N):
#             ser.write(s32le(bias[tC * N + c]))

# def send_activations(ser, A, k, nn, base_addr):
#     tiles_n = nn // N
#     count = (k // N) * tiles_n * N
#     ser.write(bytes([0x04]))
#     ser.write(u16(base_addr))
#     ser.write(u16(count))
#     for tK in range(k // N):
#         for tC in range(tiles_n):
#             for t in range(N):
#                 for c in range(N):
#                     ser.write(s8(A[tK * N + t][tC * N + c]))

# def send_run(ser, input_base, buf_b_base):
#     ser.write(bytes([0x05]))
#     ser.write(u16(input_base))
#     ser.write(u16(buf_b_base))

# def read_output(ser, m, nn, base_addr=0):
#     tiles_n = nn // N
#     count = (m // N) * tiles_n * N
#     ser.write(bytes([0x06]))
#     ser.write(u16(base_addr))
#     ser.write(u16(count))

#     result = np.zeros((m, nn), dtype=np.int64)
#     for tR in range(m // N):
#         for tC in range(tiles_n):
#             for r in range(N):
#                 raw_bytes = ser.read(N * 4)
#                 if len(raw_bytes) != N * 4:
#                     raise RuntimeError(f"Timeout reading output, got {len(raw_bytes)} bytes")
#                 for c in range(N):
#                     val = struct.unpack_from('<i', raw_bytes, c * 4)[0]
#                     result[tR * N + r][tC * N + c] = val

#     marker = ser.read(1)
#     if len(marker) != 1 or marker[0] != 0xFF:
#         raise RuntimeError(f"Expected 0xFF marker, got {marker.hex() if marker else 'nothing'}")
#     return result

# # === Software reference ===

# def sw_matmul(W, A):
#     m, k = W.shape
#     _, nn = A.shape
#     result = np.zeros((m, nn), dtype=np.int64)
#     for r in range(m):
#         for c in range(nn):
#             result[r][c] = sum(int(W[r][i]) * int(A[i][c]) for i in range(k))
#     return result

# def sw_bias_act_clamp(raw, bias, func, shift, relu6_thresh, clamp_en):
#     m, nn = raw.shape
#     out = np.zeros_like(raw)
#     for r in range(m):
#         for c in range(nn):
#             val = (raw[r][c] + int(bias[c])) >> shift
#             if func == 1:
#                 val = max(0, val)
#             elif func == 2:
#                 val = val >> 3 if val < 0 else val
#             elif func == 3:
#                 val = min(max(0, val), relu6_thresh)
#             if clamp_en:
#                 val = max(-128, min(127, val))
#             out[r][c] = val
#     return out

# # === Test runner ===

# def run_test(ser, name, m, k, nn, W, A, bias, activation, shift, relu6_thresh, clamp_en):
#     W_np = np.array(W, dtype=np.int8)
#     A_np = np.array(A, dtype=np.int8)
#     bias_np = np.array(bias, dtype=np.int32)

#     raw = sw_matmul(W_np, A_np)
#     expected = sw_bias_act_clamp(raw, bias_np, activation, shift, relu6_thresh, clamp_en)

#     ser.reset_input_buffer()

#     print(f"\n  Dimensions: {m}x{k} * {k}x{nn}")
#     print(f"  Activation: {activation}, Shift: {shift}, ReLU6 thresh: {relu6_thresh}, Clamp: {clamp_en}")

#     print("  Sending config...")
#     send_config(ser, [{"weight_base": 0, "bias_base": 0, "M": m, "K": k, "N": nn,
#         "activation": activation, "shift": shift, "relu6_thresh": relu6_thresh, "clamp_en": clamp_en}])

#     print("  Sending weights...")
#     send_weights(ser, W, m, k, 0)

#     print("  Sending bias...")
#     send_bias(ser, bias, nn, 0)

#     print("  Sending activations...")
#     send_activations(ser, A, k, nn, 0)

#     print("  Sending RUN...")
#     send_run(ser, 0, 2048)

#     print("  Waiting for ACK...")
#     ack = ser.read(1)
#     if len(ack) != 1:
#         raise RuntimeError("Timeout waiting for ACK")
#     if ack[0] != 0xAA:
#         raise RuntimeError(f"Expected 0xAA ACK, got 0x{ack[0]:02X}")
#     print("  Got ACK (0xAA)")

#     print("  Reading output...")
#     result = read_output(ser, m, nn)

#     if np.array_equal(result, expected):
#         print(f"  === PASS ===")
#         return True
#     else:
#         print(f"  === FAIL ===")
#         print(f"  Result:\n{result}")
#         print(f"  Expected:\n{expected}")
#         diff = np.where(result != expected)
#         for r, c in zip(diff[0], diff[1]):
#             print(f"    ({r},{c}): got {result[r][c]}, expected {expected[r][c]}")
#         return False

# # === Test definitions ===

# def get_tests():
#     rng = np.random.RandomState(42)
#     tests = {}

#     tests["identity"] = ("Identity 4x4", 4, 4, 4,
#         [[1 if r == c else 0 for c in range(4)] for r in range(4)],
#         [[r * 4 + c + 1 for c in range(4)] for r in range(4)],
#         [0] * 4, 0, 0, 6, False)

#     W = rng.randint(-128, 128, size=(8, 8)).tolist()
#     A = rng.randint(-128, 128, size=(8, 8)).tolist()
#     bias = rng.randint(-500, 500, size=8).tolist()
#     tests["random8x8"] = ("Random 8x8 ReLU shift=8", 8, 8, 8, W, A, bias, 1, 8, 6, False)

#     W = rng.randint(-128, 128, size=(8, 4)).tolist()
#     A = rng.randint(-128, 128, size=(4, 8)).tolist()
#     bias = rng.randint(-250, 250, size=8).tolist()
#     tests["nonsquare"] = ("Non-square 8x4*4x8 linear", 8, 4, 8, W, A, bias, 0, 4, 6, False)

#     W = rng.randint(-128, 128, size=(4, 4)).tolist()
#     A = rng.randint(-128, 128, size=(4, 4)).tolist()
#     bias = rng.randint(-1000, 1000, size=4).tolist()
#     tests["leaky"] = ("4x4 Leaky ReLU + clamp", 4, 4, 4, W, A, bias, 2, 6, 6, True)

#     W = rng.randint(-64, 64, size=(12, 12)).tolist()
#     A = rng.randint(-64, 64, size=(12, 12)).tolist()
#     bias = rng.randint(-200, 200, size=12).tolist()
#     tests["relu6"] = ("12x12 ReLU6 shift=6", 12, 12, 12, W, A, bias, 3, 6, 50, False)

#     W = rng.randint(-32, 32, size=(4, 12)).tolist()
#     A = rng.randint(-32, 32, size=(12, 4)).tolist()
#     bias = rng.randint(-100, 100, size=4).tolist()
#     tests["deepk"] = ("Deep K 4x12*12x4 ReLU", 4, 12, 4, W, A, bias, 1, 4, 6, False)

#     tests["extreme_pos"] = ("All +127 no clamp", 4, 4, 4,
#         [[127]*4]*4, [[127]*4]*4, [0]*4, 0, 0, 6, False)

#     tests["extreme_neg"] = ("All -128 shift=8 clamp", 4, 4, 4,
#         [[-128]*4]*4, [[-128]*4]*4, [0]*4, 1, 8, 6, True)

#     tests["large_bias"] = ("Large bias values", 4, 4, 4,
#         [[1 if r == c else 0 for c in range(4)] for r in range(4)],
#         [[0]*4]*4, [10000, -10000, 32767, -32768], 0, 0, 6, False)

#     return tests

# def main():
#     tests = get_tests()

#     if len(sys.argv) < 2 or sys.argv[1] in ["-h", "--help", "help"]:
#         print("Usage: python test_fpga.py <test_name> [port]")
#         print(f"       python test_fpga.py all [port]")
#         print(f"\nAvailable tests:")
#         for key, val in tests.items():
#             print(f"  {key:15s} - {val[0]}")
#         print(f"  {'all':15s} - Run all tests")
#         sys.exit(0)

#     choice = sys.argv[1]
#     port = sys.argv[2] if len(sys.argv) > 2 else PORT

#     ser = serial.Serial(port, BAUD, timeout=5)
#     time.sleep(0.1)
#     ser.reset_input_buffer()
#     ser.reset_output_buffer()

#     try:
#         if choice == "all":
#             passed = 0
#             total = len(tests)
#             for key, args in tests.items():
#                 print(f"\n{'='*60}")
#                 print(f"TEST: {args[0]}")
#                 print(f"{'='*60}")
#                 if run_test(ser, *args):
#                     passed += 1
#             print(f"\n{'='*60}")
#             print(f"RESULTS: {passed}/{total} passed")
#             print(f"{'='*60}")
#         elif choice in tests:
#             args = tests[choice]
#             print(f"{'='*60}")
#             print(f"TEST: {args[0]}")
#             print(f"{'='*60}")
#             run_test(ser, *args)
#         else:
#             print(f"Unknown test '{choice}'. Use --help to see options.")
#             sys.exit(1)
#     finally:
#         ser.close()

# if __name__ == "__main__":
#     main()

import serial
import struct
import numpy as np
import time
import sys

# === Configuration ===
PORT = "/dev/ttyUSB1"
BAUD = 115200
N = 4

# === Protocol helpers ===

def u16(v):
    return bytes([(v >> 8) & 0xFF, v & 0xFF])

def s8(v):
    return bytes([v & 0xFF])

def s32le(v):
    return struct.pack('<i', v)

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
    total = 0
    for tR in range(m // N):
        for tK in range(tiles_k):
            for t in range(N):
                for r in range(N):
                    ser.write(s8(W[tR * N + r][tK * N + t]))
                    total += 1
    print(f"    Sent {total} weight bytes")

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
    total = 0
    for tK in range(k // N):
        for tC in range(tiles_n):
            for t in range(N):
                for c in range(N):
                    ser.write(s8(A[tK * N + t][tC * N + c]))
                    total += 1
    print(f"    Sent {total} activation bytes")

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

# === Software reference ===

def sw_matmul(W, A):
    m, k = W.shape
    _, nn = A.shape
    result = np.zeros((m, nn), dtype=np.int64)
    for r in range(m):
        for c in range(nn):
            result[r][c] = sum(int(W[r][i]) * int(A[i][c]) for i in range(k))
    return result

def sw_bias_act_clamp(raw, bias, func, shift, relu6_thresh, clamp_en):
    m, nn = raw.shape
    out = np.zeros_like(raw)
    for r in range(m):
        for c in range(nn):
            val = (raw[r][c] + int(bias[c])) >> shift
            if func == 1:
                val = max(0, val)
            elif func == 2:
                val = val >> 3 if val < 0 else val
            elif func == 3:
                val = min(max(0, val), relu6_thresh)
            if clamp_en:
                val = max(-128, min(127, val))
            out[r][c] = val
    return out

def sw_multi_layer(weights, biases, input_act, configs):
    act = input_act.copy()
    for i, cfg in enumerate(configs):
        raw = sw_matmul(np.array(weights[i], dtype=np.int8), act.astype(np.int8))
        out = sw_bias_act_clamp(raw, np.array(biases[i], dtype=np.int32),
                                cfg["activation"], cfg["shift"],
                                cfg["relu6_thresh"], cfg["clamp_en"])
        if i < len(configs) - 1:
            act = out.astype(np.int8)
        else:
            act = out
    return act

# === Memory layout calculator ===

def compute_layout(layer_dims):
    """Compute weight and bias base addresses for each layer.
    layer_dims: list of (M, K, N) tuples
    Returns: (weight_bases, bias_bases)
    """
    wt_offset = 0
    bias_offset = 0
    wt_bases = []
    bias_bases = []
    for m, k, _ in layer_dims:
        wt_bases.append(wt_offset)
        bias_bases.append(bias_offset)
        # Weight tiles: (M/N) * (K/N) * N addresses
        wt_offset += (m // N) * (k // N) * N
        # Bias tiles: one address per N outputs (but bias_base counts in tiles of N)
        # Actually bias count = nn // N, and each gets one address
    for _, _, nn in layer_dims:
        bias_bases_actual = []
    
    # Recalculate properly
    wt_offset = 0
    bias_offset = 0
    wt_bases = []
    bias_bases = []
    for m, k, nn in layer_dims:
        wt_bases.append(wt_offset)
        bias_bases.append(bias_offset)
        wt_offset += (m // N) * (k // N) * N
        bias_offset += nn // N
    
    return wt_bases, bias_bases

# === Main test ===

def test_feedforward(port):
    """
    Two-layer feed-forward network:
      Layer 0: 64x784 weights * 784x1 input -> 64x1, ReLU, clamp to int8
      Layer 1: 12x64  weights * 64x1       -> 12x1, linear (no activation)
    
    Input is shaped as 784xN where N must be multiple of 4.
    We use N=4 so the input is 784x4 (4 samples in parallel).
    """
    rng = np.random.RandomState(123)

    # Layer dimensions (M, K, N)
    # Layer 0: M=64 outputs, K=784 inputs, N=4 batch
    # Layer 1: M=12 outputs, K=64 inputs,  N=4 batch
    # N here is the column dimension of the activation matrix (batch size),
    # must be multiple of 4
    batch = 4
    dims = [(64, 784, batch), (12, 64, batch)]

    # Generate random quantized weights and biases
    W0 = rng.randint(-16, 16, size=(64, 784)).tolist()
    W1 = rng.randint(-16, 16, size=(12, 64)).tolist()
    bias0 = rng.randint(-500, 500, size=64).tolist()
    bias1 = rng.randint(-200, 200, size=12).tolist()

    # Fake quantized input (like a flattened 28x28 grayscale image, 4 samples)
    input_act = rng.randint(-128, 128, size=(784, batch)).tolist()

    # Compute memory layout
    wt_bases, bias_bases = compute_layout(dims)

    print(f"Memory layout:")
    print(f"  Layer 0 weights @ {wt_bases[0]}, bias @ {bias_bases[0]}")
    print(f"  Layer 1 weights @ {wt_bases[1]}, bias @ {bias_bases[1]}")
    print(f"  Weight mem used: {wt_bases[1] + (12 // N) * (64 // N) * N} addresses")

    configs = [
        {
            "weight_base": wt_bases[0], "bias_base": bias_bases[0],
            "M": 64, "K": 784, "N": batch,
            "activation": 1,     # ReLU
            "shift": 8,
            "relu6_thresh": 6,
            "clamp_en": 1        # clamp to int8 for next layer
        },
        {
            "weight_base": wt_bases[1], "bias_base": bias_bases[1],
            "M": 12, "K": 64, "N": batch,
            "activation": 0,     # linear (raw logits)
            "shift": 0,
            "relu6_thresh": 6,
            "clamp_en": 0
        }
    ]

    # Software reference
    print("\nComputing software reference...")
    t0 = time.time()
    expected = sw_multi_layer([W0, W1], [bias0, bias1],
                               np.array(input_act, dtype=np.int8), configs)
    sw_time = time.time() - t0
    print(f"  Software reference computed in {sw_time:.3f}s")
    print(f"  Output shape: {expected.shape}")
    print(f"  Expected output:\n{expected}")

    # Run on FPGA
    ser = serial.Serial(port, BAUD, timeout=30)  # longer timeout for large transfers
    time.sleep(0.1)
    ser.reset_input_buffer()
    ser.reset_output_buffer()

    try:
        print(f"\n{'='*60}")
        print(f"FPGA TEST: 784 -> 64 -> 12 Feed-Forward Network")
        print(f"  Batch size: {batch}")
        print(f"  Layer 0: {64}x{784} * {784}x{batch}, ReLU, shift=8, clamp")
        print(f"  Layer 1: {12}x{64} * {64}x{batch}, linear")
        print(f"{'='*60}")

        t_start = time.time()

        print("\n[1/6] Sending config (2 layers)...")
        send_config(ser, configs)

        print("[2/6] Sending layer 0 weights (64x784)...")
        t = time.time()
        send_weights(ser, W0, 64, 784, wt_bases[0])
        print(f"    Took {time.time()-t:.1f}s")

        print("[3/6] Sending layer 1 weights (12x64)...")
        t = time.time()
        send_weights(ser, W1, 12, 64, wt_bases[1])
        print(f"    Took {time.time()-t:.1f}s")

        print("[4/6] Sending biases...")
        send_bias(ser, bias0, 64, bias_bases[0])
        send_bias(ser, bias1, 12, bias_bases[1])

        print("[5/6] Sending input activations (784x4)...")
        t = time.time()
        send_activations(ser, input_act, 784, batch, 0)
        print(f"    Took {time.time()-t:.1f}s")

        upload_time = time.time() - t_start
        print(f"\n  Total upload time: {upload_time:.1f}s")

        print("\n[6/6] Sending RUN...")
        t = time.time()
        send_run(ser, 0, 2048)

        print("  Waiting for ACK...")
        ack = ser.read(1)
        if len(ack) != 1:
            raise RuntimeError("Timeout waiting for ACK — FPGA may have crashed")
        if ack[0] != 0xAA:
            raise RuntimeError(f"Expected 0xAA ACK, got 0x{ack[0]:02X}")
        compute_time = time.time() - t
        print(f"  Got ACK (0xAA) — compute took {compute_time:.3f}s")

        print("\n  Reading output (12x4)...")
        result = read_output(ser, 12, batch)

        total_time = time.time() - t_start
        print(f"\n  Total round-trip time: {total_time:.1f}s")

        print(f"\n  FPGA result:\n{result}")
        print(f"\n  Expected:\n{expected}")

        if np.array_equal(result, expected):
            print(f"\n  === PASS ===")

            # Show what this would look like as classification
            print(f"\n  Per-sample argmax (predicted class):")
            for s in range(batch):
                fpga_class = np.argmax(result[:, s])
                sw_class = np.argmax(expected[:, s])
                print(f"    Sample {s}: FPGA predicts class {fpga_class}, SW predicts class {sw_class}")
        else:
            print(f"\n  === FAIL ===")
            diff = np.where(result != expected)
            num_diff = len(diff[0])
            print(f"  {num_diff} mismatches out of {12 * batch} values")
            for i in range(min(20, num_diff)):
                r, c = diff[0][i], diff[1][i]
                print(f"    ({r},{c}): got {result[r][c]}, expected {expected[r][c]}")
            if num_diff > 20:
                print(f"    ... and {num_diff - 20} more")
        
              
    finally:
        ser.close()

if __name__ == "__main__":
    port = sys.argv[1] if len(sys.argv) > 1 else PORT
    test_feedforward(port)