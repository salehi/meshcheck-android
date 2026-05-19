// :checks — the check executors (http, tcp, dns).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.meshcheck.checks"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // api, not implementation: :checks exposes :core's ResultOutcome in
    // CheckResult's public API.
    api(project(":core"))

    implementation(libs.okhttp)
    implementation(libs.dnsjava)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // A real org.json for unit tests — Android's bundled one is stubbed there.
    testImplementation(libs.json)
}
