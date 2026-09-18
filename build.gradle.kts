// Root build script.
//
// Deliberately does NOT declare the Android Gradle Plugin with `apply false`:
// that would resolve the AGP artifact even for builds that only touch :core.
// Each module declares the plugins it needs.

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
