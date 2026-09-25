// Top-level build file — declares plugin versions shared by all modules.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    // Kotlin 2 moves the Compose compiler into its own Gradle plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    // The phone link's shared protocol module (:link) is plain Kotlin/JVM.
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}
