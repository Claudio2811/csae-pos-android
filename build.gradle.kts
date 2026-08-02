// Top-level build file
// AGP 8.9.2 = ultima estable al 2026-08-02, soporta SDK 36 nativamente.
// Kotlin 2.1.20 = ultima estable 2.1.x, soporta JDK 21. (Kotlin 2.2.20 soporta
// jvmTarget 25 pero Gradle 8.14 no soporta JDK 25, asi que quedamos en 21.)
plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
}
