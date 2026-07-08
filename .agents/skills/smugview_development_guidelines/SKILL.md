---
name: smugview_development_guidelines
description: Workspace-specific development guidelines, dependency rules, and bug/compilation mitigations for the SmugView Android project.
---

# SmugView Development Guidelines & Compiling Mitigations

This workspace customization guides the development, dependency management, and compilation practices for the **SmugView** Android project.

---

## 👥 Project Personas & Team Roles

To ensure the SmugView application is designed with high visual quality, outstanding user experience, and a robust, reliable architecture, the project utilizes the following roles and personas:

### 1. The UX/UI Designer
*   **Role**: Lead Product Designer
*   **Background**: 10+ years of experience designing web applications and highly interactive image-sharing platforms.
*   **Mindset**: Strongly aligned with a Gen-Z aesthetic and user habits. Focuses on immersive, visually rich, and dynamic layouts, custom animations, sleek dark modes, and seamless transitions.
*   **Responsibilities**:
    *   Craft a premium look and feel that instantly engages the user (avoiding standard or boring default UI elements).
    *   Design interactive image galleries and gesture-driven details.
    *   Iterate on interactive flow prototypes that match modern consumption behaviors.

### 2. The UX Reviewer
*   **Role**: Senior UX & Usability Reviewer
*   **Background**: A veteran UX designer with extensive experience in customer support, user scenario deep analysis, and end-to-end workflow design.
*   **Mindset**: Pragmatic, detail-oriented, and highly user-centric. Focuses on clarity, consistency, and accessibility across all user cohorts.
*   **Responsibilities**:
    *   Review all designs proposed by the Lead Designer to ensure they are simple, concise, and useful.
    *   Analyze extreme user scenarios (e.g., poor connectivity, password-protected galleries, empty search results).
    *   Streamline workflows to minimize clicks and eliminate user confusion.

### 3. The Android Platform Expert
*   **Role**: Lead Android Developer
*   **Background**: Deep technical expertise in the Android platform, Kotlin, Jetpack Compose, and native app performance optimizations.
*   **Mindset**: Focused on building clean, high-performance, and idiomatic Android applications. Follows standard Google design guidelines while supporting advanced custom components.
*   **Responsibilities**:
    *   Implement the UI precisely as designed, choosing the optimal tools (Jetpack Compose, Material 3, Custom Canvas/Layouts if needed).
    *   Establish MVVM architecture, Room database caching, and high-performance image loading (using Coil).
    *   Write clean, modularized, and testable code.

### 4. The Platform Reviewer
*   **Role**: Senior Platform Architect & API Specialist
*   **Background**: Senior platform engineer who can write Android code but specializes in 3rd party API integrations, remote client architectures, and middle-layer design.
*   **Mindset**: Highly sensitive to API limits, network reliability, serialization pitfalls, and performance bottlenecks.
*   **Responsibilities**:
    *   Ensure robust integration with the SmugMug API v2.
    *   Design a resilient middle-layer communication layer (Repository pattern) that handles caching, paginated feeds, and error propagation cleanly.
    *   Review API usage patterns to prevent rate limiting, handle offline modes gracefully, and avoid security vulnerabilities (such as API key exposure).

---

## 🛠️ Project Incompatibilities & Mitigation Registry

The following compile-time and Gradle sync issues were discovered during initial project configuration and resolved with the documented mitigations:

### 1. Gradle Repositories Settings Conflict
*   **Error**: `Build was configured to prefer settings repositories over project repositories but repository 'Google' was added by build file 'build.gradle.kts'`
*   **Root Cause**: In `settings.gradle.kts`, `dependencyResolutionManagement.repositoriesMode` was set to `FAIL_ON_PROJECT_REPOS`. The top-level `build.gradle.kts` contained an `allprojects { repositories { ... } }` block which attempted to inject repository configurations, violating this mode.
*   **Mitigation**: Declare all build repositories (e.g., `google()`, `mavenCentral()`) solely inside `settings.gradle.kts` under `dependencyResolutionManagement`. Keep `build.gradle.kts` files free of `allprojects` repository blocks.

