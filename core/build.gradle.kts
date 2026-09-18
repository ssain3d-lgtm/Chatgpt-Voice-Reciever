import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// ARCHITECTURE.md §3.1 / INV: `core` is pure Kotlin. No `android.*`, no AndroidX.
// It must compile and unit-test on a plain JVM so that (a) the Spike contracts can
// be tested without a device and (b) Windows Phase 2 can reuse it (ADR-010).
//
// Bytecode target is pinned to 17 (not a toolchain) so the build does not need to
// provision a second JDK; 17 is what AGP 8.x expects from a consumed JVM library.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
