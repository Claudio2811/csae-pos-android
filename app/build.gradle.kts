// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "cl.csae.pos"
    // SDK 36 = Android 16 (Baklava), ultima estable al 2026-08-02.
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.csae.pos"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.7.4-sprint34-fechas"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // URL del API configurable por buildType.
        buildConfigField("String", "API_BASE_URL", "\"https://csae-dev-api.azurewebsites.net/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        // JDK 25 = ultimo estable al 2026-08-02 (sept 2025). No es LTS pero
        // Kotlin 2.2.20+ lo soporta (jvmTarget 25, parse 25.0.x). Compatible con AGP 8.9+.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // F7+ (2026-08-12): forzar jniLibs no comprimidas (16 KB page-aligned
        // compatible). AGP 8.5+ ya pone useLegacyPackaging=false por default
        // para apps targetSdk >= 23, pero lo seteamos explicito para que:
        // 1. Sea claro en el build file que esto es intencional.
        // 2. Proteja contra cambios futuros del default.
        // 3. La verificacion `unzip -l app.apk | grep .so | grep -v Storbed`
        //    da vacio (todas las .so quedan STORED, no DEFLATED), que es lo
        //    que Google Play exige a partir de Feb 1 2027 para dispositivos
        //    con 16 KB page size.
        jniLibs {
            useLegacyPackaging = false
        }
    }
    lint {
        // Sprint 3.2: CameraX 1.3.x marca `imageProxy.image` como opt-in
        // experimental. La API se va a estabilizar en 1.4+. Mientras tanto
        // lo tratamos como warning para no bloquear el build.
        disable += setOf("UnsafeOptInUsageError")
        abortOnError = false
    }
}

dependencies {
    // Compose BOM 2025.05.00 = ultima estable al 2026-08-02.
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines 1.10.x = compatible con Kotlin 2.1.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Serializacion JSON (kotlinx.serialization es mas rapido y no requiere reflection).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // DataStore (preferencias para JWT).
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Retrofit + OkHttp (cliente HTTP + interceptor de JWT).
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // CameraX 1.4.0+ = 16 KB page-aligned native libraries (cumple Google Play
    // 16 KB page size requirement, deadline Feb 1 2027). 1.3.4 tenia
    // libimage_processing_util_jni.so con LOAD segments a 4 KB.
    // (Sprint F7, 2026-08-11)
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // ML Kit barcode scanning — variante UNBUNDLED (play-services-mlkit).
    // Antes usabamos com.google.mlkit:barcode-scanning:17.3.0 (bundled) que
    // incluye libbarhopper_v3.so en el APK con LOAD segments a 4 KB.
    // La variante unbundled descarga el modelo desde Google Play Services en
    // runtime, NO incluye el .so en el APK. Mismo API
    // (com.google.mlkit.vision.barcode.*), 0 cambios de codigo.
    // (Sprint F7, 2026-08-11)
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // ZXing core para generar QR como Bitmap (preview del ticket).
    implementation("com.google.zxing:core:3.5.3")

    // Coil (Sprint F18.3, 2026-08-11): AsyncImage para descargar el logo
    // del casino cuando esta en https://... (Azure Blob Storage). 2.7.0
    // = ultima estable al 2026-08-02. 16 KB page-aligned (cumple Google
    // Play 16 KB page size requirement, deadline Feb 1 2027).
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
