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

**Funciona en el móvil de Bruno (radios y aspecto, 0.2). Android Auto y Shazam (0.3), sin probar en el móvil.**
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
- **Aspecto de RadioApp** (0.2): colores de `Theme.swift` (menta, marca #1F6F64 / #5FD3C2 en
  oscuro) en `ui/Theme.kt` y en los widgets; una sola pantalla «Mis Emisoras» con historial,
  búsqueda y + arriba; logos con fondo blanco o iniciales de color; barras animadas en la que
  suena; reproductor con «EN DIRECTO», carátula de 200 dp y botón redondo de 76 dp; «Ver letra»
  en el móvil; textos de la letra copiados de iOS. Como en iOS, **la letra no resalta ninguna
  línea hasta saber por dónde va** (`clock.exact`): tocar la que suena la sitúa. Icono: la radio
  menta de `AppIconView.swift` en vector.
- `radiodroid://play?u=…&n=…` y accesos directos a las cuatro primeras emisoras. 5 idiomas.
- **Widgets** (Jetpack Glance, `widget/`): «Emisora» (carátula, canción, la línea que suena,
  play/pausa; tres tamaños), «Letra» (pequeño: la línea; mediano: anterior, actual y siguiente) y
  «Emisoras» (rejilla de hasta 12; tocar una la pone). `WidgetFeed` los alimenta: la letra sólo
  se mueve cuando cambia la línea, sonando, con la pantalla encendida y si hay un widget de letra
  puesto. **Medir la batería**: `adb logcat -s FMWidgets` cuenta las actualizaciones por minuto.

- **Shazam sin ShazamKit** (0.3, 5-nov-2026; Bruno da de baja su cuenta de Apple, así que
  ShazamKit no es opción). La huella se calcula en el móvil (`core/ShazamSignature.kt`, traducida
  de shazamio 0.4, MIT, con la banda de 3,5–5,5 kHz corregida como en SongRec) y se manda a
  `amp.shazam.com` como hace su app (`net/Shazam.kt`). **No es una API pública**: si Shazam la
  cambia, la app sigue funcionando sin reconocimiento. El audio sale del render de ExoPlayer
  (`playback/AudioTap.kt`, un `TeeAudioProcessor`), sin segunda conexión, a 16 kHz mono.
  - Comprobado: la huella sale **idéntica byte a byte** a la de referencia en Python (audio
    sintético en las pruebas; una grabación de Cadena 100 fuera del repo). Toda la cadena con
    audio real a 44,1 kHz estéreo reconoce la canción, y el `offset` cuadra a 0,03 s.
  - El `offset` de Shazam es el segundo de la canción en que **empieza** el trozo (medido).
  - Cuándo pregunta (`NowPlaying.wantsAutoIdentify`), **sólo con la app a la vista**: al cambiar
    el título, con 12 s de la canción nueva, para situarla y aprender `title_lag`; al sintonizar
    a mitad; si el título se queda (duración + 20 s); si la emisora no manda títulos. Sin
    respuesta, espera 30 s, 1, 2, 4… hasta 5 min. El botón de la onda pregunta siempre.
  - Con Shazam situando la canción, el ajuste manual es de esa canción y vuelve a ±0 en la
    siguiente; sin Shazam, es de la emisora y se guarda.
  - La ráfaga de audio atrasado al conectar no desplaza nada: la toma marca el audio con el
    tiempo de stream, que sale de la posición de ExoPlayer.
  - Pendiente de medir en el móvil: el audio llega a la toma un poco antes de oírse (el búfer del
    AudioTrack, unos 0,1–0,3 s); no se corrige.

- **Coche y pantalla de bloqueo** (0.3.1, `playback/SessionPlayer.kt`, un `ForwardingPlayer` que
  es lo que ve la sesión; el servicio sigue hablando con ExoPlayer): el tiempo es **el de la
  canción** (`NowState.progress`: con `clock.exact` y duración de LRCLIB o iTunes; si no, posición
  −1 y sin duración, en vez del tiempo desde que se puso la emisora), sin poder arrastrar, y
  **⏮ ⏭ cambian a la emisora de al lado** de «Mis Emisoras», dando la vuelta. En la app, la misma
  posición con una barra ondulada (`ui/SongProgress.kt`, idea de Musicolet: onda hasta donde va,
  recta lo que falta). Sin probar en el coche: no hay DHU en esta máquina.

- **Corazón en el reproductor** (0.3.2, Bruno lo echaba de menos): bajo el título de la canción,
  la marca como favorita en el historial (la misma marca que allí, `FavoriteButton`). Sin entrada
  en el historial (un título ignorado) no sale.

Falta:
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
