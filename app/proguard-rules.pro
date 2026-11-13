# kotlinx.serialization trae sus reglas en el AAR; OkHttp y Media3 también.

# Glance crea las acciones de los widgets por su nombre de clase.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# WorkManager (lo trae Glance) crea su base de datos de Room por reflexión, y la regla de Room
# («-keep class * extends RoomDatabase») no nombra el constructor: desde AGP 9 R8 ya no lo guarda
# por su cuenta y la app moría al arrancar (0.3.8).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