### 2. Gradle Version Method Signature Mismatch
*   **Error**: `Unable to find method 'org.gradle.api.file.FileCollection org.gradle.api.artifacts.Configuration.fileCollection(org.gradle.api.specs.Spec)'`
*   **Root Cause**: The project was missing `gradle-wrapper.properties`. Android Studio defaulted to running its internal Gradle 9.x daemon. However, AGP 8.2.2 uses an older Configuration signature which was deprecated and removed in Gradle 9.x.
*   **Mitigation**: Explicitly create and commit `gradle-wrapper.properties` specifying a compatible Gradle version (like `Gradle 8.5`). Provide `gradlew.bat` to ensure developer builds run on the identical version outside the IDE.

### 3. Kotlin DSL Plugin Resolution & IDE Sync Failures
*   **Error**: `Task 'prepareKotlinBuildScriptModel' not found in project ':app'`
*   **Root Cause**: In Gradle 8.5+ with Kotlin DSL, Android Studio runs a tooling API sync that expects the `:app:prepareKotlinBuildScriptModel` task to exist. If the plugins block structure changes or caches are out of sync, this task fails to register, crashing the IDE sync.
*   **Mitigation**: 
    1. Declare all Gradle plugins and their versions inside the root `build.gradle.kts` using the `plugins { ... }` block with `version` and `apply false` (e.g. `id("com.android.application") version "8.2.2" apply false`). Apply them versionless inside `app/build.gradle.kts` using the native `plugins { ... }` block to preserve type-safe Kotlin DSL accessors (like `implementation`) while ensuring correct classpath visibility.
    2. Register a dummy task conditionally: `if (tasks.findByName("prepareKotlinBuildScriptModel") == null) { tasks.register("prepareKotlinBuildScriptModel") }` at the bottom of both the root `build.gradle.kts` and `app/build.gradle.kts` to allow Android Studio to bypass the check and complete its project synchronization without throwing a duplicate task exception.

### 4. API Paginated Feeds & UI Thread Locking (ANR)
*   **Error**: Application Not Responding (ANR) and app frozen when streaming high-speed paginated search/image API flows to Compose state.
*   **Root Cause**: Flow collector updates Compose states page-by-page as fast as pages load. Under fast connections or large page sets, Compose is backlogged with layout and recomposition tasks, blocking the Main Thread.
*   **Mitigation**: Always apply the `.conflate()` operator on the repository Flow collection inside the ViewModel scope to skip intermediate state updates if the UI thread is busy rendering the previous page.

### 5. Duplicate Compose Grid Keys (Crash)
*   **Error**: `java.lang.IllegalArgumentException: Key X was already used. If you are using LazyColumn/Row please make sure you provide a unique key for each item.`
*   **Root Cause**: Setting stable Compose keys (`imageKey`) when mapping items from dynamic or global search APIs. If the API returns a photo that appears in multiple directories or is duplicated, Compose encounters duplicate keys and crashes instantly.
*   **Mitigation**: Always deduplicate fetched API data lists (e.g. using `.distinctBy { it.imageKey }`) before assigning them to states used by lazy item layout builders, or append the index to the key (e.g. `${imageKey}_${index}`).

### 6. Caching of HTTP Error Responses (Rate Limits / Timeout Blocks)
*   **Error**: Custom OkHttp cache interceptors cause temporary errors (like 429 Too Many Requests or 503 Gateway Timeout) to persist indefinitely even after server-side limits reset.
*   **Root Cause**: Overriding response headers (`Cache-Control` header to public, max-age) for all GET requests without checking `response.isSuccessful`. As a result, OkHttp caches HTTP error codes on local disk.
*   **Mitigation**: Always ensure that header-rewriting network interceptors check `response.isSuccessful` prior to applying caching parameters.

### 7. Coroutine Concurrency Throttling (API Rate Limits)
*   **Error**: Rapidly hitting 429 Too Many Requests rate-limiting blocks during parallelized flows.
*   **Root Cause**: Launching many concurrent network requests inside `coroutineScope` with short/insufficient delays (e.g., 50ms) when fetching large sets of pages, exceeding the server's maximum request rate.
*   **Mitigation**: Limit parallel execution using a Coroutine `Semaphore` (e.g. `Semaphore(3)`) combined with a wider stagger delay (e.g., `250L` milliseconds multiplied by pageIndex) to safely throttle throughput below the API threshold.

