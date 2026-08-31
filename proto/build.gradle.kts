plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // JSON runtime — codec is hand-written on top of JsonObject/JsonPrimitive
    // (no compiler plugin, no codegen) for strict frozen-spec field fidelity.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // WS transport — single jar, zero transitive deps; also ships a WebSocketServer
    // used by the mock-gateway unit tests (rationale in README).
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    // Integration tests (@Integration) read this; default "false" keeps them skipped.
    systemProperty(
        "dshpm.integration",
        providers.environmentVariable("DASHPM_INTEGRATION")
            .orElse(providers.gradleProperty("integration"))
            .orElse("false"),
    )
}
