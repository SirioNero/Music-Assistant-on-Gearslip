"""Decodifica i pacchetti catturati e misura quanto è pulito il segnale (le tracce di prova sono sinusoidi)."""
import struct, sys
import numpy as np
import av

def packets(path):
    with open(path, "rb") as f:
        data = f.read()
    i = 0
    while i + 12 <= len(data):
        ts, n = struct.unpack(">qi", data[i:i + 12])
        yield ts, data[i + 12:i + 12 + n]
        i += 12 + n

def decode_opus(path):
    ctx = av.CodecContext.create("libopus", "r")
    ctx.sample_rate = 48000
    ctx.layout = "stereo"
    out, errors, count, stamps = [], 0, 0, []
    for ts, payload in packets(path):
        count += 1
        stamps.append(ts)
        try:
            for frame in ctx.decode(av.Packet(payload)):
                a = frame.to_ndarray()
                out.append(a.reshape(-1, 2) if a.shape[0] == 1 else a.T)
        except Exception as e:
            errors += 1
            if errors < 4:
                print("  errore:", e)
    return np.concatenate(out).astype(float), errors, count, stamps

def decode_pcm(path):
    out, stamps = [], []
    for ts, payload in packets(path):
        stamps.append(ts)
        out.append(np.frombuffer(payload, dtype="<i2").reshape(-1, 2))
    return np.concatenate(out).astype(float), 0, len(stamps), stamps

def snr(seg, rate=48000):
    n = len(seg)
    spec = np.abs(np.fft.rfft(seg * np.hanning(n)))
    k = int(np.argmax(spec[1:])) + 1
    best = (None, -1)
    for f in np.linspace((k - 1) * rate / n, (k + 1) * rate / n, 201):
        t = np.arange(n) / rate
        m = np.column_stack([np.sin(2 * np.pi * f * t), np.cos(2 * np.pi * f * t), np.ones(n)])
        coef, *_ = np.linalg.lstsq(m, seg, rcond=None)
        res = seg - m @ coef
        val = 10 * np.log10((seg ** 2).mean() / max((res ** 2).mean(), 1e-9))
        if val > best[1]:
            best = (f, val)
    return best

for codec in sys.argv[1:]:
    path = f"/cache/capture-{codec}.bin"
    pcm, errors, count, stamps = (decode_opus if codec == "opus" else decode_pcm)(path)
    d = np.diff(stamps)
    print(f"{codec}: {count} pacchetti, errori {errors}, {len(pcm) / 48000:.1f} s decodificati, passo tempi {np.unique(d)[:5]}")
    left = pcm[:, 0]
    for start_s in (2, 10, 30, 45, 60, 75):
        a = int(start_s * 48000)
        seg = left[a:a + 48000]
        if len(seg) < 48000:
            break
        f, s = snr(seg)
        print(f"  a {start_s:>2} s: {f:7.2f} Hz, rapporto segnale/rumore {s:5.1f} dB, rms {np.sqrt((seg ** 2).mean()):.0f}")