### 8. SmugMug Global Search "Searchable: No" Restrictions
*   **Error**: Empty Folders, Galleries (Albums), and Photos tabs when running unscoped global text search query via API, despite matching items existing on the account.
*   **Root Cause**: SmugMug's global search engine completely excludes directories and images marked with `"Searchable: No"` from the account's unscoped global index.
*   **Mitigation**:
    1. For Folders & Galleries: Query the local SQLite cache database `cached_nodes` table using `title LIKE %query%` and combine it with the API search results.
    2. For Photos: Query the local database for matching galleries, and execute a scoped `searchImagesUser` API query for each gallery by passing the gallery's node URI as the `scope` parameter (e.g., `scope = gallery.uri`). This searches unsearchable gallery content by targeting the specific container explicitly.

### 9. Batch Scoped Search HTTP 429 Rate Limiting
*   **Error**: `retrofit2.HttpException: HTTP 429 Too Many Requests` during search execution when crawling multiple matched galleries.
*   **Root Cause**: Executing scoped searches sequentially or concurrently across multiple matching directories without request pacing delays, exceeding maximum burst API request frequency.
*   **Mitigation**: Introduce a pacing delay of at least `250ms` (e.g., `delay(250)`) between sequential calls, and wrap queries in an automatic retry loop that detects `429` errors, sleeps for `1500ms`, and retries up to 3 times.

### 10. Android Log Mocking Failure during JVM Unit Tests
*   **Error**: `java.lang.RuntimeException: Method d in android.util.Log not mocked.` when executing repository tests.
*   **Root Cause**: Android's static `Log` class is part of the Android SDK platform, which is not available/mocked on standard JVM unit test execution runs by default.
*   **Mitigation**: Add `testOptions { unitTests { isReturnDefaultValues = true } }` inside `app/build.gradle.kts` to allow Android classes to return default values instead of throwing stub exceptions.

---

## 💻 Kotlin & Compose Compilation Rules

Adhere to these rules when writing user interface screens:

### 1. Compose Icon Imports (Extension Resolution)
In Jetpack Compose, icons (like `Search`, `Lock`, `PhotoAlbum`) are implemented as **Kotlin extension properties** on the `Icons.Default` / `Icons.Filled` objects.
*   **Rule**: You *must* explicitly import the extension property (e.g., `import androidx.compose.material.icons.filled.Search`) even when using the fully qualified parent path (`Icons.Default.Search`). Failure to import the extension property will cause an `Unresolved reference: Search` compile-time error.

### 2. Default Parameters Type Declarations
*   **Rule**: When declaring default parameters inside Composable functions, always write the type annotation explicitly. Write `modifier: Modifier = Modifier` instead of `modifier = Modifier`. Writing only the value causes the compiler to throw `A type annotation is required on a value parameter`.

### 3. Column Layout Alignment
*   **Rule**: For `Column(horizontalAlignment = ...)`, always use `Alignment.CenterHorizontally` (not `Alignment.CenterAlignment` or `Alignment.Center` which are for `Box` layouts).

### 4. Modifier Extension Imports
*   **Rule**: Compose modifier extensions (such as `.border()` or `.clickable()`) require explicit foundation package imports (e.g. `import androidx.compose.foundation.border` and `import androidx.compose.foundation.clickable`). Add them manually when missing.

### 5. Compose Dialog & DialogProperties Imports
*   **Rule**: Compose dialog features reside in `androidx.compose.ui.window.Dialog` and `androidx.compose.ui.window.DialogProperties`. Avoid duplicate/ambiguous imports in the import statements.

### 6. Experimental API Opt-in Propagation
*   **Rule**: When using experimental APIs like `FlowRow` or experimental animation APIs, ensure the `@OptIn` annotations (e.g. `ExperimentalLayoutApi::class`) are added directly to the parent Composable screens that invoke them.

### 7. Explicit Coroutines Imports for Scope Builders
*   **Rule**: When introducing or utilizing coroutine builders/operators (e.g. `launch`, `channelFlow`, `conflate`, `stateIn`), always explicitly add the correct package imports (e.g. `import kotlinx.coroutines.launch` or `import kotlinx.coroutines.flow.channelFlow`). Do not assume they are resolved globally or automatically, as missing imports cause compile-time unresolved references and confusing "suspension functions can be called only within coroutine body" errors.

