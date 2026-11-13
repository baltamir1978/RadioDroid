import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "es.altamirano.fmdroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "es.altamirano.fmdroid"
        minSdk = 31          // Android 12
        targetSdk = 36
        versionCode = 13
        versionName = "0.3.9"
    }

    // La clave de release vive fuera del repositorio (ver Tools/instalar-entorno.sh y CLAUDE.md).
    // Sin ella, la release sale sin firmar y la de depuración sigue funcionando.
    val keyFile = File(System.getProperty("user.home"), ".android/claves/fmdroid-release.properties")
    val releaseKey = if (keyFile.exists()) Properties().apply { keyFile.inputStream().use { load(it) } } else null
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = File(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    // Leer `Locale.getDefault()` o un texto con `context.getString` al pintar no se entera de un
    // cambio de idioma en caliente; aquí da igual, porque al cambiar el idioma Android recrea la
    // actividad. Avisos, no errores.
    lint { warning += setOf("NonObservableLocale", "LocalContextGetResourceValueCall") }

    buildTypes {
        release {
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Media3 toca clases de Android al cargar `Player.Commands`: en las pruebas del JVM, que no fallen.
    testOptions { unitTests { isReturnDefaultValues = true } }
    buildFeatures { compose = true }
}

dependencies {
    implementation("es.altamirano:common")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    testImplementation(libs.junit)
}
