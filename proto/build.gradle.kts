plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

// Intentionally no dependencies: this module is a pure-JVM protocol
// surface shared by the future frame codec (AND2-3) and the app.
