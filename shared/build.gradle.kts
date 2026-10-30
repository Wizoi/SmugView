plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Room's compiler runs through KSP once per target. A target that cannot be built on this machine (iOS on Windows) may have no
// configuration, so this attaches the compiler to whichever of them exist.
configurations.matching { it.name in setOf("kspJvm", "kspAndroid", "kspIosArm64", "kspIosSimulatorArm64") }.all {
    project.dependencies.add(name, "androidx.room:room-compiler:2.8.5")
}

// iOS spike (Stage 0, step 3): a Kotlin Multiplatform module that `:app` does not depend on yet. The iOS targets are declared here and
// compiled on a macOS runner; on Windows only the JVM target builds and runs its tests.
kotlin {
    jvm()
    // Stage 1: the Android app consumes the shared code through this target. The app is built for Java 17, and inline functions from
    // this library must not be built for a newer target.
    androidLibrary {
        namespace = "com.smugview.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("androidx.room:room-runtime:2.8.5")
            implementation("androidx.sqlite:sqlite-bundled:2.6.2")
            api("io.ktor:ktor-client-core:3.0.3")
            // The Compose runtime only (state holders such as mutableStateOf in the view-model layer); the screens stay in the app.
            implementation(compose.runtime)
            api(compose.ui) // ImageVector (the icons) and the other graphics types the shared UI state uses
            // The multiplatform ViewModel and SavedStateHandle (the base of SmugViewModel).
            api("androidx.lifecycle:lifecycle-viewmodel:2.10.0")
            api("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.10.0")
            implementation("androidx.room:room-paging:2.8.5")
            api("androidx.paging:paging-common:3.5.3")
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
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.13.0")
            implementation("io.ktor:ktor-client-okhttp:3.0.3")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.0.3")
        }
    }
}

// The shared tests run on the JVM target and on the iOS simulator, with the same sources. The Android target has no test actuals of its own
// (Room's Android builder needs a Context), and a root `testDebugUnitTest` would otherwise try to compile them.
tasks.matching { it.name.contains("UnitTest") }.configureEach { enabled = false }
