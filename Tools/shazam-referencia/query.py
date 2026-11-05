"""Huella de referencia (shazamio 0.4, MIT) de un PCM s16le 16 kHz y consulta a Shazam.
Uso: query.py <pcm> [inicio_s] [duración_s] [--sig-out fichero]"""
import sys, json, time, uuid, urllib.request, gzip, numpy as np
sys.path.insert(0, sys.argv[0].rsplit("/", 1)[0])
from refshazam.algorithm import SignatureGenerator
pcm = np.fromfile(sys.argv[1], dtype="<i2")
start = float(sys.argv[2]) if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else 0
dur = float(sys.argv[3]) if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else 12
clip = pcm[int(start*16000):int((start+dur)*16000)]
g = SignatureGenerator(); g.MAX_TIME_SECONDS = dur
g.feed_input(clip.tolist())
sig = g.get_next_signature()
peaks = {int(b): len(p) for b, p in sig.frequency_band_to_sound_peaks.items()}
uri = sig.encode_to_uri()
if "--sig-out" in sys.argv:
    open(sys.argv[sys.argv.index("--sig-out")+1], "wb").write(sig.encode_to_binary())
print("muestras", sig.number_samples, "picos por banda", peaks)
if "--no-query" in sys.argv: sys.exit()
url = ("https://amp.shazam.com/discovery/v5/es/ES/android/-/tag/%s/%s?sync=true&webv3=true&sampling=true"
       "&connected=&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"
       % (str(uuid.uuid4()).upper(), str(uuid.uuid4()).upper()))
body = {"timezone": "Europe/Madrid", "signature": {"uri": uri, "samplems": int(sig.number_samples/16)},
        "timestamp": int(time.time()*1000), "context": {}, "geolocation": {}}
req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json",
      "User-Agent": "FMDroid-test", "Accept": "*/*", "Accept-Language": "es"})
raw = urllib.request.urlopen(req, timeout=20).read()
d = json.loads(raw)
m = (d.get("matches") or [{}])[0]; t = d.get("track") or {}
print(json.dumps({"title": t.get("title"), "artist": t.get("subtitle"), "offset": m.get("offset"),
                  "timeskew": m.get("timeskew"), "frequencyskew": m.get("frequencyskew"),
                  "coverart": (t.get("images") or {}).get("coverart"), "retryms": d.get("retryms"),
                  "keys": sorted(d.keys())}, ensure_ascii=False, indent=1))
