plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.dshpm.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.dshpm.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.4.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX minimal set — just enough to host a Compose surface.
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.0")

    // Compose (versions via BOM; compiler ext pinned above to match Kotlin 1.9.22).
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // Pure-JVM protocol module (AND2-3): frames + WS client; api-exposes
    // kotlinx-serialization-json for JsonObject parsing in this module too.
    implementation(project(":proto"))

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    // Integration tests (real gateway) read this; default "false" keeps them skipped.
    systemProperty(
        "dshpm.integration",
        providers.environmentVariable("DASHPM_INTEGRATION")
            .orElse(providers.gradleProperty("integration"))
            .orElse("false"),
    )
}