---

## 📱 Advanced Compose & Media Playback Guidelines

### 1. Box Layout Order (Z-Index / Drawing Layer)
*   **Rule**: Compose draws children of a `Box` in order of declaration. Always declare the scrolling list/grid container *first* (bottom-most layer) and sticky overlays or floating control buttons *last* (top-most layer) to keep overlays visible and interactive above the scroll content.

### 2. ExoPlayer CDN Redirection & Authentication (HTTP 403/401)
*   **Rule**: When streaming videos from private/secured CDNs, configure `DefaultHttpDataSource.Factory` with a custom `User-Agent` and call `setAllowCrossProtocolRedirects(true)`. Append necessary query parameters (like `APIKey` and password tokens) using Android's native `Uri` builder prior to initiating playback.

### 3. Material 3 Chip Border Type Mismatches
*   **Rule**: Instantiating borders using standard Compose `BorderStroke(width, color)` directly (e.g., `BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))`) to avoid type mismatches with `ChipBorder` and `BorderStroke?` across different Material 3 dependency releases.

---

## 🧠 Kotlin, Room & State Hoisting Mechanics

### 1. Kotlin Smart Casts on Properties with Custom Getters
*   **Rule**: Kotlin does not allow smart-casting nullable variables that have custom getters (like calculated properties or delegated properties) because they can change values between reads. Copy the property value to a local variable (e.g., `val localValue = myObject.myProperty`) before performing null checks.

### 2. SmugMug EXIF API Key Names
*   **Rule**: The SmugMug EXIF metadata endpoint (`image/{image_key}!metadata`) returns separate `"Make"` and `"Model"` keys. Model your `ExifData` class with `@SerializedName("Make")` and `@SerializedName("Model")`, then expose a custom calculated `val camera: String?` getter to combine them dynamically:
    ```kotlin
    val camera: String?
        get() {
            val maker = make?.trim() ?: ""
            val mdl = model?.trim() ?: ""
            return when {
                maker.isNotEmpty() && mdl.isNotEmpty() -> {
                    if (mdl.lowercase().contains(maker.lowercase())) mdl else "$maker $mdl"
                }
                maker.isNotEmpty() -> maker
                mdl.isNotEmpty() -> mdl
                else -> null
            }
        }
    ```

### 3. Password-Protected Sub-resource Queries
*   **Rule**: Direct API calls to sub-resources (like `image/{image_key}!metadata`) within password-locked albums require passing the `Password` parameter query. Ensure the repository's `getImageExif` signature accepts `password: String? = null` and forwards it as a `@Query("Password")` parameter in the Retrofit endpoint definition.

### 4. State Hoisting Scope Boundaries
*   **Rule**: Hoist shared overlay states (like detailed image view overlay toggles or folder dialog triggers) to the parent screen container (e.g., `BrowserScreen.kt`) and pass modification callbacks down to child tabs to avoid accessibility/responsiveness issues.

### 5. Encapsulated Room Database/Repository Access
*   **Rule**: Create explicit delegate suspension methods inside the repository class (e.g., `suspend fun getCollectionById(id: Long): OfflineCollection? = dao.getCollectionById(id)`) to safely expose DAO methods without exposing the private `dao` database property directly.

### 6. Kotlin Property Delegates Import Resolution
*   **Rule**: Using Compose delegated properties (`var stateVar by remember { mutableStateOf(...) }`) requires both `import androidx.compose.runtime.setValue` and `import androidx.compose.runtime.getValue` to compile. Add these imports explicitly when using `by` delegation.

---

## ⚙️ Emulator & CLI Execution Environment

### 1. CLI JDK Toolchain Setup
*   **Error**: `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.`
*   **Root Cause**: The gradle script cannot find the Java runtime executable on the host system.
*   **Rule**: Point the shell `JAVA_HOME` environment variable to the bundled JetBrains Runtime (JBR) inside Android Studio (located at `C:\Program Files\Android\Android Studio\jbr`) before initiating command-line builds:
    ```powershell
    $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
    .\gradlew.bat installDebug
    ```

