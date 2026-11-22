"""Builds the app's sound effects (app/src/main/assets/sfx/<id>.ogg, 48 kHz mono Vorbis) from
real recordings, all CC0 (credits: app/src/main/assets/sfx/CREDITS.txt).

Sources:
  sk: SoundKit by Soundza (https://github.com/soundza/soundkit), CC0, non-AI. Clone it and pass
      its folder: the sounds are MP3s embedded in registry/soundza/sounds/<name>.ts.
  fs: Freesound previews of CC0 sounds, fetched by the workflow on the sfx-src branch into the
      sfx-fs branch (git archive origin/sfx-fs | tar -x -C <folder>).

Run: python3 scripts/build_sfx.py <soundkit clone> <sfx-fs folder> app/src/main/assets/sfx
     (needs numpy, scipy and ffmpeg with libvorbis)

Each entry: id -> (source, start ms, end ms or None, fade-out ms, peak cap dBFS[, fade-in ms[, loudness]]).
Leading and trailing silence is trimmed, the loudest 400 ms is matched to -15 dB (K-weighted, like
LUFS) and the peak capped, so every sound lands at about the same level. Ids never change: saved
projects refer to them.
"""
import base64, re, subprocess, sys, os, tempfile
import numpy as np

SK_REPO, FS, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
SK = tempfile.mkdtemp()
R = 48000

SPEC = {
    # transitions
    'whoosh':        ('sk:air-whoosh', 0, None, 0, -1),
    'swoosh':        ('sk:quick-air-whoosh', 0, None, 0, -1),
    'whip':          ('sk:whoosh-for-whip-zoom', 0, 420, 60, -1),
    'swipe':         ('sk:swipe-ui-transition', 0, None, 0, -1),
    'reverse':       ('sk:reverse-whoosh', 0, None, 0, -1),
    'riser':         ('sk:cinematic-tension-riser', 0, None, 0, -1),
    'buildup':       ('sk:noise-riser-sound-fx', 0, None, 0, -1),
    'reverse_cymbal':('sk:reverse-cymbal-crash', 0, None, 0, -1),
    'glitch':        ('sk:vhs-glitch', 0, None, 0, -1),
    'static':        ('sk:radio-static-transition', 0, 1300, 250, -1),
    'rewind':        ('sk:record-rewind-whoosh', 0, None, 0, -1),
    # impacts
    'boom':          ('sk:distant-bass-boom', 0, None, 0, -1),
    'impact':        ('sk:explosive-impact-whoosh', 0, None, 0, -1),
    'bass_drop':     ('sk:cinematic-sub-drop', 0, 3500, 800, -1),
    'braam':         ('sk:cinematic-braam', 0, None, 0, -1),
    'drama':         ('sk:aggressive-braam', 0, None, 0, -1),
    'thump':         ('sk:deep-bass-thump', 0, None, 0, -1),
    'punch':         ('sk:punch-swish', 0, None, 0, -1),
    'crash':         ('sk:short-crash-cymbal', 0, None, 0, -1),
    'zap':           ('sk:digital-zap', 0, None, 0, -1),
    # reactions
    'pop':           ('sk:short-pop', 0, None, 0, -1),
    'bubble':        ('sk:bubble-1', 0, None, 0, -1),
    'cork':          ('sk:champagne-cork-pop', 0, None, 0, -1),
    'poof':          ('sk:magical-appearance-poof', 0, 3000, 700, -1),
    'confetti':      ('sk:party-popper-pop-with-air-rush', 0, None, 0, -1),
    'fail':          ('sk:horn-fail-wah-wah-low', 0, None, 0, -1),
    'buzzer':        ('sk:wrong-answer-buzzer', 0, None, 0, -1),
    'rimshot':       ('sk:bah-dum-tss-sting', 0, None, 0, -1),
    'drumroll':      ('sk:snare-drumroll', 0, 3500, 400, -1),
    'scratch':       ('sk:vinyl-scratch', 0, None, 0, -1),
    'applause':      ('sk:stadium-crowd-applause', 0, 4000, 1200, -1),
    # notifications
    'ding':          ('sk:glass-ding', 0, None, 0, -1),
    'bell':          ('sk:bright-bell-chime', 0, None, 0, -1),
    'notify':        ('sk:phone-notification-chime', 0, None, 0, -1),
    'sparkle':       ('sk:magical-sparkle-shimmer', 150, 3000, 900, -1),
    'tada':          ('sk:success-fanfare', 0, None, 0, -1),
    'levelup':       ('sk:level-up-success-fanfare', 0, None, 0, -1),
    'error':         ('sk:computer-error-beep', 0, None, 0, -1),
    'countdown':     ('sk:countdown-beeper', 0, 4600, 300, -1),
    # objects
    'click':         ('sk:mouse-click', 0, None, 0, -3),
    'camera':        ('sk:camera-shutter-click', 0, 650, 80, -1),
    'page':          ('sk:book-page-flip', 0, None, 0, -1),
    'cash':          ('sk:cash-register-closing', 700, None, 0, -1),
    'coins':         ('sk:money-bag-jingle', 0, None, 0, -1),
    'snap':          ('sk:finger-snap', 0, None, 0, -1),
    # Freesound, CC0
    'laugh':         ('fs:laugh_383207.mp3', 10700, 14300, 900, -1, 60),
    'cheer':         ('fs:wow_324892.mp3', 400, 4500, 1200, -1),
    'boo':           ('fs:wow_264378.mp3', 0, 3500, 1000, -1, 120),
    'crickets':      ('fs:crickets_481883.mp3', 2000, 6000, 800, -1, 300, -21),
    'heartbeat':     ('fs:heartbeat_250845.mp3', 300, 4300, 500, -1),
}

