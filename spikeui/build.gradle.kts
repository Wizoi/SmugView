plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Compose Multiplatform screens and the iOS framework. Kept apart from `:shared` because Compose Multiplatform 1.12 needs
// compileSdk 37 (AGP 9), which the Android app is not on, so `:shared` (which `:app` uses) must stay free of it. Stage 2 merges them again
// once the AGP migration is done. The one static framework, `Shared`, carries both modules.
kotlin {
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}
