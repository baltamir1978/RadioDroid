# RadioDroid

En el móvil se llama **FMDroid**. Radio por internet con letra sincronizada, widgets y Android Auto.

Versión para Android de [RadioApp](https://github.com/baltamir1978/RadioApp), la app de iOS. No es un port:
se escribe desde cero, con la app original como especificación.

**Estado: 0.3.10** (13-nov-2026): radios y aspecto probados en el móvil; Android Auto, Shazam y
lo último, todavía no. Ver `CLAUDE.md`.

## Qué hace

- Radio en segundo plano, con controles y carátula en la pantalla de bloqueo, y **Android Auto**.
  En el coche y en la pantalla de bloqueo, el tiempo es el de la canción (cuando se sabe por
  dónde va) y ⏮ ⏭ cambian a la emisora de al lado.
- Tus emisoras (añadir, editar, importar y exportar el JSON de MacRadio y LinRadio) y búsqueda en
  **Radio Browser**.
- La canción que suena, con su carátula (iTunes) y su **letra sincronizada** (LRCLIB). Tocar una
  línea sincroniza la letra; − / + / ↺ la ajustan, y el ajuste se recuerda por emisora. Una barra
  ondulada dice por dónde va la canción.
- **Reconoce lo que suena con Shazam**, sin cuenta ni ShazamKit: sitúa la letra al segundo,
  aprende cuánto tarda cada emisora en cambiar el título y nombra las canciones de las emisoras
  que no lo dicen. Usa la misma petición que la app de Shazam, que no es pública: si cambia,
  deja de reconocer y lo demás sigue igual.
- **Aguanta los cortes de red** (pensada para 5G en movimiento): reconecta sola.
- Si se van el coche o los auriculares, **calla**; tras una llamada, **sigue sola**.
- **Historial** con favoritas (también con el corazón del reproductor, de la notificación y de Android Auto), «borrar para siempre» para los eslóganes, y búsqueda en YouTube.
- **Widgets**: Emisora, Letra (pequeño y mediano) y accesos a Emisoras.
- Plegable abierto o tableta: reproductor y letra lado a lado.
- Accesos directos y enlaces `radiodroid://play?u=<url>&n=<nombre>`. Una emisora que no está en
  «Mis Emisoras» pregunta antes de sonar, y otras apps sólo pueden poner las de tu lista.
- **De viva voz** («pon Radio 3» al asistente o en Android Auto): suena la emisora que mejor casa.
- Español, inglés, alemán, francés y portugués. Sin cuentas ni analítica.

## Pila

Kotlin + Jetpack Compose, Media3 (ExoPlayer), Jetpack Glance. Para **Android 12 o posterior**,
también en **GrapheneOS** (sin los servicios de Google).

## Compilar (Linux)

```sh
Tools/instalar-entorno.sh          # JDK 17, Android SDK y Gradle en ~/Android, sin sudo
./gradlew testDebugUnitTest lintDebug   # pruebas del núcleo y lint
./gradlew assembleRelease               # app/build/outputs/apk/release/app-release.apk
```

Hace falta clonar también [droid-common](https://github.com/baltamir1978/droid-common) **en la
carpeta de al lado** (`../droid-common`): el código que comparten las apps de Android, que Gradle
incluye desde ahí. En cada push, GitHub Actions pasa las pruebas y el lint
(`.github/workflows/android.yml`).

La release se firma con una clave que vive fuera del repositorio (`~/.android/claves/`); sin
ella sale sin firmar. Para instalar: copiar el APK al móvil y abrirlo, o
`adb install -r app/build/outputs/apk/release/app-release.apk`.
