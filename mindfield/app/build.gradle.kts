plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rajan.mindfield"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rajan.mindfield"
        minSdk = 26
        targetSdk = 36
        // Every CI build gets a higher version so Android installs it as an update over the last one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    // One fixed key (the same test key as the Meditation Timer, so the same SHA-1 is registered
    // with Google): each update installs over the old app and keeps the journal, and Google
    // sign-in, which is tied to the key's SHA-1, keeps working. A private key from GitHub secrets
    // wins when present. The committed key's password is the public Android debug default: it
    // identifies these builds, it protects nothing.
    val privateKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        create("fixed") {
            if (privateKeystore != null) {
                storeFile = privateKeystore
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "mindfield"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            } else {
                storeFile = file("mindfield-debug.keystore")
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
    testOptions {
        // Robolectric runs the real Android code (alarms, notifications, widget, screens) on the JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>().configureEach {
    // Roborazzi: write screenshots of the real screens for review instead of comparing.
    systemProperty("roborazzi.test.record", "true")
    systemProperty("mindfield.assets", file("src/main/assets").absolutePath)
    maxHeapSize = "3g"
    // Print why a test failed in the CI log, not only in the HTML report.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
    // The pure-Kotlin tests use org.json outside Robolectric, where android.jar only has stubs.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
