plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rajan.meditationtimer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rajan.meditationtimer"
        minSdk = 26
        targetSdk = 35
        // Every CI build gets a higher version so Android installs it as an update over the last one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    // One fixed key, so each update installs over the old app and keeps its history (a different
    // key forces an uninstall, which wipes the app's data), and so Google sign-in, which is tied
    // to the key's SHA-1, keeps working. A private key from GitHub secrets wins when present;
    // otherwise the committed test key is used. Its password is the public Android debug
    // default: it identifies these test builds, it protects nothing.
    val privateKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        create("fixed") {
            if (privateKeystore != null) {
                storeFile = privateKeystore
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "meditation"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            } else {
                storeFile = file("meditation-debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Google sign-in and the Drive permission for the private backup (Authorization API).
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    testImplementation("junit:junit:4.13.2")
}
