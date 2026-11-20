"""Synthesizes the app's built-in sound effects (classic shorts SFX) into app/src/main/assets/sfx.

Everything is generated from oscillators and noise here, so the sounds carry no license.
48 kHz mono 16-bit WAV.  Run: python3 scripts/make_sfx.py
"""
import os
import wave

import numpy as np

SR = 48000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'assets', 'sfx')
rng = np.random.default_rng(7)


def t_(dur):
    return np.arange(int(dur * SR)) / SR


def env_ad(n, attack, decay_tau):
    t = np.arange(n) / SR
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    return a * np.exp(-np.maximum(t - attack, 0) / decay_tau)


def noise(n):
    return rng.standard_normal(n)


def bandpass_sweep(x, f0, f1, q=2.0):
    """Time-varying 2-pole band-pass (state variable filter) from f0 to f1 (log sweep)."""
    n = len(x)
    f = f0 * (f1 / f0) ** (np.arange(n) / max(1, n - 1))
    y = np.zeros(n)
    low = band = 0.0
    damp = 1.0 / q
    for i in range(n):
        g = 2 * np.sin(np.pi * min(f[i], SR / 6) / SR)
        high = x[i] - low - damp * band
        band += g * high
        low += g * band
        y[i] = band
    return y


def lowpass(x, fc):
    a = np.exp(-2 * np.pi * fc / SR)
    y = np.zeros_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def chirp(f0, f1, dur, shape='sin', log=True):
    t = t_(dur)
    f = f0 * (f1 / f0) ** (t / dur) if log else f0 + (f1 - f0) * t / dur
    ph = 2 * np.pi * np.cumsum(f) / SR
    if shape == 'sin':
        return np.sin(ph)
    if shape == 'square':
        return np.sign(np.sin(ph))
    return 2 * ((ph / (2 * np.pi)) % 1) - 1


def bell(f, dur, partials=((1, 1), (2.76, 0.5), (5.4, 0.25), (8.93, 0.12)), tau=0.5):
    t = t_(dur)
    y = sum(a * np.sin(2 * np.pi * f * r * t) * np.exp(-t / (tau / (1 + r * 0.3))) for r, a in partials)
    return y * np.clip(t / 0.002, 0, 1)


def pad(x, dur):
    out = np.zeros(int(dur * SR))
    out[:min(len(x), len(out))] = x[:len(out)]
    return out


def at(base, x, sec, gain=1.0):
    i = int(sec * SR)
    j = min(len(base), i + len(x))
    base[i:j] += x[:j - i] * gain
    return base


def norm(x, peak=0.85):
    m = np.max(np.abs(x)) or 1
    x = x / m * peak
    # 1.5 ms fade in, 10 ms fade out: no clicks.
    k1, k2 = int(0.0015 * SR), int(0.01 * SR)
    x[:k1] *= np.linspace(0, 1, k1)
    x[-k2:] *= np.linspace(1, 0, k2)
    return x


def whoosh(dur=0.7, f0=300, f1=3000):
    n = int(dur * SR)
    t = np.arange(n) / n
    e = np.sin(np.pi * t) ** 2.5
    return bandpass_sweep(noise(n), f0, f1, q=1.6) * e


