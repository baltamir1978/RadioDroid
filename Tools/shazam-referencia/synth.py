"""Audio sintético reproducible (notas, barridos y ruido) para probar la huella sin música con derechos."""
import sys, numpy as np
sr, secs = 16000, 8
t = np.arange(sr * secs) / sr
rng = np.random.default_rng(1978)
x = np.zeros_like(t)
notes = [262, 330, 392, 523, 659, 784, 1046, 1318, 1568, 2093, 2637, 3136, 4186, 5274]
for i in range(32):                         # notas cortas con armónicos, cada cuarto de segundo
    f = notes[rng.integers(len(notes))]; a = i * 0.25
    env = np.exp(-np.clip(t - a, 0, None) * 6) * (t >= a)
    for h, amp in ((1, 1), (2, .5), (3, .25)):
        x += amp * env * np.sin(2 * np.pi * f * h * t)
x += 0.4 * np.sin(2 * np.pi * (300 + 600 * t) * t)          # barrido
x += 0.05 * rng.standard_normal(len(t))                       # ruido
pcm = np.round(x / np.abs(x).max() * 20000).astype("<i2")
pcm.tofile(sys.argv[1])
