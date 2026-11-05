"""Decodifica un audio a PCM s16le mono 16 kHz (lo que espera la huella de Shazam)."""
import sys, av, numpy as np
src, dst = sys.argv[1], sys.argv[2]
c = av.open(src)
r = av.AudioResampler(format="s16", layout="mono", rate=16000)
out = []
for f in c.decode(audio=0):
    for g in r.resample(f): out.append(g.to_ndarray().reshape(-1))
for g in r.resample(None): out.append(g.to_ndarray().reshape(-1))
pcm = np.concatenate(out).astype("<i2")
pcm.tofile(dst)
print(f"{len(pcm)/16000:.1f} s")
