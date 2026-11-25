// A second Health Connect data source for development and the instrumented suite: an app of
// its own, under its own package, that writes records the way a watch or scale app would. Only
// ever built as a debug APK; it is not part of any release and never goes to a store.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set("1.5.0")
    android.set(false)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

android {
    namespace = "com.owen282000.lifedashboard.fixture"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.owen282000.lifedashboard.fixture"
        // Health Connect is part of the platform from API 34, which is also the lowest the
        // suite's AVD may run; the fixture has no reason to support anything older.
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.add("-opt-in=androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi")
    }
}

dependencies {
    implementation(libs.androidx.health.connect)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
