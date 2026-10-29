plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// iOS spike (Stage 0, step 3): a Kotlin Multiplatform module that `:app` does not depend on yet. The iOS targets are declared here and
// compiled on a macOS runner; on Windows only the JVM target builds and runs its tests.
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("io.ktor:ktor-client-core:3.0.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            implementation("io.ktor:ktor-client-mock:3.0.3")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.0.3")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.0.3")
        }
    }
}
