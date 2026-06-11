// :checks — the check executors (http, tcp, dns, ping). The `ping` check is a
// traceroute that needs the ICMP error queue (recvmsg/MSG_ERRQUEUE), absent
// from android.system.Os before API 33 — hence the native engine under
// src/main/cpp (built with the NDK + CMake; see README § Building).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.meshcheck.checks"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    // r27+ links .so segments 16KB-aligned by default (Play requirement).
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 21

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_PLATFORM=android-21"
            }
        }
        ndk {
            // Real ARM phones (incl. 32-bit for minSdk 21) + 64-bit emulators;
            // 32-bit x86 is dropped.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
