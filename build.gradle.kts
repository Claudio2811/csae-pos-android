// Top-level build file
// AGP 8.9.2 = ultima estable al 2026-08-02, soporta SDK 36 nativamente.
// Kotlin 2.1.20 = soporta JDK 25 (Kotlin 2.0.21 tenia issues con 25.0.2).
// Compose plugin debe matchear la version de Kotlin.
plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
}
