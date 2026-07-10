# SmugView Developer Rules & Environment Settings

## ⚙️ Gradle & Compilation Environment
*   **Java JDK / Runtime Location**: The default shell does not have `JAVA_HOME` or the `java` binary in its `PATH`. Always use the Android Studio bundled JetBrains Runtime (JBR) for executing Gradle commands:
    ```powershell
    $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
    ./gradlew compileDebugKotlin
    ```

## 🎨 Compose & UI Layout Guidelines
*   **Flow Layouts**: Standard `FlowRow` is available under `androidx.compose.foundation.layout.FlowRow` and requires `@OptIn(ExperimentalLayoutApi::class)`.
*   **Conditional Border Modifiers**: When applying conditional borders in layouts, use direct conditional modifiers instead of referencing properties on a nullable `BorderStroke`:
    ```kotlin
    Modifier.then(if (isSelected) Modifier.border(1.dp, NeonBlue, shape) else Modifier)
    ```

## 🤝 Parallel Workspace Coordination
*   **Coordination**: Since there may be concurrent developer agents working on the codebase, always check the git status and look up the logs in the sibling agent directories:
    ```
    C:\Users\kidzi\.gemini\antigravity-ide\brain\<sibling-conversation-id>\.system_generated\logs\transcript.jsonl
    ```
    Ensure you do not regress their features when making changes to shared files like `BrowserScreen.kt` or `SmugViewModel.kt`.

## 🚀 SmugMug API Optimization Guidelines
*   **AlbumKeywords Expansion**: To fetch all keywords for an album without downloading heavy image payloads, use `GET album/{albumKey}?_expand=AlbumKeywords&_filter=Uri&_verbosity=1`.
*   **Multi-Get/Batch Queries**: You can query keywords for multiple albums at once by joining their keys with commas: `GET album/id1,id2,id3`.
*   **URL Length & Chunking**: To avoid HTTP client or server-side URL length limit rejections, limit multi-get requests to a maximum of 25-30 album keys. Use chunking (e.g. `albumList.chunked(25)`) to process larger collections.
*   **Dynamic On-Demand Loading**: Avoid loading full image sets for an entire folder scope upfront. Query the lightweight keywords first to render the UI (like Tag Clouds), and only fetch matching gallery images on-demand when a specific tag is selected.
*   **Security/Password Grouping**: When querying password-protected galleries in batches, group the galleries by their saved/unlocked passwords and execute one batch query per unique password.
*   **Search Filter Payload Shrink**: Optimize the `_filter` query parameter on search endpoints to omit large fields (`ArchivedUri`, `WebUri`, `OriginalWidth`/`OriginalHeight`/`OriginalSize`, and the nested `Uris` dictionary). This shrinks payloads by 60-70%; detail views must load these fields dynamically on-demand via the single image endpoint.
*   **Retrofit Query Encoding**: Do not manually URL-encode parameters passed to `@Query` annotations in Retrofit interfaces. Retrofit automatically encodes these parameters; manually doing so leads to double encoding (e.g. `%` to `%25`) and server lookup failures.

## 🔍 Search & Indexing Optimization (Searchable: No / HTTP 429)
*   **Local Caching Fallback for Folders/Galleries**: SmugMug's global `node!search` hides nodes marked with `"Searchable: No"`. To bypass this, always combine global API node search results with a local database query of `cached_nodes` (`title LIKE %query%`).
*   **Tokenized Tag Cloud Local Filtering**: When filtering search results by tag cloud selections locally, split multi-word tags into tokens and match them against tokenized sets of image keywords, titles, captions, and filenames (removing non-alphanumeric punctuation). This prevents empty states on assets without explicit keywords that matched the API text query.

## ☕ Kotlin & Generic Type Compiler Mitigations
*   **Map .forEach Ambiguity**: Avoid calling `.forEach` on a `Map` within coroutines or asynchronous contexts, as the compiler can fail with generic type inference or overload resolution ambiguity errors. Instead, use standard Kotlin `for ((key, value) in map)` loops:
    ```kotlin
    for ((key, value) in myMap) { ... }
    ```
*   **FlatMap Fallback Type Resolution**: When calling `.flatMap` on collections, using a generic `emptyList()` as a fallback can cause `Not enough information to infer type variable T` errors. Always specify the type parameter explicitly on the fallback list:
    ```kotlin
    val photos = matchingKeys.flatMap { loadedImages[it] ?: emptyList<AlbumImageData>() }
    ```