def make():
    s = {}
    s['whoosh'] = whoosh(0.75, 250, 2500)
    s['swoosh'] = whoosh(0.32, 900, 6000)
    s['swipe'] = whoosh(0.22, 4000, 800)
    # Pop: a quick pitch drop with a click.
    p = chirp(900, 160, 0.09) * env_ad(int(0.09 * SR), 0.001, 0.03)
    s['pop'] = pad(p + 0.4 * noise(len(p)) * env_ad(len(p), 0.0005, 0.004), 0.15)
    b = chirp(250, 1100, 0.12) * env_ad(int(0.12 * SR), 0.002, 0.05)
    s['bubble'] = pad(b, 0.18)
    s['ding'] = bell(1568, 1.6)
    s['bell'] = bell(880, 2.2, tau=0.9)
    # Boom: a low sine dropping, a noise hit on top.
    n = int(1.8 * SR)
    boom = chirp(90, 32, 1.8) * env_ad(n, 0.004, 0.55)
    hit = lowpass(noise(n), 900) * env_ad(n, 0.001, 0.06) * 3
    s['boom'] = boom + hit
    # Bass drop: deeper and longer.
    n = int(2.4 * SR)
    s['bass_drop'] = chirp(160, 28, 2.4) * env_ad(n, 0.01, 0.9) * (1 + 0.3 * np.sin(2 * np.pi * 7 * np.arange(n) / SR))
    # Riser: noise sweeping up, a rising tone under it.
    n = int(3.0 * SR)
    ramp = (np.arange(n) / n) ** 2
    s['riser'] = (bandpass_sweep(noise(n), 200, 7000, q=3) * 0.8 + 0.35 * chirp(120, 1200, 3.0, 'saw')) * ramp
    # Click and a camera shutter.
    c = noise(int(0.012 * SR)) * env_ad(int(0.012 * SR), 0.0002, 0.002)
    s['click'] = pad(bandpass_sweep(c, 3000, 3000, q=4) * 3 + c * 0.3, 0.05)
    sh = np.zeros(int(0.25 * SR))
    for k, (sec, g) in enumerate(((0.0, 1.0), (0.085, 0.8))):
        burst = bandpass_sweep(noise(int(0.04 * SR)), 2500, 1200, q=1.2) * env_ad(int(0.04 * SR), 0.0005, 0.008)
        at(sh, burst, sec, g)
    s['camera'] = sh
    # Ka-ching: two bright pings and a rattle.
    kc = np.zeros(int(1.3 * SR))
    at(kc, lowpass(noise(int(0.12 * SR)), 5000) * env_ad(int(0.12 * SR), 0.001, 0.03), 0.0, 0.6)
    at(kc, bell(2637, 1.1, tau=0.35), 0.08, 0.9)
    at(kc, bell(3520, 1.0, tau=0.35), 0.16, 0.7)
    s['cash'] = kc
    # Glitch: chopped square and noise bursts.
    g = np.zeros(int(0.6 * SR))
    pos = 0.0
    while pos < 0.55:
        d = rng.uniform(0.02, 0.06)
        f = rng.choice([110, 220, 440, 880, 1760])
        seg = chirp(f, f * rng.uniform(0.5, 2), d, 'square') if rng.random() < 0.6 else noise(int(d * SR))
        seg = np.round(seg * 4) / 4
        at(g, seg, pos, rng.uniform(0.4, 1))
        pos += d + rng.uniform(0, 0.02)
    s['glitch'] = g
    # Notification: two soft tones.
    nt = np.zeros(int(0.7 * SR))
    at(nt, bell(1319, 0.5, partials=((1, 1), (2, 0.2)), tau=0.25), 0.0)
    at(nt, bell(1760, 0.6, partials=((1, 1), (2, 0.2)), tau=0.3), 0.12)
    s['notify'] = nt
    # Typing: key clicks at a human rhythm.
    ty = np.zeros(int(1.4 * SR))
    pos = 0.02
    while pos < 1.3:
        k = noise(int(0.018 * SR)) * env_ad(int(0.018 * SR), 0.0003, 0.003)
        k = bandpass_sweep(k, rng.uniform(1800, 3200), 2000, q=2) * 2 + k * 0.2
        at(ty, k, pos, rng.uniform(0.5, 1))
        pos += rng.uniform(0.06, 0.16)
    s['typing'] = ty
    # Laser / zap.
    s['zap'] = chirp(2600, 180, 0.35, 'square') * env_ad(int(0.35 * SR), 0.001, 0.12) * 0.6
    # Heartbeat: two low thumps, twice.
    hb = np.zeros(int(1.8 * SR))
    th = chirp(70, 40, 0.15) * env_ad(int(0.15 * SR), 0.003, 0.05)
    for sec in (0.0, 0.25, 0.9, 1.15):
        at(hb, th, sec, 1.0 if sec in (0.0, 0.9) else 0.7)
    s['heartbeat'] = hb
    # Ta-da: a rising arpeggio and a chord.
    td = np.zeros(int(1.8 * SR))
    for k, f in enumerate((523, 659, 784)):
        at(td, bell(f, 0.4, partials=((1, 1), (2, 0.3), (3, 0.1)), tau=0.15), k * 0.09, 0.6)
    for f in (523, 659, 784, 1047):
        at(td, bell(f, 1.5, partials=((1, 1), (2, 0.35), (3, 0.15)), tau=0.6), 0.3, 0.5)
    s['tada'] = td
    # Fail: four falling notes, the last one wobbling.
    fl = np.zeros(int(2.2 * SR))
    for k, f in enumerate((392, 370, 349)):
        n = int(0.38 * SR)
        tone = chirp(f, f * 0.98, 0.38, 'saw')
        at(fl, lowpass(tone, 1800) * env_ad(n, 0.01, 0.6), k * 0.42, 0.8)
    n = int(1.0 * SR)
    t = np.arange(n) / SR
    f = 330 * (1 + 0.03 * np.sin(2 * np.pi * 6 * t)) * (1 - 0.06 * t)
    tone = 2 * ((np.cumsum(f) / SR) % 1) - 1
    at(fl, lowpass(tone, 1500) * env_ad(n, 0.01, 0.7), 1.26, 0.9)
    s['fail'] = fl
    # Applause: many short claps.
    ap = np.zeros(int(2.6 * SR))
    for _ in range(420):
        sec = rng.uniform(0, 2.3) ** 1.0
        cl = bandpass_sweep(noise(int(0.02 * SR)), rng.uniform(900, 2500), 1500, q=1.5) * env_ad(int(0.02 * SR), 0.0005, 0.004)
        at(ap, cl, sec, rng.uniform(0.2, 1))
    s['applause'] = ap * np.clip((2.6 - np.arange(len(ap)) / SR) / 0.6, 0, 1)
    # Drama: three low stabs.
    dr = np.zeros(int(2.4 * SR))
    for k, (sec, f, d) in enumerate(((0.0, 110, 0.35), (0.45, 104, 0.35), (0.9, 98, 1.4))):
        n = int(d * SR)
        tone = sum(chirp(f * m, f * m, d, 'saw') * a for m, a in ((1, 1), (1.5, 0.6), (2, 0.4)))
        at(dr, lowpass(tone, 1200) * env_ad(n, 0.005, d * 0.5), sec)
    s['drama'] = dr
    # Record scratch: noise through a fast-wobbling filter.
    n = int(0.5 * SR)
    t = np.arange(n) / SR
    sc = bandpass_sweep(noise(n), 400, 2400, q=4) * (0.6 + 0.4 * np.sin(2 * np.pi * 18 * t))
    s['scratch'] = sc * env_ad(n, 0.005, 0.25)
    return s


def write(name, x):
    x = norm(np.asarray(x, dtype=np.float64))
    pcm = (np.clip(x, -1, 1) * 32767).astype('<i2')
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for k, v in make().items():
        write(k, v)
        print(k, round(len(v) / SR, 2), 's')
