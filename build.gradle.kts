// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.kapt") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    id("com.google.dagger.hilt.android") version "2.55" apply false
    id("com.github.triplet.play") version "3.12.0" apply false
    // iOS spike (Stage 0): the shared module is Kotlin Multiplatform with kotlinx.serialization.
    id("org.jetbrains.kotlin.multiplatform") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10" apply false
    id("com.google.devtools.ksp") version "2.1.10-1.0.31" apply false
    id("androidx.room") version "2.7.1" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Android Studio Kotlin DSL IDE Sync Workaround
if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel")
}
