"""Sveglia's own alarm sounds, synthesised from scratch (no samples), each a seamless loop.

Alba: soft bell-sines climbing a D major pentatonic over a low drone, like light lifting.
Campanile: two distant bells (Risset's inharmonic partials), slow, alternating.
Brezza: a warm Dmaj9 pad breathing, with a slow, airy arpeggio above.
"""
import numpy as np, subprocess, sys

SR = 44100
rng = np.random.default_rng(7)

def hz(midi): return 440.0 * 2 ** ((midi - 69) / 12)

def env(n, attack, decay):
    t = np.arange(n) / SR
    a = np.clip(t / attack, 0, 1)
    return a * np.exp(-t / decay)

def place(buf, sig, at):
    i = int(at * SR)
    end = i + len(sig)
    if end <= len(buf):
        buf[i:end] += sig
    else:  # wrap round: the loop is circular, so a tail spills into the start
        k = len(buf) - i
        buf[i:] += sig[:k]
        buf[:end - len(buf)] += sig[k:]

def bell_sine(f, dur, amp, decay):
    n = int(dur * SR); t = np.arange(n) / SR
    s = (np.sin(2 * np.pi * f * t) * env(n, 0.012, decay)
         + 0.35 * np.sin(2 * np.pi * 2 * f * t) * env(n, 0.008, decay * 0.45)
         + 0.12 * np.sin(2 * np.pi * 3.01 * f * t) * env(n, 0.006, decay * 0.25))
    return amp * s

RISSET = [(0.56, 1, 1), (0.56 + 1 / 1000, .67, .9), (0.92, 1, .65), (0.92 + 1.7 / 1000, 1.8, .55),
          (1.19, 2.67, .325), (1.7, 1.67, .35), (2.0, 1.46, .25), (2.74, 1.33, .2),
          (3.0, 1.33, .15), (3.76, 1, .1), (4.07, 1.33, .075)]

def bell(f, dur, amp):
    n = int(dur * SR); t = np.arange(n) / SR
    s = np.zeros(n)
    for ratio, a, d in RISSET:
        s += a * np.sin(2 * np.pi * f * ratio * t) * env(n, 0.004, dur * d)
    return amp * s / 8

def reverb(x, seconds=1.6, mix=0.28):
    n = int(seconds * SR)
    ir = rng.standard_normal(n) * np.exp(-np.arange(n) / (SR * seconds / 6))
    ir[: int(0.012 * SR)] = 0
    ir /= np.sqrt(np.sum(ir ** 2))
    # Circular convolution keeps the loop seamless
    L = len(x)
    wet = np.real(np.fft.ifft(np.fft.fft(x, L) * np.fft.fft(np.pad(ir, (0, max(0, L - n)))[:L])))
    return (1 - mix) * x + mix * wet

def lowpass(x, cutoff):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1 / SR)
    X *= 1 / np.sqrt(1 + (f / cutoff) ** 4)
    return np.fft.irfft(X, len(x))

def alba():
    L = 28.0; buf = np.zeros(int(L * SR))
    scale = [62, 66, 69, 71, 74, 78, 81, 83]  # D major pentatonic, rising
    phrases = [[0, 1, 2, 4], [1, 2, 4, 5], [2, 4, 5, 7], [4, 5, 6, 7]]
    t = 0.0
    for ph in phrases:
        for j, k in enumerate(ph):
            place(buf, bell_sine(hz(scale[k]), 5.0, 0.22 if j < 3 else 0.28, 1.8), t)
            t += 0.62
        t += 4.52
    # A low drone on D and A, breathing slowly
    n = len(buf); tt = np.arange(n) / SR
    breath = 0.5 + 0.5 * np.sin(2 * np.pi * tt / L - np.pi / 2)
    drone = (np.sin(2 * np.pi * hz(50) * tt) + 0.6 * np.sin(2 * np.pi * hz(57) * tt)) * (0.03 + 0.03 * breath)
    return reverb(buf + drone, 2.2, 0.32)

def campanile():
    L = 24.0; buf = np.zeros(int(L * SR))
    for i in range(8):
        f = hz(57) if i % 2 == 0 else hz(52)  # A3, then E3
        place(buf, bell(f, 9.0, 0.9 if i % 4 == 0 else 0.7), i * 3.0)
    return reverb(lowpass(buf, 5200), 2.8, 0.35)

def brezza():
    L = 32.0; n = int(L * SR); tt = np.arange(n) / SR
    chord = [50, 57, 62, 66, 69, 76]  # D, A, D, F#, A, E: Dmaj9 open
    pad = np.zeros(n)
    for i, m in enumerate(chord):
        for det in (-0.07, 0.0, 0.07):
            f = hz(m + det)
            # soft saw from a few harmonics
            pad += sum(np.sin(2 * np.pi * k * f * tt + i) / k ** 1.6 for k in range(1, 6)) / (len(chord) * 3)
    swell = 0.55 + 0.45 * np.sin(2 * np.pi * tt / (L / 2) - np.pi / 2)
    pad = lowpass(pad * swell, 1400) * 0.5
    air = lowpass(rng.standard_normal(n), 2600) * 0.012 * swell
    buf = pad + air
    arp = [74, 78, 81, 76, 74, 81, 83, 78]
    for i, m in enumerate(arp):
        place(buf, bell_sine(hz(m), 6.0, 0.12, 2.6), 1.5 + i * 4.0)
    return reverb(buf, 2.5, 0.3)

def write(name, x):
    x = x - np.mean(x)
    x = x / np.max(np.abs(x)) * 0.7  # leave headroom; Sveglia swells the volume itself
    pcm = (x * 32767).astype(np.int16).tobytes()
    out = f"{sys.argv[1]}/{name}.ogg"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", "-",
                    "-c:a", "libvorbis", "-q:a", "4", out], input=pcm, check=True)
    print(name, f"{len(x) / SR:.0f}s", out)

for name, fn in (("alba", alba), ("campanile", campanile), ("brezza", brezza)):
    write(name, fn())
