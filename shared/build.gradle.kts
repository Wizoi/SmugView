plugins {
    id("org.jetbrains.kotlin.multiplatform")
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
configurations.matching { it.name in setOf("kspJvm", "kspIosArm64", "kspIosSimulatorArm64") }.all {
    project.dependencies.add(name, "androidx.room:room-compiler:2.8.5")
}

// iOS spike (Stage 0, step 3): a Kotlin Multiplatform module that `:app` does not depend on yet. The iOS targets are declared here and
// compiled on a macOS runner; on Windows only the JVM target builds and runs its tests.
kotlin {
    jvm()
    // The static framework Xcode will link (Stage 3); built here so CI can measure its size and link time (Stage 0, step 5).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("androidx.room:room-runtime:2.8.5")
            implementation("androidx.sqlite:sqlite-bundled:2.6.2")
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

// On a clean machine the iOS KSP tasks started before Gradle had downloaded the iOS libraries ("KLIB resolver: Could not find
// room-common-iosSimulatorArm64Main-2.7.1.klib", seen on the first three macOS CI runs). Resolving the klib configurations first downloads them.
val resolveIosKlibs by tasks.registering {
    doLast {
        listOf(
            "iosSimulatorArm64CompileKlibraries", "iosSimulatorArm64TestCompileKlibraries",
            "iosArm64CompileKlibraries", "iosArm64TestCompileKlibraries"
        ).forEach { name -> configurations.findByName(name)?.files }
        // What was resolved and whether each file is really on disk: read by the CI diagnostics step.
        val lines = listOf("iosSimulatorArm64CompileKlibraries").flatMap { name ->
            (configurations.findByName(name)?.files ?: emptySet()).filter { "room" in it.name || "sqlite" in it.name }
                .map { "$name: ${it.path} exists=${it.exists()}" }
        }
        layout.buildDirectory.file("resolved-klibs.txt").get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n"))
    }
}
tasks.matching { it.name.startsWith("ksp") && it.name.contains("Ios") }.configureEach { dependsOn(resolveIosKlibs) }
