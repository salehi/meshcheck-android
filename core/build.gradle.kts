// :core — Wire-generated protocol message types and (later) on-device crypto.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.wire)
}

android {
    namespace = "io.meshcheck.core"
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

// Generate Kotlin message types from the vendored, pinned protocol schema.
// proto/agent.proto must not be edited in this repo — see CLAUDE.md.
wire {
    kotlin {}
    sourcePath {
        srcDir("${rootProject.projectDir}/proto")
    }
}

dependencies {
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
}
