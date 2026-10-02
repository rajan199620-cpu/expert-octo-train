import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin/JVM: the card parser and layout rules, shared by the phone and watch apps.
// Kept free of Android so it can be tested on any JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Randomised suites scale with -Pstress.iterations=N; failures print their seed.
    systemProperty("stress.iterations", (findProperty("stress.iterations") ?: "2500").toString())
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
