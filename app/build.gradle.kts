plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.minimx"
    // 34, not 36: AGP 8.5.1 is the newest Android Studio Koala accepts, and it does not
    // know about SDK 35+. This also pins Compose to the 1.6 line — anything newer is
    // built against SDK 35 and refuses to link.
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.minimx"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    // ponytail: foundation only. No material3 — this launcher has no ripples,
    // no elevation and no theme system, so the whole Material layer is dead weight.
    implementation("androidx.compose.foundation:foundation")
    // Google Fonts pulled at runtime through the Play Services provider — no font bytes
    // in the APK. Needs res/values/font_certs.xml to trust the provider.
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
