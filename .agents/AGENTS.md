# SmugView Developer Rules & Environment Settings

## ⚙️ Gradle & Compilation Environment
*   **Java JDK / Runtime Location**: The default shell does not have `JAVA_HOME` or the `java` binary in its `PATH`. Always use the Android Studio bundled JetBrains Runtime (JBR) for executing Gradle commands:
    ```powershell
    $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
    ./gradlew compileDebugKotlin
    ```
*   **Deployment Pre-Requisite**: Before initiating any deployment, emulator install, or App Bundle compilation task, always run the fast local unit tests first (using `./gradlew testDebugUnitTest`) to ensure codebase integrity and compilation stability.
*   **Minification (R8) & Obfuscation Warning**: Do not enable code minification (`isMinifyEnabled = true`) or resource shrinking (`isShrinkResources = true`) in the release build block of `app/build.gradle.kts` unless comprehensive keep rules are fully verified. Gson, Retrofit, and other reflection-based API components will fail to deserialize network responses at runtime without these rules, returning empty states or failed lookups. Keep minification disabled (`false`) by default for release publications.

## 🎨 Compose & UI Layout Guidelines
*   **Flow Layouts**: Standard `FlowRow` is available under `androidx.compose.foundation.layout.FlowRow` and requires `@OptIn(ExperimentalLayoutApi::class)`.
*   **Conditional Border Modifiers**: When applying conditional borders in layouts, use direct conditional modifiers instead of referencing properties on a nullable `BorderStroke`:
    ```kotlin
    Modifier.then(if (isSelected) Modifier.border(1.dp, NeonBlue, shape) else Modifier)
    ```
*   **Folder/Album Navigation Routing**: When resolving keys for gallery navigation, always distinguish between Folder nodes and Album nodes. If the target key corresponds to a Folder node, navigate the user to the folder explorer root (`"browser"`) and update the active folder via `navigateToChildFolder(node)` rather than navigating to the media grid (`"photo_grid"`), preventing endpoint query failures.
*   **Technical Jargon Auditing**: Actively identify and eliminate technical jargon (such as "EXIF", "ISO", or raw database response parameters) from user-facing text, replacing them with simple, intuitive descriptions that are easy for non-technical users to understand.



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
*   **Search Filter Payload Shrink**: Optimize the `_filter` query parameter on search endpoints to omit heavy payloads like `ArchivedUri` and `OriginalWidth`/`OriginalHeight`/`OriginalSize`. However, to prevent parent gallery navigation failures, **always preserve `WebUri` and the nested `Uris` dictionary (along with `ImageAlbum` in `_filteruri`)** so the app can resolve parent gallery keys for locked or anonymous media.
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
*   **Flow Test Imports**: When mock-stubbing flow variables (like `activeDevice` or `discoveredDevices` StateFlows) in test configuration setups, always ensure that `kotlinx.coroutines.flow.MutableStateFlow` or `kotlinx.coroutines.flow.flowOf` imports are explicitly declared rather than using unresolved short references.

