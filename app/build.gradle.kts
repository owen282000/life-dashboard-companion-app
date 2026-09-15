plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

// ktlint enforces a deliberately narrow rule set, configured in .editorconfig.
//
// The full set flags 4,276 violations across 82 files, almost entirely argument wrapping and
// trailing commas. Auto-formatting that would rewrite every file, destroy git blame for the
// whole codebase and change no behaviour, which is a bad trade while F-Droid is reviewing this
// very code. What remains enforced is the part that catches mistakes: unused imports, wildcard
// imports outside the packages listed in .editorconfig, import order and stray whitespace.
ktlint {
    version.set("1.5.0")
    // Off on purpose: it layers a rule set of its own over .editorconfig.
    android.set(false)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

// The latest semver git tag (X.Y.Z, no prefix) is the single source of truth for the app
// version. Tagging a release is the only version bump needed; CI enforces tag validity.
// providers.exec rather than ProcessBuilder: reading process output directly at configuration
// time makes the build unfit for Gradle's configuration cache, because the result cannot be
// tracked as an input. This form is cache-correct and behaves identically, including the
// empty-output and non-zero-exit cases that a repository without tags produces.
fun runGit(vararg args: String): String? {
    val result = providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue != 0) return null
    return result.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}

val semverRegex = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
val baseVersionTag = runGit("describe", "--tags", "--match", "[0-9]*.[0-9]*.[0-9]*", "--abbrev=0")

// When HEAD is exactly a release tag, the version name is that tag, no matter what the
// working tree looks like. F-Droid's builder modifies the tree before building (it strips
// signing configs and removes gradle-wrapper.jar), and with --dirty that produced
// "1.12.1-dirty" in the manifest, breaking the reproducible-build comparison against the
// released APK. Between tags, dev builds keep the descriptive --dirty form.
val exactTag = runGit("describe", "--tags", "--match", "[0-9]*.[0-9]*.[0-9]*", "--exact-match")
val describedVersion = exactTag
    ?: runGit("describe", "--tags", "--match", "[0-9]*.[0-9]*.[0-9]*", "--dirty")
val semverMatch = baseVersionTag?.let { semverRegex.find(it) }

if (semverMatch == null && System.getenv("CI") != null) {
    throw GradleException(
        "No semver tag (X.Y.Z) reachable from HEAD. CI builds require full git history: " +
        "use actions/checkout with fetch-depth: 0."
    )
}

val appVersionName = describedVersion ?: "0.0.0-dev"
val appVersionCode = semverMatch?.destructured?.let { (major, minor, patch) ->
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
} ?: 1

android {
    namespace = "com.owen282000.lifedashboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.owen282000.lifedashboard"
        minSdk = 26
        // Play requires API 35 to stay available to new users, and API 36 for new apps and
        // updates from 31 August 2026. compileSdk is already 37.
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Signing is driven by environment variables so CI can sign via GitHub Secrets
            // while local builds without a keystore stay unsigned. See docs/KEYSTORE_SETUP.md.
            val keystorePath = System.getenv("KEYSTORE_PATH")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAliasEnv = System.getenv("KEY_ALIAS")
            val keyPasswordEnv = System.getenv("KEY_PASSWORD")
            if (keystorePath != null && keystorePassword != null && keyAliasEnv != null && keyPasswordEnv != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }
    dependenciesInfo {
        // AGP embeds a dependency-tree blob in the APK signing block, encrypted with a
        // Google public key: only Google can read it and nobody can verify its contents.
        // IzzyOnDroid and F-Droid flag it, and it adds nothing outside Google Play.
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            // R8 shrinks and optimises; the keep rules in proguard-rules.pro cover the
            // reflection-heavy parts (HiveMQ/Netty, kotlinx.serialization). Asked for by
            // F-Droid during review; mapping.txt lands in app/build/outputs/mapping/release.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // The HiveMQ MQTT client bundles Netty, whose jars ship duplicate metadata files
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.health.connect.client.ExperimentalDeduplicationApi",
            "-opt-in=androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.okhttp)
    implementation(libs.hivemq.mqtt)
    implementation(libs.kotlinx.serialization.json)

    // Scanning a pairing QR code. The camera is only ever started from the scanner
    // screen, and the permission is asked for there, never at startup.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
