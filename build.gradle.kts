// Root build file — plugin versions declared here (`apply false`), actually
// applied in app/build.gradle.kts. Keeps version numbers in exactly one place.
//
// No org.jetbrains.kotlin.android plugin: AGP 9.0+ has built-in Kotlin
// support and no longer wants it applied separately (it carries its own
// Kotlin Gradle Plugin dependency internally) — only the Compose compiler
// plugin still needs applying on top. See
// https://developer.android.com/build/releases/agp-9-0-0-release-notes#android-gradle-plugin-built-in-kotlin
plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
