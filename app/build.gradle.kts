// :app — the Compose UI, the foreground service, and the DI wiring.
import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.meshcheck.contributor"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.meshcheck.contributor"
        minSdk = 21
        targetSdk = 35
        // Version is the single source of truth in the git tag: the release
        // workflow derives it from the tag (debug-X.Y.Z / release-X.Y.Z) and
        // passes -PversionName / -PversionCode, so the built APK's version
        // always equals the tag. Local builds fall back to these defaults.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key for now: enrollment is still stubbed,
            // so nothing is shipped to Play yet — this just makes the release
            // APK installable for local testing. Swap for a real release
            // keystore (signingConfigs + keystore.properties) before publishing.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":protocol"))
    implementation(project(":checks"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.work.runtime)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}

// Move each variant's APK into the top-level dist/ once it is assembled. It is
// a move, not a copy: build/outputs holds the APK only transiently, then it
// lands in dist/ — exactly one copy on disk. dist/ is gitignored. Because the
// APK is moved out of the packaging task's output, that task re-runs on the
// next build (compilation/dexing stay cached), which is the intended cost.
androidComponents {
    val distDir = rootProject.layout.projectDirectory.dir("dist").asFile
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        val moveApk = tasks.register("move${capitalized}ApkToDist") {
            description = "Moves the ${variant.name} APK into dist/ (leaves no copy in build/)."
            group = "distribution"
            inputs.dir(apkDir)
            outputs.dir(distDir)
            // The source is moved away each run, so never report up-to-date.
            outputs.upToDateWhen { false }
            doLast {
                val source = apkDir.get().asFile
                distDir.mkdirs()
                val apks = source.listFiles { f -> f.isFile && f.extension == "apk" }.orEmpty()
                if (apks.isEmpty()) throw GradleException("No APK produced for ${variant.name}")
                apks.forEach { src ->
                    val target = distDir.resolve(src.name)
                    if (target.exists()) target.delete()
                    if (!src.renameTo(target)) {
                        // Fallback for a cross-filesystem move.
                        src.copyTo(target, overwrite = true)
                        src.delete()
                    }
                    println("dist: ${target.name}")
                }
            }
        }
        // assemble<Variant> is created by AGP after this callback runs, so
        // look it up lazily and wire the finalizer when it is realized.
        tasks.matching { it.name == "assemble$capitalized" }
            .configureEach { finalizedBy(moveApk) }
    }
}
