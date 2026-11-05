# Referencia de la huella de Shazam

`refshazam/` es `algorithm.py`, `signature.py` y `enums.py` de **shazamio 0.4.0.1**
(https://github.com/shazamio/ShazamIO, licencia MIT, ver `refshazam/LICENSE`), con una
corrección: la banda de 3,5–5,5 kHz (`5500 < f <= 5500` → `3500 < f <= 5500`, como SongRec).
`ShazamSignature.kt` es su traducción y tiene que dar los mismos bytes.

```sh
python3 -m venv /tmp/venv && /tmp/venv/bin/pip install av numpy
# grabar 25 s de una emisora y pasarlos a PCM s16 mono 16 kHz
timeout 25 curl -s -o radio.mp3 <url del stream>
/tmp/venv/bin/python decode.py radio.mp3 radio.pcm
# huella de referencia de 12 s desde el segundo 2, y consulta de verdad a Shazam
/tmp/venv/bin/python query.py radio.pcm 2 12 --sig-out radio-2-12.sig
dd if=radio.pcm of=radio-2-12.pcm bs=2 skip=32000 count=192000
# la de Kotlin tiene que coincidir
FM_SHAZAM_PCM=$PWD/radio-2-12.pcm ./gradlew testDebugUnitTest --tests '*ShazamSignatureTest*'
```

`synth.py` genera el audio sintético de `app/src/test/resources/shazam/` (no se sube música).

Medido el 5-nov-2026 con Cadena 100: Shazam reconoce 12 s y también 8 s, y su `offset` es el
segundo de la canción en que **empieza** el trozo (el mismo trozo con 8 o 12 s da lo mismo;
empezando 5 s después, 5,005 s más).
