# RadioDroid — contexto del repositorio

Versión para Android de **RadioApp** (iOS), escrita **desde cero en Kotlin + Jetpack Compose**. Lo
común a las apps de Android (firma, `versionCode`, secretos, distribución, ShazamKit) está en el
`CLAUDE.md` de la carpeta de proyectos; aquí va lo propio de esta.

«RadioDroid» es el nombre de la carpeta y del repositorio. **En el móvil se llama FMDroid**
(Bruno, 21-oct-2026), porque ya hay una app de radio libre para Android llamada RadioDroid.

Tiene que funcionar en **GrapheneOS**, sin Google Play. Todo lo de esta app funciona así salvo
**Android Auto**, que en GrapheneOS necesita Google Play aislado y la app de Android Auto
instalados. Si no están, el resto de la app no debe notarlo.

## Estado (5-nov-2026)

**Primera versión: compila y pasan 20 pruebas del núcleo; sin probar todavía en un móvil.**
Letrasdroid sigue sin código, así que lo de LRCLIB, títulos, historial y memoria por emisora se
ha traducido de **LinRadio** (`titles.py`, `lyrics.py`, `catalog.py`, `history.py`, `memory.py`).

Hecho:
- `playback/RadioService.kt`: Media3 `MediaLibraryService` + ExoPlayer (también HLS). Pantalla
  de bloqueo, notificación, auriculares y árbol de emisoras para **Android Auto**.
  `setHandleAudioBecomingNoisy(true)`, foco de audio con reanudación sola. Reconexión sin fin
  mientras se quiera oír (1, 2, 4… hasta 30 s, y al momento si vuelve la red). Tras una pausa de
  más de 10 s, al seguir vuelve al directo. Reanudación del sistema con la última emisora.
- Títulos ICY con `IcyInfo` en `onMetadata` → `playback/NowPlaying.kt` (el motor): carátula de
  iTunes, título al revés corregido con su artista, letra de LRCLIB con todas las búsquedas de
  repuesto, historial. La canción va a la pantalla de bloqueo con `replaceMediaItem` (mismo URI,
  no corta).
- **Reloj de la letra en «tiempo de stream»**: posición de ExoPlayer + un ancla que se rehace en
  cada conexión. Una pausa, un corte o una llamada que se reanudan desde el búfer no desfasan la
  letra, y una reconexión salta al directo.
- Interfaz Compose: Emisoras (añadir, editar, borrar, importar/exportar el JSON de MacRadio y
  LinRadio), Buscar (Radio Browser, populares del país), Historial (favoritas, borrar, borrar para
  siempre, títulos ignorados, buscar en YouTube). Reproductor con letra sincronizada: tocar una
  línea sincroniza; − / + / ↺ por emisora; arrastrar deja de seguir 4 s. `WindowSizeClass`: raíl y
  reproductor con la letra al lado en plegable/tableta.
- `radiodroid://play?u=…&n=…` y accesos directos a las cuatro primeras emisoras. 5 idiomas.
- **Widgets** (Jetpack Glance, `widget/`): «Emisora» (carátula, canción, la línea que suena,
  play/pausa; tres tamaños), «Letra» (pequeño: la línea; mediano: anterior, actual y siguiente) y
  «Emisoras» (rejilla de hasta 12; tocar una la pone). `WidgetFeed` los alimenta: la letra sólo
  se mueve cuando cambia la línea, sonando, con la pantalla encendida y si hay un widget de letra
  puesto. **Medir la batería**: `adb logcat -s FMWidgets` cuenta las actualizaciones por minuto.

Falta:
- **Shazam**, con su AAR y su token (ver Letrasdroid): `TeeAudioProcessor`, aprender `title_lag`,
  títulos que se quedan, la ráfaga de audio atrasado al conectar, y ajuste a ±0 en cada canción
  con Shazam. Ahora `title_lag` vale siempre 0.
- La cuña de entrada (`intro.py` de LinRadio).
- URLs `.pls`/`.m3u` en emisoras propias (las de Radio Browser ya vienen resueltas).
- `versionCode`: revisar el criterio del `CLAUDE.md` común (no estaba en esta máquina).
- Probar en el móvil: las 8 emisoras de partida, sobre todo las que necesitaban el proxy.

## Cómo se compila (Linux)

`Tools/instalar-entorno.sh` instala sin sudo, en `~/Android`, el JDK 17, el SDK y Gradle.
Después: `./gradlew testDebugUnitTest assembleDebug` y
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

