import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// SECURITY_PRIVACY.md §9 / P-5: the Picovoice AccessKey never enters the repository.
// It comes from local.properties (git-ignored) or the environment. Absent is a normal
// state: the app must build and run, and report "Porcupine key missing" in the UI.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun secret(vararg keys: String): String =
    keys.firstNotNullOfOrNull { k ->
        (localProperties.getProperty(k) ?: System.getenv(k))?.trim()?.takeIf { it.isNotEmpty() }
    } ?: ""

android {
    namespace = "com.ssain3d.gptvoicereceiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ssain3d.gptvoicereceiver"

        // ANDROID_CONSTRAINTS.md §8: Mode P needs EXTRA_AUDIO_SOURCE,
        // EXTRA_SEGMENTED_SESSION and checkRecognitionSupport — all API 33. Targeting
        // Galaxy One UI 7/8 (Android 15/16), so 33 costs nothing and keeps the S-2
        // probe free of version branches on its primary path.
        minSdk = 33

        // ANDROID_CONSTRAINTS.md §12 requires targetSdk >= 34 for the Samsung FGS
        // guarantee. 35 = Android 15.
        targetSdk = 35

        versionCode = 1
        versionName = "0.1.0-spike"

        buildConfigField(
            "String",
            "PICOVOICE_ACCESS_KEY",
            "\"${secret("PICOVOICE_ACCESS_KEY", "PORCUPINE_ACCESS_KEY")}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // The Spike ships as a debug APK (brief §15). Release is left unshrunk so
            // that an accidental release build still produces something diagnosable.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // A lint nit must not block a device-test session. The HTML/XML report is
        // still produced at app/build/reports/lint-results-debug.html — read it there.
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    // ADR-002: the initial wake-word candidate. The architecture does not depend on
    // it — it sits behind core's WakeWordEngine and the app runs without it.
    // Licensing/pricing: MUST BE VERIFIED AGAINST CURRENT PICOVOICE TERMS (R-13).
    implementation(libs.porcupine.android)
}
