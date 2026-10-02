import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.github.triplet.play")
}

android {
    namespace = "com.smugview.app"
    compileSdk = 36

    // Read local.properties
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }

    defaultConfig {
        applicationId = "com.smugview.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 27
        versionName = "0.8.1"
        if (project.hasProperty("localBuild")) versionNameSuffix = "-local"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val apiKey = localProperties.getProperty("smugmug.api.key") ?: "YOUR_API_KEY_HERE"
        val nickname = localProperties.getProperty("smugmug.nickname") ?: "smugmug"

        buildConfigField("String", "SMUGMUG_API_KEY", "\"$apiKey\"")
        buildConfigField("String", "SMUGMUG_NICKNAME", "\"$nickname\"")
    }

    signingConfigs {
        val keystorePath = localProperties.getProperty("signing.storeFile")
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = localProperties.getProperty("signing.storePassword")
                keyAlias = localProperties.getProperty("signing.keyAlias")
                keyPassword = localProperties.getProperty("signing.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isCrunchPngs = false
        }
        release {
            // Re-enabled: the reflective Gson/Retrofit surfaces are preserved via explicit keep
            // rules in proguard-rules.pro. Do NOT disable these again to "fix" JSON parsing —
            // add the missing -keep rule for the affected model package instead.
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            val keystorePath = localProperties.getProperty("signing.storeFile")
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true // required by Robolectric
        }
    }
    // Room migration tests read the exported schema JSONs from this directory.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        // Phase 1c: Robolectric unit tests read assets from the *debug variant's* merged assets
        // (test_config.properties android_merged_assets = mergeDebugAssets), not from the "test"
        // source set, so the schemas go in the debug source set (~40 KB, debug APKs only).
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }
}

// Export Room schemas to app/schemas/ so migrations can be validated with MigrationTestHelper.
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    // AndroidX & Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.04.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Jetpack Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.room:room-paging:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Retrofit & Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Paging 3
    implementation("androidx.paging:paging-runtime-ktx:3.2.1")
    implementation("androidx.paging:paging-compose:3.2.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    kapt("androidx.hilt:hilt-compiler:1.1.0")

    // Hilt Dependency Injection
    val hiltVersion = "2.50"
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    kapt("com.google.dagger:hilt-compiler:$hiltVersion")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Encrypted storage for gallery passwords
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coil Image Loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Palette API for dynamic theme color extraction
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Media3 (ExoPlayer) for Video Playback
    val media3Version = "1.3.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Google Cast SDK Framework
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")

    // QR code generation for the "Share" dialogs (folder/gallery/photo link -> scannable code).
    // Just the barcode-writing core — no camera/scanning UI is needed for this.
    implementation("com.google.zxing:core:3.5.3")
    // Reads the Orientation of a photo before a share strips its Exif (6-14); also written by the test fixture.
    implementation("androidx.exifinterface:exifinterface:1.3.6")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Real org.json for unit tests (Android's bundled org.json is stubbed in JVM tests)
    testImplementation("org.json:json:20231013")
    // Robolectric so Room's real SQLite (and the recursive-CTE queries) run in JVM unit tests
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    // Phase 6-0: Compose UI tests under Robolectric (ui-test-manifest is already debugImplementation)
    testImplementation(platform("androidx.compose:compose-bom:2024.04.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // WorkManager test utilities (TestListenableWorkerBuilder) for the offline worker tests (phase 5)
    testImplementation("androidx.work:work-testing:2.9.0")
    // In-memory Room + migration testing (used by DAO and MigrationTestHelper tests)
    testImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.04.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Android Studio Kotlin DSL IDE Sync Workaround
if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel")
}

tasks.withType<Test> {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

tasks.register("testFullSuite") {
    group = "verification"
    description = "Runs all unit, functional, and instrumented integration tests in parallel."
    dependsOn("testDebugUnitTest", "connectedDebugAndroidTest")
}

tasks.register("testDebugSuite") {
    group = "verification"
    description = "Runs all offline unit, functional, and retry tests in parallel."
    dependsOn("testDebugUnitTest")
}

tasks.matching { it.name.startsWith("kapt") && it.name.endsWith("TestKotlin") }.configureEach {
    enabled = false
}

// R-51: keep each release's R8 mapping so a crash line from the diagnostics log can be
// deobfuscated later (retrace). release-mappings/ is git-ignored; the AAB also carries the mapping.
val archiveReleaseMapping by tasks.registering(Copy::class) {
    val mappingDir = rootProject.layout.projectDirectory.dir("release-mappings/${android.defaultConfig.versionCode}")
    val archived = mappingDir.file("mapping.txt").asFile
    from(layout.buildDirectory.file("outputs/mapping/release/mapping.txt"))
    into(mappingDir)
    doLast {
        if (!archived.isFile) throw GradleException("R8 mapping was not archived to $archived")
    }
}
tasks.matching { it.name == "minifyReleaseWithR8" }.configureEach {
    finalizedBy(archiveReleaseMapping)
}

play {
    serviceAccountCredentials.set(rootProject.file("play-service-account.json"))
    defaultToAppBundles.set(true)
    track.set("internal")
}

