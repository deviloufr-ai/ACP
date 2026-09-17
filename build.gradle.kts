// Top-level build file — declares plugin versions shared by all modules.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.x moves the Compose compiler into its own Gradle plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
