# =============================================================================
# CSAE POS - ProGuard / R8 rules
# =============================================================================
# F53c (2026-08-17): reglas para que R8 minifique sin romper la app.
# Probado con: AGP 8.9, Kotlin 2.2.20, Compose 1.8.x, R8 default optimizer.
#
# REGLA GENERAL: solo agregar -keep cuando R8 rompa algo. Empezar sin
# reglas custom y agregar si falla. Hoy el proyecto es chico (~20 archivos
# .kt) y la mayoria de las libraries son compatibles con R8 out-of-the-box.
# =============================================================================

# -----------------------------------------------------------------------------
# Codigo propio (cl.csae.pos.**)
# -----------------------------------------------------------------------------
# ServiceLocator: el inyector manual. Se accede desde composables por nombre
# (ServiceLocator.printerService, etc). Si R8 lo renombra, los call sites
# compilan igual porque es referencia directa, pero las strings de log y los
# nombres de los services se pierden. Mantenemos para debug.
-keep class cl.csae.pos.di.ServiceLocator { *; }

# Composables: R8 ya maneja @Composable correctamente desde Kotlin 2.0+.
# NO necesita -keep.

# ViewModels: en este proyecto no usamos Hilt ni AndroidX ViewModel
# (los screens tienen estado interno con remember). Si en el futuro se
# agregan, agregar:
# -keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# Modelos DTO (@Serializable de kotlinx-serialization): ver la seccion de
# kotlinx-serialization mas abajo. NO requieren -keep explicito si las
# reglas de kotlinx-serialization estan activas.

# WorkManager: el proyecto NO usa WorkManager (F50d+F50e no se mergeo al
# master). Si en el futuro se agrega, ver reglas de WorkManager abajo.

# -----------------------------------------------------------------------------
# kotlinx-serialization (JSON via @Serializable)
# -----------------------------------------------------------------------------
# Mantiene los companion objects de las clases @Serializable y la metadata
# del $serializer. Sin esto, deserialization falla con
# "Serializer for class X is not found".
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Aplica a todos los DTOs del proyecto.
-keep,includedescriptorclasses class cl.csae.pos.**$$serializer { *; }
-keepclassmembers class cl.csae.pos.** {
    *** Companion;
}
-keepclasseswithmembers class cl.csae.pos.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# -----------------------------------------------------------------------------
# Retrofit + OkHttp
# -----------------------------------------------------------------------------
# Mantiene los metodos de las interfaces (los param names los usa Retrofit
# para mapear @Query/@Path/@Body).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
# OkHttp interno.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# -----------------------------------------------------------------------------
# DataStore (Preferences)
# -----------------------------------------------------------------------------
# DataStore es reflection-friendly, no necesita reglas especiales en general.
# Si rompe, agregar:
# -keep class androidx.datastore.*.** { *; }

# -----------------------------------------------------------------------------
# Coroutines
# -----------------------------------------------------------------------------
# Coroutines DebugProbes se inicializa con ServiceLoader, no necesita reglas.
# Pero stack traces pueden perder nombres sin esto.
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepclassmembers class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keepclassmembers class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-dontwarn kotlinx.coroutines.flow.**inlined**

# -----------------------------------------------------------------------------
# Compose
# -----------------------------------------------------------------------------
# Compose Compiler genera codigo que R8 maneja sin reglas extra. Solo
# mantener la annotation de preview.
-keep class androidx.compose.ui.tooling.preview.Preview { *; }

# -----------------------------------------------------------------------------
# CameraX
# -----------------------------------------------------------------------------
# CameraX 1.4.0 ya es R8-friendly. Solo evitar warnings.
-dontwarn com.google.auto.value.AutoValue
-dontwarn androidx.camera.camera2.**.Camera2Config

# -----------------------------------------------------------------------------
# ML Kit Barcode Scanning (unbundled via play-services-mlkit)
# -----------------------------------------------------------------------------
# ML Kit unbundled: el modelo se descarga de Google Play Services en runtime,
# R8 no incluye el .so. No necesita reglas especiales.
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_barcode.**

# -----------------------------------------------------------------------------
# ZXing (QR code generation)
# -----------------------------------------------------------------------------
# ZXing core: mantiene los BarcodeFormat enum y los writer internals.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# -----------------------------------------------------------------------------
# Coil (image loading)
# -----------------------------------------------------------------------------
# Coil 2.7.0 usa ksp-less reflection minima. Sin reglas.
-dontwarn coil3.**

# -----------------------------------------------------------------------------
# BCrypt / BCrypt.Net (no aplica en Android — solo en backend .NET)
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Suppress warnings benignos
# -----------------------------------------------------------------------------
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.lang.model.**
-dontwarn java.lang.management.**
