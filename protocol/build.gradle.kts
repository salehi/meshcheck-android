// :protocol — the agent WebSocket: handshake, heartbeat, task lifecycle.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.meshcheck.protocol"
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
    // api, not implementation: :protocol exposes :core types (the Wire-generated
    // protocol messages) in its public API.
    api(project(":core"))

    implementation(libs.okhttp)
    implementation(libs.conscrypt.android)
    // api: AgentClient.state exposes StateFlow in its public API.
    api(libs.kotlinx.coroutines.core)
}