## 🤖 Antigravity Tool Usage Learnings
*   **Ripgrep Windows File Search**: On Windows systems, ripgrep (`grep_search`) might fail to find matches if `SearchPath` is set directly to a file containing backslashes. Instead, set `SearchPath` to the containing directory and filter using the `Includes` parameter with the filename (e.g., `Includes = ["BrowserScreen.kt"]`).
*   **Proactive Skill Verification**: Always check and view the `smugview_development_guidelines` SKILL.md file at the very start of any task or conversation. Never execute command-line tools (such as Gradle, ADB, or emulator binaries) without verifying the exact path, environment variables, or commands documented in the workspace guidelines first.
*   **Windows Markdown Image Path Validation**: The IDE validator enforces absolute paths starting with `/` located inside the artifact directory on Windows. If the workspace drive uses lowercase casing (e.g. `c:\`) while the environment defines the app data path in uppercase (e.g. `C:\`), case-sensitive prefix checks fail. Slashes vs backslashes also cause issues. This false-alarm warning can be safely ignored; use forward slashes and absolute paths for correct image rendering: `![Caption](/C:/Users/kidzi/.gemini/antigravity-ide/brain/<uuid>/screen.png)`.

## 🔍 Scoped Search Learnings
*   **Search Scope Race Condition**: In `performSearch()`, avoid launching `searchNodesRemote` concurrently with `searchImages`. If run concurrently, the image search will read the database before the remote galleries are resolved and cached, finding 0 matching galleries and returning empty images. Run them sequentially (letting `searchNodesRemote` complete first) to ensure the cache is fully populated before searching images.
*   **Unit Test Speedup & Loop Constraints**: Do not loop over large album list counts (e.g., 100 albums) in live API unit tests. Limit loops verifying album details or scoped search flows to the first 1-2 galleries using `.take(2)`. Also, avoid redundant keyword checks in flow tests; use a single keyword (e.g., `"tahoma"`) to verify the end-to-end integration path cleanly and keep test runtimes under 1 minute.
*   **AlbumKeywords Expansion Map URI Parsing**: When reading expansions from `album/{albumKeys}?_expand=AlbumKeywords`, SmugMug maps expansions by the fully expanded sub-resource URI (e.g. `/api/v2/album/{key}!keywords`). To map this back to the clean album key correctly, clean the key by stripping everything after the `!` symbol (e.g., `uri.substringAfterLast("/").substringBefore("!")`). Failure to do this results in invalid lookup keys like `"key!keywords"`, failing downstream album resolution and photo grid binding.
*   **API Image Properties**: When interacting with the SmugMug API Image objects, use `FileName` to get the actual file name, and `OriginalSize` to get the raw byte size of the image.
*   **Album Key Helper Consistency (`getAlbumKey`)**: Always use the helper method `node.getAlbumKey()` instead of inline calculations like `node.albumUri?.substringAfterLast("/") ?: node.nodeId`. The helper method handles stripping sub-resource suffixes (e.g. `!keys` or `!images`), avoiding key mismatches in downstream database lookups or API calls. Avoid writing inline substring logic to prevent parallel agent refactoring thrashing.

## 🛡️ Antigravity Workspace Tool Boundaries
*   **Internal Brain Directory Access**: Do NOT use the native `list_dir`, `grep_search`, or `view_file` tools to search or traverse the root `.gemini/antigravity-ide/brain` directory. This violates a hardcoded system protection boundary and will fail with `Permission denied`. To read sibling agent logs, use terminal commands (like `Get-ChildItem` and `Get-Content` in PowerShell) or access the exact conversation paths explicitly provided in your environment variables or rules.

## 🔍 Bug Investigation & Resolution Guidelines
*   **Zero-Assumption Rule**: When investigating any bug or error, never assume that any part of the existing code is correct, even if it is pre-existing, compiles successfully, or returns an HTTP 200 OK. Every line of code related to the faulty flow must be suspected and validated from first principles.
*   **Mandatory RCA Workflow**: Any analysis of runtime bugs, navigation failures, or errors MUST explicitly use the `/find_root_cause` skill to perform a structured, evidence-based investigation. Bypassing this workflow or applying code fixes without presenting a proposal plan to the user is strictly prohibited.
*   **Navigation & Key Instrumentation**: Add explicit diagnostic logging and user feedback (e.g. detailed Toast messages) around any functions checking, extracting, or using album keys/navigation parameters. Always log precisely when a key is extracted, from which source (e.g. `detailedPhoto` vs `currentPhoto`), and when or why it becomes empty.
*   **Evidence-Based Reporting**: Before proposing or making code changes to fix any bug, always compile and provide a detailed evidence-based analysis report containing relevant logcat traces, databases/state snapshots, and actual API query inputs/outputs, along with recommendations for user review.
*   **Stage-by-Stage Verification & Raw Payload Inspection**: When analyzing API discrepancies (e.g. between a browser request and the mobile client), never rely solely on parsed application models or memory state objects, which assume successful serialization. Fetch and inspect the raw HTTP response payloads anonymously (simulating the client) and check for visibility toggles (such as `"ShowKeywords": false`) or server-side redactions that explain the behavior.

## 🧪 Test Architecture & Code Quality Guidelines
*   **Startup & UI Thread Room Queries**: Any Room database query executed during application startup, view initialization, or UI rendering must use targeted index-based queries (e.g. `getNodeByIdOrKey` or matching primary keys). Unbounded queries (such as `getAllCachedNodes()`) are strictly prohibited in loops or init blocks to prevent Main-thread blockages and database lock congestion (ANRs).
*   **Mock Verification of API Visibility Rules**: Mocks and unit test fixtures simulating API responses must model the actual visibility rules of the SmugMug API. Ensure fields that are redacted under anonymous states (like `Keywords` or `expansions` on locked categories) are explicitly verified in tests simulating anonymous vs. unlocked states.

## 🧪 QA/Tester & Data-Layer Snapshot Rules
*   **Scenario Update Test Planning**: Every workflow or scenario update must begin with a QA/Tester subagent-approved test coverage plan.
*   **Test Gap & Impact Analysis**: Every implementation plan must detail why a bug was missed by existing tests, how to address it in future tests, and how existing tests or snapshots are impacted (including updating snapshots and expectations).
*   **Mock Elimination via Snapshots**: Transition test suites away from static mock data layers. Capture raw, live API response JSON payloads and inject them at the lowest data layer (e.g. mock server or network level) to guarantee that all fields, schemas, and relationships (like `Uris` and `WebUri`) are identical to actual server payloads.
*   **Field Congruency Verification**: Before implementing tests, manually execute the target query against the SmugMug API. The QA/Tester must verify that all fields, URIs, and formats expected in the repository queries exist in the captured raw response before updating snapshots.

*   **Dual Test Reports & Verification**: After every test execution, the QA/Tester is responsible for generating, updating, and verifying the accuracy of the portable relative-linked summary (`test_result.md`) and deep-dive (`test_details.md`) reports. The Project Manager must verify these artifacts. Note that these are transient files created during the testing/build phase.
    *   **Summary Report Structure (`test_result.md`)**: Grouped by test category using a Markdown table with columns: `Verdict | Test Scenario | Source Query / API Endpoint | Expected Data / Fields | Actual Data / Status`. The status column must remain concise and end with a relative link to the detailed report: `[Details](test_details.md#<lowercase-scenario-name>)`.
    *   **Detailed Log Structure (`test_details.md`)**: Contains anchor headers (`<a name="<lowercase-scenario-name>"></a>\n## <Scenario>`), full response details, bulleted step lists, and verification logs.

## 📈 Project Manager Scrum & Retro Rules
*   **Automatic Retrospectives**: Every scenario update or bug fix task must conclude with an automatic retrospective review conducted by the Project Manager.
*   **Continuous Instruction Refinement**: The Project Manager must trace the log trajectory (in `transcript.jsonl`), locate misdirections, identify gaps in sibling personas (UX, Android, API, QA, Smart Device), and update workspace config/instruction guides to continually prevent repeat mistakes.

## 🧠 Critical Product Thinking & Constructive Dissent
*   **Product-First Advocacy**: Never implement any feature, fix, or workflow purely because another persona or instruction requests it. Critically assess if the action is truly optimal for the product's performance, stability, API limits, security, and user experience.
*   **Constructive Dissent Checklist**: Before aligning on any design proposal, check:
    *   *Android Expert*: Does this layout cause unnecessary recompositions or consume too much memory?
    *   *API Specialist*: Does this call pattern risk hitting rate limits or fetch redundant nested fields?
    *   *UX Designer/Reviewer*: Does this compromise standard typography, spacing, navigation segment state preservation, or accessibility guidelines?
    *   *QA/Tester*: Does this implementation allow deterministic automated validation or does it create untestable logic?
    *   *Smart Device Expert*: Does this integration handle network routing, firewall ports, cleartext traffic policies, and casting security protocols correctly?
*   **Dissent Record Requirement**: If a sub-optimal approach is requested or observed, explicitly raise the alternative, document the trade-offs, and seek alignment rather than moving forward silently.

## 📖 Documentation Reference & Maintenance
*   **Referencing Docs**: Respective developer agents must refer to the documentation files in the [docs](file:///c:/src/kidzi/GitHub/SmugView/docs) directory (including [DESIGN.md](file:///c:/src/kidzi/GitHub/SmugView/docs/DESIGN.md), [SMUGMUG.md](file:///c:/src/kidzi/GitHub/SmugView/docs/SMUGMUG.md), and [README.md](file:///c:/src/kidzi/GitHub/SmugView/docs/README.md)) to understand system design, SmugMug API specifications, and codebase setup.
*   **Maintenance**: Respective agents are responsible for keeping these files up to date during any edits, features, or architectural modifications.




