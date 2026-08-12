// Versions are capped by Android Studio Koala (241.x), which refuses to open a project
// on anything newer than AGP 8.5.x. AGP 8.5.1 in turn caps Gradle at 8.x and compileSdk
// at 34, which caps Compose at the 1.6 line. Bump all of it together after a Studio update.
plugins {
    id("com.android.application") version "8.5.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
