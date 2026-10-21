# RadioDroid — contexto del repositorio

Versión para Android de **RadioApp** (iOS), escrita **desde cero en Kotlin + Jetpack Compose**. Lo
común a las apps de Android (firma, `versionCode`, secretos, distribución, ShazamKit) está en el
`CLAUDE.md` de la carpeta de proyectos; aquí va lo propio de esta.

«RadioDroid» es el nombre de la carpeta y del repositorio. **En el móvil se llama FMDroid**
(Bruno, 21-oct-2026), porque ya hay una app de radio libre para Android llamada RadioDroid.

Tiene que funcionar en **GrapheneOS**, sin Google Play. Todo lo de esta app funciona así salvo
**Android Auto**, que en GrapheneOS necesita Google Play aislado y la app de Android Auto
instalados. Si no están, el resto de la app no debe notarlo.

## Estado (21-oct-2026)

**Sin empezar.** Va después de Letrasdroid, de la que reutiliza Shazam y LRCLIB. Conviene hacerla a
la vez que LinRadio (Linux), que comparte el diseño del motor.

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
