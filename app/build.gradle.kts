// app/build.gradle.kts
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// F53d (2026-08-17): carga keystore.properties para configurar la firma de
// release. El archivo esta en .gitignore (NO commitear passwords).
// Si NO existe (CI sin secrets, o build sin firma), usamos una firma debug
// placeholder y el build NO falla. Esto permite builds locales sin keystore
// para validar que el codigo compila.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("app/keystore.properties")
    if (f.exists()) {
        load(FileInputStream(f))
    } else {
        logger.warn("app/keystore.properties no existe. La firma de release usara la debug keystore (NO recomendada para prod).")
    }
}

android {
    namespace = "cl.csae.pos"
    // SDK 36 = Android 16 (Baklava), ultima estable al 2026-08-02.
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.csae.pos"
        minSdk = 26
        targetSdk = 36
        // F4.5 (2026-08-13): concatenar todos los ByteArrays en un unico
        // write + delay(150ms) post-flush. El patron anterior (N writes
        // consecutivos) hacia buffering por write en algunos BluetoothSocket
        // y la PPT305BT recibia los bytes incompletos o fuera de orden.
        // Ademas: verificacion de `s.isConnected` antes de escribir.
        //
        // F21 (2026-08-13): nuevo endpoint backend
        // `GET /api/v1/pos/comensales/{rut}/servicios-disponibles?fecha=...`
        // que el mobile consulta al seleccionar un comensal y despues de
        // cada ticket. SIEMPRE ve el estado actual de la BD, no el cache
        // local. El user reporto que seguia viendo servicios ya consumidos.
        // Cambios mobile:
        // - PosDtos.kt: nuevo ComensalServiciosDisponiblesResponseDto,
        //   ServicioDisponibleItemDto, ComensalNoEncontradoResponseDto.
        // - PosApiService.kt: nuevo metodo `serviciosDisponibles(rut, fecha)`.
        // - CatalogRepository.kt: nuevo metodo
        //   `buscarComensalServiciosFrescos(rut, fecha)` que llama al
        //   endpoint nuevo y sincroniza el cache local.
        // - TotemScreen.kt y POSScreen.kt: usan el metodo nuevo en vez
        //   de `buscarComensal` (que usaba cache local).
        // - ConsumoRepository.kt: despues de un consumo OK, refresca el
        //   cache del comensal via el endpoint nuevo.
        versionCode = 28
        // F55c: auto-imprimir tambien en modo Totem (kiosko).
        versionName = "0.9.16-f60-device-rut-clp"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // URL del API configurable por buildType.
        buildConfigField("String", "API_BASE_URL", "\"https://csae-dev-api.azurewebsites.net/\"")
    }

    // F53d (2026-08-17): signing config de release, leida de keystore.properties.
    // Se declara ANTES de buildTypes porque el DSL de AGP evalua en orden y
    // buildTypes.release.signingConfig referencia signingConfigs.getByName("release").
    signingConfigs {
        create("release") {
            if (rootProject.file("app/keystore.properties").exists()) {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // storeFile es path RELATIVO a app/ (donde esta el build.gradle.kts).
                // Tipico: keystore/csae-pos-release.keystore.
                val storeFileRel = keystoreProperties.getProperty("storeFile")
                if (storeFileRel != null) {
                    storeFile = file(storeFileRel)
                }
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            // F53c (2026-08-17): habilitar R8 minify + shrink resources.
            // Antes isMinifyEnabled=false -> APK release pesaba ~22MB
            // (igual que debug). Con minify+shrink baja a ~10-12MB y
            // obfuscate protege contra decompile trivial. Las reglas
            // estan en app/proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // F53d (2026-08-17): firmar con el keystore de release si
            // keystore.properties existe. Si no, fallback a la debug
            // keystore (util para CI que valida que el codigo compila
            // sin necesidad del secret).
            signingConfig = if (rootProject.file("app/keystore.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    // ML Kit barcode scanning â€” variante UNBUNDLED (play-services-mlkit).
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

    // F4.4 (2026-08-13): SDK vendor de la impresora REMOVIDO. Volvimos
    // al patron BluetoothSocket + UUID SPP estandar (ver PrinterService.kt)
    // que SÃ funciona con la PPT305BT del casino Demo. El SDK vendor
    // (posprinterconnectandsendsdk.jar) usaba un UUID RFCOMM interno que
    // no matcheaba con la impresora, por eso connectBtPort retornaba
    // onfailed. El JAR en app/libs/ ya no se compila (deja de estar en
    // .gitignore tambien en este commit).

    // Tests
    testImplementation("junit:junit:4.13.2")
    // F56 (2026-08-17): MockWebServer para tests del TokenAuthenticator
    // y AuthRepository.refreshToken. 4.12.0 = misma version que OkHttp
    // de runtime, asi que las clases internas (Challenge, Handshake) son
    // compatibles.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // F56: coroutines-test para `runTest` y `TestScope` en los tests
    // del refresh proactivo del AuthRepository.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