def decode(path):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-ac', '1', '-ar', str(R), '-f', 'f32le', '-'],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32).astype(np.float64)

def soundkit(name):
    """The MP3 embedded in SoundKit's registry entry for [name], as a file."""
    path = f'{SK}/{name}.mp3'
    if not os.path.exists(path):
        ts = open(f'{SK_REPO}/registry/soundza/sounds/{name}.ts').read()
        b64 = re.search(r'src: "data:audio/mpeg;base64,([^"]+)"', ts).group(1)
        open(path, 'wb').write(base64.b64decode(b64))
    return path

def src(s):
    kind, name = s.split(':', 1)
    return decode(soundkit(name) if kind == 'sk' else f'{FS}/{name}')

from scipy.signal import lfilter

def short_rms(x):
    """Loudest 400 ms (momentary loudness, BS.1770 K-weighting), as a linear level."""
    y = lfilter([1.53512485958697, -2.69169618940638, 1.19839281085285], [1, -1.69065929318241, 0.73248077421585], x)
    y = lfilter([1.0, -2.0, 1.0], [1, -1.99004745483398, 0.99007225036621], y)
    w = min(len(y), int(0.4 * R))
    c = np.concatenate([[0], np.cumsum(y * y)])
    m = (c[w:] - c[:-w]) / w
    return np.sqrt(m.max())

def finish(x, start=0, end=None, fade=0, cap=-1, target=-15.0, fade_in=0):
    x = x[int(start * R / 1000): int(end * R / 1000) if end else None].copy()
    pk = np.abs(x).max()
    # Leading silence (below -40 dB of the peak), keeping 4 ms before the onset.
    on = int(np.argmax(np.abs(x) > pk * 0.01))
    x = x[max(0, on - int(0.004 * R)):]
    # Trailing silence (below -60 dB of the peak).
    loud = np.nonzero(np.abs(x) > pk * 0.001)[0]
    x = x[: loud[-1] + int(0.02 * R)]
    if fade_in:
        n = min(len(x), int(fade_in * R / 1000))
        x[:n] *= np.sin(np.linspace(0, np.pi / 2, n)) ** 2
    if fade:
        n = min(len(x), int(fade * R / 1000))
        x[-n:] *= np.cos(np.linspace(0, np.pi / 2, n)) ** 2
    # A short fade at both ends so a cut never clicks.
    n = int(0.003 * R)
    x[:n] *= np.linspace(0, 1, n)
    x[-n:] *= np.linspace(1, 0, n)
    g = min(10 ** (target / 20) / short_rms(x), 10 ** (cap / 20) / np.abs(x).max())
    return x * g

def typing():
    """A few keystrokes at an uneven typing pace."""
    keys = [finish(decode(soundkit(n))) for n in ['mechanical-keyboard-keystroke', 'mechanical-keyboard-click', 'spacebar-keystroke']]
    rng = np.random.default_rng(7)
    seq = [0, 1, 0, 0, 1, 2, 0, 1, 0, 0]
    out = np.zeros(int(1.6 * R))
    t = 0
    for k in seq:
        s = keys[k] * rng.uniform(0.7, 1.0)
        e = min(len(out), t + len(s))
        out[t:e] += s[: e - t]
        t += int(rng.uniform(0.085, 0.16) * R)
    return finish(out)

def encode(x, path):
    pcm = (np.clip(x, -1, 1) * 32767).astype('<i2').tobytes()
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 's16le', '-ar', str(R), '-ac', '1', '-i', '-',
                    '-c:a', 'libvorbis', '-q:a', '4', path], input=pcm, check=True)

if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    spec = dict(SPEC)
    for sid, (s, a, b, fade, cap, *more) in spec.items():
        x = finish(src(s), a, b, fade, cap, fade_in=more[0] if more else 0, target=more[1] if len(more) > 1 else -15.0)
        encode(x, f'{OUT}/{sid}.ogg')
        print(f'{sid:15s} {len(x) / R:5.2f}s  peak {20 * np.log10(np.abs(x).max()):5.1f}  rms {20 * np.log10(short_rms(x)):5.1f}')
    x = typing()
    encode(x, f'{OUT}/typing.ogg')
    print(f'typing          {len(x) / R:5.2f}s')
