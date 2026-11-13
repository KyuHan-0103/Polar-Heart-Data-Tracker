plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.h10recorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.h10recorder"
        minSdk = 33          // required by Polar BLE SDK README ("minSdk 33 or higher")
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        // Polar SDK 8.x is compiled for Java 21
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }

    testOptions {
        unitTests {
            // Keep false: any accidental android.* call in JVM unit tests fails loudly
            // instead of silently returning 0/null.
            isReturnDefaultValues = false
            all { it.testLogging { events("passed", "skipped", "failed"); showStandardStreams = false } }
        }
    }
}

// Test code uses kotlinx-coroutines-test's runCurrent/advanceTimeBy (marked experimental).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("UnitTest") || name.contains("AndroidTest"))
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

dependencies {
    implementation(libs.polar.ble.sdk)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // JVM unit tests (app/src/test) - run on this Mac, no phone or H10 needed
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)   // PolarBleApi has ~60 abstract methods; mocking beats a hand-written fake

    // Instrumented tests (app/src/androidTest) - run on a phone, optionally with a real H10
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