## 🤖 Antigravity Tool Usage Learnings
*   **Ripgrep Windows File Search**: On Windows systems, ripgrep (`grep_search`) might fail to find matches if `SearchPath` is set directly to a file containing backslashes. Instead, set `SearchPath` to the containing directory and filter using the `Includes` parameter with the filename (e.g., `Includes = ["BrowserScreen.kt"]`).
*   **Proactive Skill Verification**: Always check and view the `smugview_development_guidelines` SKILL.md file at the very start of any task or conversation. Never execute command-line tools (such as Gradle, ADB, or emulator binaries) without verifying the exact path, environment variables, or commands documented in the workspace guidelines first.

## 🔍 Scoped Search Learnings
*   **Search Scope Race Condition**: In `performSearch()`, avoid launching `searchNodesRemote` concurrently with `searchImages`. If run concurrently, the image search will read the database before the remote galleries are resolved and cached, finding 0 matching galleries and returning empty images. Run them sequentially (letting `searchNodesRemote` complete first) to ensure the cache is fully populated before searching images.
*   **Unit Test Speedup & Loop Constraints**: Do not loop over large album list counts (e.g., 100 albums) in live API unit tests. Limit loops verifying album details or scoped search flows to the first 1-2 galleries using `.take(2)`. Also, avoid redundant keyword checks in flow tests; use a single keyword (e.g., `"tahoma"`) to verify the end-to-end integration path cleanly and keep test runtimes under 1 minute.
*   **AlbumKeywords Expansion Map URI Parsing**: When reading expansions from `album/{albumKeys}?_expand=AlbumKeywords`, SmugMug maps expansions by the fully expanded sub-resource URI (e.g. `/api/v2/album/{key}!keywords`). To map this back to the clean album key correctly, clean the key by stripping everything after the `!` symbol (e.g., `uri.substringAfterLast("/").substringBefore("!")`). Failure to do this results in invalid lookup keys like `"key!keywords"`, failing downstream album resolution and photo grid binding.
*   **API Image Properties**: When interacting with the SmugMug API Image objects, use `FileName` to get the actual file name, and `OriginalSize` to get the raw byte size of the image.
*   **Album Key Helper Consistency (`getAlbumKey`)**: Always use the helper method `node.getAlbumKey()` instead of inline calculations like `node.albumUri?.substringAfterLast("/") ?: node.nodeId`. The helper method handles stripping sub-resource suffixes (e.g. `!keys` or `!images`), avoiding key mismatches in downstream database lookups or API calls. Avoid writing inline substring logic to prevent parallel agent refactoring thrashing.

## 🛡️ Antigravity Workspace Tool Boundaries
*   **Internal Brain Directory Access**: Do NOT use the native `list_dir`, `grep_search`, or `view_file` tools to search or traverse the root `.gemini/antigravity-ide/brain` directory. This violates a hardcoded system protection boundary and will fail with `Permission denied`. To read sibling agent logs, use terminal commands (like `Get-ChildItem` and `Get-Content` in PowerShell) or access the exact conversation paths explicitly provided in your environment variables or rules.

## 🔍 Bug Investigation & Resolution Guidelines
*   **Evidence-Based Reporting**: Before proposing or making code changes to fix any bug, always compile and provide a detailed evidence-based analysis report containing relevant logcat traces, databases/state snapshots, and actual API query inputs/outputs, along with recommendations for user review.
*   **Stage-by-Stage Verification & Raw Payload Inspection**: When analyzing API discrepancies (e.g. between a browser request and the mobile client), never rely solely on parsed application models or memory state objects, which assume successful serialization. Fetch and inspect the raw HTTP response payloads anonymously (simulating the client) and check for visibility toggles (such as `"ShowKeywords": false`) or server-side redactions that explain the behavior.

## 🧪 Test Architecture & Code Quality Guidelines
*   **Startup & UI Thread Room Queries**: Any Room database query executed during application startup, view initialization, or UI rendering must use targeted index-based queries (e.g. `getNodeByIdOrKey` or matching primary keys). Unbounded queries (such as `getAllCachedNodes()`) are strictly prohibited in loops or init blocks to prevent Main-thread blockages and database lock congestion (ANRs).
*   **Mock Verification of API Visibility Rules**: Mocks and unit test fixtures simulating API responses must model the actual visibility rules of the SmugMug API. Ensure fields that are redacted under anonymous states (like `Keywords` or `expansions` on locked categories) are explicitly verified in tests simulating anonymous vs. unlocked states.