**Firma de release** (5-nov-2026): `~/.android/claves/fmdroid-release.jks`, con sus datos en
`~/.android/claves/fmdroid-release.properties`, que lee `app/build.gradle.kts`. Fuera del repo y
sin copia en ninguna otra parte: **si se pierde, no se puede actualizar la app instalada** (hay que
desinstalarla y se pierden las emisoras y el historial). Huella SHA-256 del certificado:
`0D:70:0A:BC:8A:82:1F:CB:A7:43:0E:CA:90:23:10:8B:5A:BF:4F:B9:16:F6:AE:1D:D1:9F:05:02:D2:6F:F7:97`.
`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (unos 5 MB).

`applicationId`: `es.altamirano.fmdroid` (provisional: comprobarlo con el `CLAUDE.md` común).

## La especificación es el original

`../RadioApp` (`github.com/baltamir1978/RadioApp`, **privado**): leer su `README.md` y su
`REVISION.md`. Las mejoras de MacRadio también cuentan (ver `../MacRadio/REVISION.md`). Qué hace:

- Reproducción en segundo plano con controles en la pantalla de bloqueo y carátula.
- Búsqueda en Radio Browser y emisoras propias (URL y logo).
- **Shazam sobre el propio stream**, carátulas (Shazam o iTunes Search, con caché por canción).
- **Letra sincronizada** de LRCLIB que sigue la canción; tocar una línea sincroniza; − / + / ↺ por
  emisora.
- **Pantallas anchas** (plegable abierto, tableta): reproductor y letra lado a lado.
- **Resistencia a cortes de red** (pensada para 5G en movimiento) y proxy para emisoras que
  describen mal su stream.
- **Si desaparece el coche o los auriculares, calla** en vez de seguir por el altavoz; **una pausa
  que el usuario no pidió** (llamada, asistente) se recupera sola.
- Historial con favoritas y títulos ignorados; widgets (emisora con carátula y letra, «Letra»
  pequeño y mediano, accesos a emisoras); CarPlay; atajos; 5 idiomas (es, en, de, fr, pt).

## Cómo se hace en Android

| En iOS | Aquí |
|---|---|
| `AVPlayer` + Now Playing | **Media3**: ExoPlayer dentro de un `MediaLibraryService`. Da la pantalla de bloqueo, los auriculares y **Android Auto** sin escribir interfaz para el coche |
| CarPlay (`CarPlaySceneDelegate`) | Android Auto con el mismo `MediaLibraryService`: árbol de emisoras navegable. Se prueba con la Desktop Head Unit |
| Títulos de la emisora | ExoPlayer entrega el ICY como `IcyInfo` en los metadatos |
| `AudioStreamTap` / `StreamDecoder` para Shazam | `TeeAudioProcessor` en el render de audio: copia el PCM que suena hacia ShazamKit, **sin segunda conexión** (comprobarlo) |
| `LocalStreamProxy` | Probablemente sobra: ExoPlayer tolera mejor los streams mal descritos. Comprobarlo con las emisoras que lo necesitaban |
| Ruta de audio que desaparece | `ACTION_AUDIO_BECOMING_NOISY` → pausa (ExoPlayer: `setHandleAudioBecomingNoisy(true)`) |
| Interrupciones (llamada, Siri) | Foco de audio gestionado por ExoPlayer (`handleAudioFocus = true`), que reanuda solo |
| WidgetKit | Jetpack Glance. Mover la letra línea a línea en un widget cuesta batería: medirlo |
| Dos columnas en pantallas anchas | `WindowSizeClass` |
| Atajos de Siri, `radioapp://play?u=…` | Accesos directos de la app y enlaces `radiodroid://play?u=…` |

## Trampas que vienen del original y de MacRadio

- **Identificar automáticamente sólo con la app en primer plano**, por los datos móviles.
- **El retraso de la letra es de la emisora**: se aprende por emisora con Shazam (`title_lag`). No
  guardar un «retraso de conexión» entre sesiones.
- **Títulos que se quedan**: pasada la duración más 20 s con el mismo título, preguntar a Shazam.
- **Algunas emisoras mandan «Título - Artista» al revés**: se corrige con el artista de iTunes.
- **Ráfaga de audio atrasado al conectar** (Cadena 100): no pasársela a Shazam.
- **LRCLIB y las colaboraciones**: buscar también por el artista principal.
- Con Shazam situando la canción, el ajuste manual vuelve a ±0 en cada canción nueva.
