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

// A fork made with GitHub's default "Copy the main branch only" has no tags, so its CI
// cannot describe a version (#94). There the version comes from version.properties, which
// prepare-release.sh keeps equal to the last tag, marked "-untagged" so such a build never
// passes for a release. Only this repository's own CI insists on the tag: here a missing
// tag means a shallow checkout, and a release must never be built from the fallback.
val versionProperties = providers.fileContents(rootProject.layout.projectDirectory.file("version.properties"))
    .asText.orNull
    ?.lineSequence()
    ?.mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 && !line.startsWith("#") } }
    ?.associate { (key, value) -> key.trim() to value.trim() }
    .orEmpty()
val isUpstreamCi = System.getenv("GITHUB_REPOSITORY") == "owen282000/life-dashboard-companion-app"

if (semverMatch == null && System.getenv("CI") != null && isUpstreamCi) {
    throw GradleException(
        "No semver tag (X.Y.Z) reachable from HEAD. CI builds require full git history: " +
        "use actions/checkout with fetch-depth: 0."
    )
}

val appVersionName = describedVersion
    ?: versionProperties["VERSION_NAME"]?.let { "$it-untagged" }
    ?: "0.0.0-dev"
val appVersionCode = semverMatch?.destructured?.let { (major, minor, patch) ->
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
} ?: versionProperties["VERSION_CODE"]?.toIntOrNull() ?: 1

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

        // Puts WorkManager in test mode before the application schedules anything; see the class.
        testInstrumentationRunner = "com.owen282000.lifedashboard.harness.LdTestRunner"
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
        // The Google Play build (P1-13): the release build without the donation row, which a
        // paid listing cannot carry. A build type of its own rather than a flavour, so every
        // existing task, output path and the F-Droid recipe (assembleRelease) stay exactly as
        // they are; src/play/res switches the row off. Same application id and signing key as
        // the release build. Build it with ./gradlew bundlePlay.
        create("play") {
            initWith(getByName("release"))
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

// The instrumented suite validates every payload it receives against docs/webhook-schema.json.
// The file is copied into the test APK's assets at build time rather than kept as a second copy,
// so the documented schema stays the only one and a change to it is tested at once.
abstract class CopyWebhookSchema : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val schema: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        schema.get().asFile.copyTo(File(out, "webhook-schema.json"), overwrite = true)
    }
}

val copyWebhookSchema = tasks.register<CopyWebhookSchema>("copyWebhookSchemaForAndroidTest") {
    schema.set(rootProject.layout.projectDirectory.file("docs/webhook-schema.json"))
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(copyWebhookSchema, CopyWebhookSchema::outputDir)
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
    implementation(libs.okhttp.coroutines)
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
    // WebhookRedirectTest: what goes over the wire when a receiver answers with a redirect.
    testImplementation(libs.okhttp.mockwebserver3)
    // The instrumented suite (scripts/instrumented.sh). None of this reaches a release build.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.okhttp.mockwebserver3)
    androidTestImplementation(libs.json.schema.validator)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