### 2. ADB Command Execution (Command Not Found)
*   **Error**: `adb : The term 'adb' is not recognized as the name of a cmdlet, function, script file, or operable program.`
*   **Root Cause**: Android platform tools are not added to the system `PATH` environment variable.
*   **Rule**: Locate and reference the `adb.exe` executable using the local AppData Android SDK path. Define the `$env:ANDROID_HOME` variable and execute ADB commands using the call operator `&` in PowerShell:
    ```powershell
    $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
    & "$env:ANDROID_HOME\platform-tools\adb.exe" devices
    ```

### 3. Emulator PM Forking Issues
*   **Rule**: If the emulator's process table becomes full or corrupt, APK installation fails with `can't fork - try again`. Resolve by issuing an ADB reboot and checking if the boot completes:
    ```powershell
    $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
    & "$env:ANDROID_HOME\platform-tools\adb.exe" reboot
    & "$env:ANDROID_HOME\platform-tools\adb.exe" shell getprop sys.boot_completed
    ```

---

## 🧠 Key Technical Skills & Learnings Checklist

The following technical skills and architectural learnings were established during the development of this Android application:

1.  **State Hoisting in Multi-Tab UIs**: Mutating or displaying shared views (e.g. details dialog overlays, bookmark popups) across different tab screens requires hoisting the control states (`selectedImageForDetail`, `showFolderBookmarkDialog`) to the parent container level (`BrowserScreen.kt`) and passing modification lambdas down.
2.  **Room Entity Tree Modeling**: Structured hierarchical databases inside Room supporting custom offline shortcuts (Folders, Galleries, and Photos) mapped via relational entity tables (`CollectionBookmark` and `OfflineCollection`).
3.  **Active Cache Management**: Do NOT clear Coil's image disk cache in `onDestroy()`. Android destroys and recreates the Activity on screen rotation, so calling `diskCache?.clear()` on destroy forces every visible photo to re-download after a rotation. The correct approach is to let the Coil singleton (configured in `SmugViewApp`) manage cache lifecycle automatically. The `onDestroy()` cache-clearing behavior was identified as a regression bug and was explicitly removed from `MainActivity`.
4.  **Flexible Retrofit Sub-resource Injections**: Appending custom query and header extensions (e.g., passing verified folder passwords downstream to API requests like `image/{key}!metadata?Password=xxx`) to unlock secure resource metadata.
5.  **Multi-Tier Async Fallback Loading**: Constructing resilient loaders using Jetpack Compose state steps to load files sequentially from multiple networks/CDNs before showing text/letter avatar fallbacks.
6.  **Adaptive Columns & Aspect Ratios**: Scaling staggered and grid columns dynamically in response to orientation transitions, and adjusting header height coordinates proportionally.
7.  **Dynamic Bottom Navigation Styling**: Overriding static BottomBar templates to render live state-flow attributes, including disabling elements conditionally, modifying labels dynamically, and rendering custom circular icons.
8.  **Compose Graphics Bounds Validation (NaN prevention)**: Always verify layout/dimension pixel bounds (e.g. `headerHeightPx > 0f`) before dividing by them in calculations for alpha, scale, or translation effects. Division by zero yields `NaN`, which aborts the OpenGL/Compose render thread, leading to a blank white screen.
9.  **Parallel Agent Tool Batching**: Group all independent file reads (`view_file`), workspace search calls (`grep_search`), and command checks into a single turn's parallel execution block to prevent turn latency and control context token blowup.
10. **Crawl Budget and Password Prioritization**: When executing local fallback scoped searches across cached directories, implement a hard crawl cap (e.g. 40 albums) to prevent hitting rate limits (HTTP 429), and sort password-protected galleries first to maximize target coverage since public galleries are already resolved by unscoped global API calls.
11. **Sequential Search-Cache Boundaries**: Run remote node/gallery search sequentially before executing scoped photo searches to ensure the cache is fully populated with matching directories, avoiding race conditions that cause zero image results.
12. **Filename Decoding & Sanitization for Native Storage**: Decode resource segments using `URLDecoder.decode` prior to extracting base names and sanitizing illegal filesystem characters to prevent download file corruption on local drives.


