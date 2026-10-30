// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.5.0" apply false
    // AGP 9 has Kotlin built in (no kotlin-android plugin), needs this plugin for a Kotlin Multiplatform library, and replaces kapt with the legacy-kapt plugin.
    id("com.android.kotlin.multiplatform.library") version "9.5.0" apply false
    id("com.android.legacy-kapt") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("com.github.triplet.play") version "4.1.1" apply false
    // iOS spike (Stage 0): the shared module is Kotlin Multiplatform with kotlinx.serialization.
    id("org.jetbrains.kotlin.multiplatform") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.room") version "2.8.5" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Android Studio Kotlin DSL IDE Sync Workaround
if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel")
}
