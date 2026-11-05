# RadioDroid

En el móvil se llama **FMDroid**. Radio por internet con letra sincronizada, widgets y Android Auto.

Versión para Android de RadioApp (repositorio privado), la app de iOS. No es un port:
se escribe desde cero, con la app original como especificación.

**Estado: primera versión (0.1), sin probar aún en un móvil** (5-nov-2026). El reconocimiento
con Shazam está pendiente. Ver `CLAUDE.md`.

## Qué hace

- Radio en segundo plano, con controles y carátula en la pantalla de bloqueo, y **Android Auto**.
- Tus emisoras (añadir, editar, importar y exportar el JSON de MacRadio y LinRadio) y búsqueda en
  **Radio Browser**.
- La canción que suena, con su carátula (iTunes) y su **letra sincronizada** (LRCLIB). Tocar una
  línea sincroniza la letra; − / + / ↺ la ajustan, y el ajuste se recuerda por emisora.
- **Aguanta los cortes de red** (pensada para 5G en movimiento): reconecta sola.
- Si se van el coche o los auriculares, **calla**; tras una llamada, **sigue sola**.
- **Historial** con favoritas, «borrar para siempre» para los eslóganes, y búsqueda en YouTube.
- **Widgets**: Emisora, Letra (pequeño y mediano) y accesos a Emisoras.
- Plegable abierto o tableta: reproductor y letra lado a lado.
- Accesos directos y enlaces `radiodroid://play?u=<url>&n=<nombre>`.
- Español, inglés, alemán, francés y portugués. Sin cuentas ni analítica.

## Pila

Kotlin + Jetpack Compose, Media3 (ExoPlayer), Jetpack Glance. Para **Android 12 o posterior**,
también en **GrapheneOS** (sin los servicios de Google).

## Compilar (Linux)

```sh
Tools/instalar-entorno.sh          # JDK 17, Android SDK y Gradle en ~/Android, sin sudo
./gradlew testDebugUnitTest        # pruebas del núcleo
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
```

La release se firma con una clave que vive fuera del repositorio (`~/.android/claves/`); sin
ella sale sin firmar. Para instalar: copiar el APK al móvil y abrirlo, o
`adb install -r app/build/outputs/apk/release/app-release.apk`.
