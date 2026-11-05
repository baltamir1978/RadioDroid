# kotlinx.serialization trae sus reglas en el AAR; OkHttp y Media3 también.

# Glance crea las acciones de los widgets por su nombre de clase.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
