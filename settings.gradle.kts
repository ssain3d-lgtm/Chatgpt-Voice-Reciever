import java.io.File
import java.util.Properties

// Google's Maven only serves Android/AndroidX/Google artifacts. Restricting it by
// group means Kotlin, JUnit and Picovoice are never looked up there: fewer requests,
// faster resolution, and no spurious failures when that host is unreachable while the
// module being built (`:core`) does not need it at all.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "chatgpt-voice-receiver"

// ---------------------------------------------------------------------------
// :core is pure Kotlin/JVM (ARCHITECTURE.md §3.1) and needs no Android SDK.
// :app needs the Android SDK and the Android Gradle Plugin.
//
// If no Android SDK is reachable we include :core only, so that the pure-Kotlin
// contracts and their unit tests can still be compiled and run (for example in a
// sandbox or CI image without the SDK). On any normal developer machine — or any
// machine with ANDROID_HOME / local.properties `sdk.dir` — :app is included and
// the build behaves exactly like a standard Android project.
//
// Force inclusion with:  ./gradlew -Paura.forceAndroidModule=true <task>
// ---------------------------------------------------------------------------
include(":core")

val localProperties = Properties().apply {
    val f = settingsDir.resolve("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

val androidSdkDir: String? = sequenceOf(
    localProperties.getProperty("sdk.dir"),
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
).firstOrNull { !it.isNullOrBlank() && File(it).isDirectory }

val forceAndroidModule =
    startParameter.projectProperties["aura.forceAndroidModule"]?.toBoolean() ?: false

if (androidSdkDir != null || forceAndroidModule) {
    include(":app")
} else {
    println(
        """
        |
        |  ============================================================================
        |   No Android SDK found — the :app module is EXCLUDED from this build.
        |
        |   Looked at: local.properties `sdk.dir`, ANDROID_HOME, ANDROID_SDK_ROOT.
        |
        |   Only :core (pure Kotlin/JVM) is available, so `assembleDebug` and `lint`
        |   will NOT exist. `:core:test` still runs.
        |
        |   To build the Spike APK, install the Android SDK and either export
        |   ANDROID_HOME or add `sdk.dir=/path/to/Android/Sdk` to local.properties.
        |  ============================================================================
        |
        """.trimMargin()
    )
}
