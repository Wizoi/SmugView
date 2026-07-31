# SmugView Developer Rules & Environment Settings

## 🚀 Agent Execution & Planning Guidelines
*   **Design Proposal Sign-off**: When receiving a new feature, bug fix, or scenario update request, always compile a structured implementation plan and wait for the user's explicit design sign-off/approval.
*   **Direct Execution Phase (Silent Mode)**: Once the implementation plan is approved by the user, proceed to execute all file edits, terminal commands, and testing tasks in sequence without stopping to prompt the user, ask for file-by-file confirmation, or explain intermediate steps. Provide a single, comprehensive final walkthrough only when execution is complete.
*   **Silent Mode EXCLUSIONS (always stop and confirm first)**: Silent Mode covers local, reversible work only. It NEVER authorizes irreversible or outward-facing actions. Plan approval is **not** approval for any of the following — confirm each one explicitly, in the moment:
    *   Publishing/uploading to Google Play (`publishReleaseBundle`, `publishListing`, the `/publish_app` skill).
    *   `git push`, force-push, history rewrites (`filter-repo`/BFG), tag/release creation, or anything that mutates a remote.
    *   Destructive git/data operations (`reset --hard`, `clean -fd`, branch/stash deletion, dropping or wiping database tables).
    *   Rotating, revoking, or writing credentials/secrets.
    *   Deleting files the agent did not create.

    Rationale: this repo pairs Silent Mode with `.vscode/settings.json` auto-save (1s) and a reachable
    one-command Play upload. Without these exclusions, an approved plan could ship to production with no
    human checkpoint.

## ⚙️ Gradle & Compilation Environment
*   **Java JDK / Runtime Location**: The default shell does not have `JAVA_HOME` or the `java` binary in its `PATH`. Always use the Android Studio bundled JetBrains Runtime (JBR) for executing Gradle commands:
    ```powershell
    $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
    ./gradlew compileDebugKotlin
    ```
    From the **Git Bash** tool, export a POSIX path and invoke `gradlew.bat`:
    ```bash
    export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
    ./gradlew.bat testDebugUnitTest
    ```
*   **Shell gotchas that have burned agents (verify results, don't trust exit codes blindly)**:
    *   **Piping masks exit codes.** `./gradlew.bat ... | tail` reports the exit status of `tail`, not Gradle. Never conclude "build passed" from a piped command — grep for an explicit `BUILD SUCCESSFUL`/`BUILD FAILED` line, or check `${PIPESTATUS[0]}`.
    *   **`MSYS_NO_PATHCONV=1` for adb.** Git Bash rewrites `/data/...` and `/sdcard/...` into Windows paths, so `adb shell` commands with device paths silently target the wrong path. Prefix with `export MSYS_NO_PATHCONV=1`. But note this ALSO stops the JBR POSIX path from being converted — set `JAVA_HOME` in a shell where path-conv is on (i.e. don't combine `MSYS_NO_PATHCONV=1` with the JBR export in the same command).
    *   **Avoid `./gradlew clean` while the emulator/app is running.** It triggers file-lock failures (`Unable to delete .../R.jar`). If you hit one, `./gradlew.bat --stop` then delete the locked intermediate dir and retry without `clean`.
    *   **`cd` into a subdir persists across Bash calls.** A stray `cd app/build/...` will make later `./gradlew.bat` invocations fail with "No such file". Prefer absolute paths / `cd` back to the repo root.
    *   **Never `git stash` to get a "baseline" mid-task.** Stashing untracked/staged work (e.g. `git rm --cached` deletions) and popping can silently re-add files you intended to remove. Compare against `HEAD` with read-only diffs instead.
*   **Deployment Pre-Requisite**: Before initiating any deployment, emulator install, or App Bundle compilation task, always run the fast local unit tests first (using `./gradlew testDebugUnitTest`) to ensure codebase integrity and compilation stability.
*   **Minification (R8) is ENABLED — keep it that way**: As of the 2026-07-17 review, `isMinifyEnabled`/`isShrinkResources` are **true** for release, guarded by `app/proguard-rules.pro` which keeps the reflective `com.smugview.app.data.api.**` and `data.db.**` packages plus Gson/Retrofit attributes. Do NOT disable R8 to "fix" JSON parsing — that ships an unshrunk, unobfuscated APK with the compiled-in API key trivially extractable. If you add a new reflection-based (Gson) model package, add a `-keep` for it in `proguard-rules.pro` instead. Caveat: `assembleRelease` compiling clean proves shrinking works, NOT that JSON still parses — smoke-test a release build on a device (load galleries + a search image) before shipping.

## 🔒 Security & Secret Hygiene (owned by the Security Reviewer persona)
*   **Never commit secrets.** The SmugMug API key, the signing keystore/passwords, `play-service-account.json`, gallery passwords, and any provider key (e.g. Anthropic in `.vscode/settings.json`) must never be tracked. Before committing, scan staged content: `git diff --cached | grep -iE "api.?key|password|sk-ant|oauth"`. The API key lives in `local.properties` (gitignored) and is injected via `buildConfigField` — reference `BuildConfig.SMUGMUG_API_KEY`, never a literal.
*   **Recorded API snapshots embed the live API key** in echoed response URLs. They are gitignored (`app/src/test/resources/snapshots/`) and must be **redacted** (replace the key with a placeholder) before they can be committed. Redaction must not break the `PlaybackInterceptor` request→file matching (matching is by sanitized filename, not the raw URL, so redacting the body is safe).
*   **Gallery passwords are stored encrypted** via `PasswordStore` (`EncryptedSharedPreferences`), not plaintext `SharedPreferences`. Route all password reads/writes through the injected `PasswordStore` interface. Never resolve a locked album by brute-forcing every saved password against the API — resolve the correct one via the node hierarchy.
*   **HTTP body logging must be gated on `BuildConfig.DEBUG`** (request URLs carry `APIKey`/`Password` query params). The Web Companion LAN server must stay restricted to the cast-target IP with a socket timeout and bounded pool — it serves potentially-private photo URLs over cleartext.
*   **Rotation is the user's job.** If a secret leaked into git history, removing it from HEAD does not unpublish it — flag that the key/password must be rotated and history scrubbed. Never `git push`, rotate, or scrub history without explicit per-action confirmation (see Silent Mode exclusions).

## 🎨 Compose & UI Layout Guidelines
*   **Flow Layouts**: Standard `FlowRow` is available under `androidx.compose.foundation.layout.FlowRow` and requires `@OptIn(ExperimentalLayoutApi::class)`.
*   **Conditional Border Modifiers**: When applying conditional borders in layouts, use direct conditional modifiers instead of referencing properties on a nullable `BorderStroke`:
    ```kotlin
    Modifier.then(if (isSelected) Modifier.border(1.dp, NeonBlue, shape) else Modifier)
    ```
*   **Folder/Album Navigation Routing**: When resolving keys for gallery navigation, always distinguish between Folder nodes and Album nodes. If the target key corresponds to a Folder node, navigate the user to the folder explorer root (`"browser"`) and update the active folder via `navigateToChildFolder(node)` rather than navigating to the media grid (`"photo_grid"`), preventing endpoint query failures.
*   **Technical Jargon Auditing**: Actively identify and eliminate technical jargon (such as "EXIF", "ISO", or raw database response parameters) from user-facing text, replacing them with simple, intuitive descriptions that are easy for non-technical users to understand.



## 🧭 Primary Agent Environment: Claude Code
This project is now worked on primarily via the **Claude Code** extension (not Antigravity). Translate the older "Antigravity tool" instructions below accordingly:
*   File search → `Glob`; content search → `Grep` (ripgrep-backed, pass a repo-relative `path` and `glob`); read → `Read`; edit → `Edit`/`Write`. Prefer these over shell `find`/`grep`/`cat`.
*   There is **no `.gemini/antigravity-ide/brain` sibling-transcript coordination** under Claude Code. Coordinate via git (`git status`, `git log`, branches) and this `AGENTS.md`. The Antigravity-specific sections further down are retained for reference but do not apply here.
*   Environment: Windows 11, Git Bash + PowerShell tools. See the Gradle shell-gotchas above before running builds/adb.

## 🤝 Parallel Workspace Coordination
*   **Coordination (legacy Antigravity note)**: If concurrent developer agents are working the codebase under Antigravity, their logs live at `C:\Users\kidzi\.gemini\antigravity-ide\brain\<sibling-conversation-id>\.system_generated\logs\transcript.jsonl`. Under Claude Code this does not apply — use git. Either way, take care not to regress features when editing shared god-files like `BrowserScreen.kt` (~4k lines) or `SmugViewModel.kt` (~3.2k lines).

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
*   **Test Gap & Impact Analysis**: Every implementation plan must detail why a bug was missed by existing tests, how to address it in future tests, and how existing tests or snapshots are impacted (including updating snapshots and expectations). If a new field or model property is added to API queries or cache entities, the QA/Tester must explicitly identify and update all related inline mock test stubs or external JSON snapshots to include the new field (preventing mock fields from being parsed as null during test runs).
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
    *   *Security Reviewer*: Does this expose a secret (key/password/token), private media, or an unauthenticated surface? Is anything sensitive being committed, logged, or stored unencrypted?
*   **Dissent Record Requirement**: If a sub-optimal approach is requested or observed, explicitly raise the alternative, document the trade-offs, and seek alignment rather than moving forward silently.

## 📖 Documentation Reference & Maintenance
*   **Referencing Docs**: Respective developer agents must refer to the documentation files in the [docs](file:///c:/src/kidzi/GitHub/SmugView/docs) directory (including [DESIGN.md](file:///c:/src/kidzi/GitHub/SmugView/docs/DESIGN.md), [SMUGMUG.md](file:///c:/src/kidzi/GitHub/SmugView/docs/SMUGMUG.md), and [README.md](file:///c:/src/kidzi/GitHub/SmugView/docs/README.md)) to understand system design, SmugMug API specifications, and codebase setup. For Play Store publishing/listing work, see [PUBLISH.md](file:///c:/src/kidzi/GitHub/SmugView/docs/PUBLISH.md) and [PRIVACY_POLICY.md](file:///c:/src/kidzi/GitHub/SmugView/docs/PRIVACY_POLICY.md) — if a change alters what data the app collects/stores/transmits or what permissions it needs, update PRIVACY_POLICY.md to match.

## 📈 Site Discovery & Autocomplete Learnings (2026-07-16/17 Retro)
*   **Compose Annotations Target Boundaries**: In Kotlin/Compose, never place annotations like `@OptIn` or `@Composable` directly on data classes or standard objects. These annotations are only applicable to functions, file levels, or local declarations, and placing them on classes will cause immediate compiler errors.
*   **GSON Mock Schema Key Congruency**: When writing unit test mock JSON responses for SmugMug API endpoints (e.g. `image!search`), verify that the JSON keys in the mocked payload match the `@SerializedName` annotations in the production models (e.g., `"Image"` instead of `"AlbumImage"`). Failure to match these exactly results in GSON silently parsing the payload to an empty list, causing unit test assertions to fail.

## 📈 Refined Gallery Hub & Suspend Mocking Learnings (2026-07-17 Retro)
*   **API Filter Navigation Integrity**: When applying a custom `_filter` query on endpoints (like `user!recentimages`), ensure the `Uris` envelope is preserved if downstream navigation requires parent gallery details (like `ImageAlbum` / `albumKey`). Stripping `Uris` breaks photo detail clicks.
*   **Kotlin Suspend Function Mocking inside Unit Tests**: To stub Kotlin suspend functions with arguments in Mockito tests, wrap the stubs in `kotlinx.coroutines.runBlocking { ... }` and use standard `Mockito.when(...)` with exact Kotlin argument types. Avoid calling `doReturn().when(mock).suspendMethod(any())` from outside coroutine scopes as it causes compilation errors due to hidden Continuation parameter mismatches.

## 📈 Password Caching & Mockito Matchers Corruption Learnings (2026-07-17 Retro v2)
*   **Privacy vs SecurityType Mapping Coexistence**: SmugMug API responses for password-protected items can return `"Privacy": "Public"` (or `"Unlisted"`) alongside `"SecurityType": "Password"`. When parsing API response nodes to cache models, always prioritize `securityType` over `privacy` to prevent storing locked items with public access values.
*   **Mockito Suspend Verification Gotcha**: Verifying suspend functions using standard Mockito `verify(...)` is fragile due to the hidden `Continuation` parameter appended by the Kotlin compiler. Avoid verifying suspend functions with Mockito matchers; prefer testing via concrete fakes (e.g., `FakeCollectionDao`) or checking state/return value results directly.
*   **Mockito Global State Corruption**: Any mismatched/unfinished matcher in a Mockito test (like mixing matchers and concrete arguments or incomplete verification statements) corrupts Mockito's global thread-local state. This causes unrelated subsequent tests in the suite to fail with `UnfinishedVerificationException` or `InvalidUseOfMatchersException`. Ensure every Mockito stubbing/verification call uses matchers for all parameters and completes correctly.

## 📈 Featured Sites Selection & Live API Validation Learnings (2026-07-17 Retro v3)
*   **Onboarding / Exploration Hardcoded Portfolios**: When curating featured portfolios or onboarding sites on the client-side (such as carousels in `BrowserScreen.kt`), do not use individual photographers who are prone to profile deletion or nickname updates. Instead, select official company-managed portals (`smugmugfilms`, `Tutorial`) and established public institutions (`uphs`, `corvettemuseum`, `daemenuniversity`).
*   **Live Configuration Regression Safeguards**: To protect hardcoded onboarding configurations from decaying over time, implement a target integration test in the live API test suite (`SmugMugApiTest.kt`) that queries each nickname. Wrap the test in credentials and network capability checks so that it skips gracefully in offline CI environments instead of causing build failure.

## 📈 Full-Codebase Review & Album-Sync Learnings (2026-07-17 Retro v4)
Distilled from a large security/quality review + the incremental album-index feature. These are the highest-leverage, most non-obvious facts for this project.

### SmugMug API — hard limits verified against the live API
*   **`image!search` (keyword + search) returns NO containing-album reference.** `Album`, `ImageAlbum`, and `WebUri` are all absent from results — even for public images. You **cannot** resolve which gallery a search-result image belongs to from the search response. The app resolves it heuristically by matching the photo's `ThumbnailUrl` **path** against the cached album index (`getAlbumKeyFromWebUri`). This only works if the album index is populated (see album-index sync). Design "jump to gallery from a search image" around this limit.
*   **Album/gallery METADATA is public; only CONTENTS are gated.** `GET album/{key}` (and `user/{nickname}!albums`) return `Name`, `AlbumKey`, `UrlPath`, `SecurityType`, `ImageCount`, `LastUpdated`, and the `HighlightImage` reference **without a password**, even for `SecurityType=Password` galleries. The images/`ImageAlbum` are withheld until a session unlock. → You can detect a locked album and prompt for its password *without* already having it.
*   **`user/{nickname}!albums` supports `SortMethod=LastUpdated&SortDirection=Descending`** (newest-first). Use it for incremental sync: fetch newest-first and stop at the first album whose `LastUpdated` you already have cached.
*   **NodeID vs AlbumKey are different namespaces.** `GET node/{albumKey}` 404s (an album key is not a node id). Don't feed album keys to node endpoints.
*   **Session unlock ≠ `?Password=` query param.** The single-image endpoint does not honor `?Password=` for locked galleries; the app unlocks via a POST `!unlock` that establishes a session cookie (OkHttp CookieJar), then GETs. Don't expect a bare `Password` query param to unlock image detail.

### Room / DB performance — full-table scans STARVE under load
*   **`getAllCachedNodes()` (`SELECT *`) AND leading-wildcard `LIKE` (`albumUri LIKE '%'||key`) both do full-table scans.** Under concurrent load they hang for many seconds (verified: a lookup that should be instant blocked >20s while other fetches ran, main thread idle). Primary-key/indexed lookups (`getNodeById`) stay instant. Never use `getAllCachedNodes()` or leading-wildcard `LIKE` in a hot/navigation path — use indexed queries (extends the existing "Startup & UI Thread Room Queries" rule to ALL hot paths, not just init/render).
*   **Blocking `Thread.sleep` retry interceptor + eager pagination = dispatcher starvation.** The OkHttp retry interceptor sleeps the calling thread on 429s; combined with loading whole result sets eagerly (all keyword images / all albums), it saturates the OkHttp dispatcher and starves unrelated calls (e.g. a single `getAlbum` queues behind it). Two levers: (a) don't eagerly page huge result sets — persist + load on demand; (b) the retry interceptor should eventually move to suspending `delay`.
*   **Persist + incremental-sync pattern (implemented for albums, reuse it):** the gallery index lives in `cached_albums` (flat, separate from the browsable `cached_nodes` tree). On launch: load from Room instantly, then delta-sync newest-first and stop at known data. Only metadata + cover is synced; gallery **contents** stay on demand. Result: a warm launch made 2 album requests instead of 26.

### Kotlin / Compose / coroutine traps hit this session
*   **StateFlow used in an `init`-block `combine` must be declared BEFORE the `init` block.** Property initializers run top-to-bottom; an `init` at line N that references a `MutableStateFlow` declared at line >N sees `null` → `NullPointerException` in `combineInternal` at construction (crashes the ViewModel/app). Declare such flows above the `init` block.
*   **The password prompt only renders in `BrowserScreen` and `PhotoGridScreen`** (they host `PasswordPromptDialog`). Detail screens do not. To prompt from a detail-screen action, navigate to the grid and let `selectAlbum` → prompt handle it — don't call `promptPassword` from a screen with no dialog.
*   **Bound starvation-prone work with `withTimeoutOrNull`.** If a preamble (e.g. hierarchy password resolution) can be starved, wrap it so the flow still proceeds (detect-lock → prompt) instead of spinning forever.
*   **Inject dispatchers for testability.** `Dispatchers.Default/IO` hardcoded inside a class means `runTest` virtual time can't drive it (forces `Thread.sleep` in tests). Add a test-only constructor taking a `CoroutineDispatcher`.

### Verifying on the emulator (playbook — UI taps are unreliable)
*   `input tap` for multi-screen flows desyncs constantly (auto-hiding controls, keyboard shifting buttons). Prefer **evidence over taps**: (1) sprinkle temporary `BuildConfig.DEBUG` `Log.d` tags and `adb logcat -d | grep <tag>` to trace execution; (2) `adb exec-out screencap -p > f.png` then read the image; (3) inspect the DB with `adb shell "run-as com.smugview.app sqlite3 /data/data/com.smugview.app/databases/smugview_db '<SQL>'"` (needs `MSYS_NO_PATHCONV=1`); (4) `uiautomator dump` for exact tappable bounds; (5) `cat /proc/$(pidof com.smugview.app)/status | grep State` to tell an ANR (blocked) from executor starvation (main thread `S` sleeping). `force-stop` between runs for a clean state; a **release** install requires uninstalling the debug build first (signature mismatch) and wipes app data.

### Investigation discipline
*   **Separate "regression I introduced" from "pre-existing bug my change exposed."** Removing a crutch (e.g. a brute-force password replay) can surface a latent bug that only *looked* like a new regression. State which it is, with evidence.
*   **Know when to stabilize vs keep digging.** A single feature (locked-gallery jump) spiraled through 5+ layers of pre-existing issues. When a fix keeps revealing deeper pre-existing problems, land the safe/verified pieces, document the blocker precisely, and stop — don't destabilize verified work chasing the tail.

## 📈 Progressive Pinch-to-Zoom & Publish-Verification Learnings (2026-07-18 Retro v5)
Distilled from replacing the guessed-URL pinch-to-zoom with real SmugMug `ImageSizeDetails`-driven tiers, and the v0.6.3 publish.

*   **`ImageSizeDetails` has an extra nesting level the other endpoints don't.** It's `Response.ImageSizeDetails.ImageSizeXxx`, not `Response.ImageSizeXxx` like every other envelope in this codebase. Modeling it flat silently deserializes every size to `null` — no crash, no error, the feature just always falls back to its "not loaded yet" behavior. Only caught by curling the live endpoint and diffing raw JSON against the Kotlin model. Full verified field list now in `api_specialist.yaml`.
*   **On-device crash-free navigation is NOT proof a new response model is correct.** It proves the null-safe fallback paths don't NPE. A wrong JSON shape/field name fails silently (see above) and would sail through any amount of "load galleries, no crash" smoke testing. For any new Gson response model, `curl` the live endpoint first — this is the one verification step nothing else substitutes for.
*   **Adding a `SmugMugApi` interface method breaks `MockSmugMugApi` at compile time, not runtime.** `SmugMugApiTest.kt`'s `MockSmugMugApi` hand-delegates every method; a new endpoint fails `compileDebugUnitTestKotlin` until the mock is updated too. This is exactly the class of break `publish_app.yaml`'s mandatory `testDebugUnitTest` step exists to catch before it reaches a release build — don't skip it, and don't assume "clean `assembleRelease`" means tests would also pass.
*   **`LaunchedEffect(key) { if (gate) commit(key) }` can miss the exact recomposition where `gate` flips true**, if the key's value happens to be numerically unchanged across that transition (a `coerceAtMost` clamp can easily produce this). Compose only re-runs the effect when the KEY changes, not when values referenced inside the block change. Always include the gate in the key list: `LaunchedEffect(key, gate)`.
*   **Real multi-touch pinch cannot be reliably synthesized via adb in this environment — stop trying.** Parallel `input touchscreen swipe` calls don't merge into one gesture; `monkey --pct-pinchzoom` emitted single-pointer events only and relaunched the app (destroying nav state); raw `sendevent`/`getevent` can't even see `adb shell input` taps (they bypass the device nodes via InputManager injection). For gesture-gated Compose logic, get a human to pinch-test on-device, or write an instrumented test using Compose's `performTouchInput { pinch(...) }`. Full detail in `find_root_cause.yaml`'s emulator playbook.
*   **The R8 release-smoke-test gate paid for itself again**: `assembleRelease` compiling clean said nothing about whether the new Gson model classes (already covered by the existing `com.smugview.app.data.api.**` keep rule) would actually parse under minification — installing the signed release APK and re-navigating to the immersive pager confirmed it before publishing, per the existing "Minification (R8)" rule above. No proguard changes were needed this time because the new classes landed inside the already-kept package — but always re-check the keep rule covers a new model's actual package before assuming that.

## 📈 Cache Reconciliation & Dispatcher-Injection Learnings (2026-07-31 Retro v6)
Distilled from two production bug reports (gallery search stuck at 0 after unlocking a
password-protected folder; folders not showing new/updated galleries without a manual refresh)
plus a flaky `SmugViewModelTest` failure surfaced while adding regression coverage.

*   **The v4 "persist + incremental-sync" pattern (`cached_albums` flat index vs. `cached_nodes`
    tree) was documented but never reconciled — that gap was the root cause of both bugs.**
    Unlocking a password-protected folder fetches its children via `getNodeChildren` into
    `cached_nodes` (proven by photo search working), but nothing ever merged the revealed Album
    rows into `cached_albums`/`albumsCache` — the only store gallery search reads — so galleries
    stayed invisible to search forever. Separately, `getNodeChildren` caches a folder's children
    with no TTL/staleness check (`if (cached.isNotEmpty() && !forceRefresh) return`), and the
    startup tree crawl (`startFolderTreeSync`) always passes `forceRefresh = false`, so it's
    permanently a no-op after the first launch — a folder's cached listing never learns about a
    new or updated gallery underneath it. Fixed by (a) having `getNodeChildren` merge any
    Album-type children it fetches into the flat gallery index too
    (`SmugMugRepository.mergeAlbumsIntoIndex`), and (b) giving `CachedAlbum` a `parentNodeId`
    (from the API's `Uris.ParentNode`, already returned but previously discarded) so the existing
    cheap `LastUpdated` delta sync can evict just the affected parent folder's `cached_nodes` rows
    when one of its galleries is new or changed (`dao.deleteNodesByParent`). Two independent
    caches for the same underlying data need an explicit reconciliation path — building the second
    cache is not enough; write down (or code) how each write to one propagates to the other.
*   **That first fix was verified-correct but still shipped incomplete (caught in production on
    v0.7.1/versionCode 19, same day).** `mergeAlbumsIntoIndex` only merges a folder's DIRECT
    children on unlock. Real sites commonly nest galleries several folders deep (locked "Family"
    -> "School" -> the actual gallery); the unlocked folder's direct children are then all type
    `Folder`, not `Album`, so there's nothing to merge and search stays blind to anything nested,
    even though the folder itself now shows unlocked. The original regression test didn't catch
    this because it only modeled one level of nesting — it proved the merge mechanism works, not
    that it reaches deep enough. Fixed with `SmugMugRepository.unlockAndIndexSubtree`: a
    background BFS (same password, capped at `maxNodes = 300`) that walks the rest of the subtree
    after unlock, so `getNodeChildren`'s existing per-level merge fires at every depth, not just
    the first. **Lesson**: when a bug report says "still broken after the fix," re-derive the
    failure from the user's exact scenario before assuming the original diagnosis was wrong — here
    the diagnosis (two unreconciled caches) was right, the *scope* of the fix (one level vs. the
    whole subtree) was the gap. A regression test with only one level of tree depth can't catch a
    bug that only manifests at depth two — mirror the real reported topology, not the simplest
    shape that reproduces the symptom.
*   **A cache invalidation fix is easy to get backwards without a failing-first test.** Both
    regression tests (`testUnlockingFolderMakesItsGalleriesSearchable`,
    `testGalleryUpdateInvalidatesParentFolderCache`) were verified to actually fail against the
    pre-fix code via `git stash` on just the repository file before being trusted — a test that
    only asserts the fix's own new code path can pass for the wrong reason (e.g. testing the mock
    setup, not the production logic) if it's never run red first.
*   **The v4 "inject dispatchers for testability" rule wasn't applied everywhere it should have
    been, and the gap silently corrupted an unrelated test's result.** `SmugViewModel` had two
    `Dispatchers.Default` references — an eagerly-shared `StateFlow` (`unlockedNodeIds`, started at
    construction via `SharingStarted.Eagerly`, so present in *every* test in the class, not just
    the ones exercising it directly) and an on-demand `viewModelScope.launch` inside
    `getImageDetails()`. `Dispatchers.setMain(testDispatcher)` only redirects `Dispatchers.Main`;
    there is no `Dispatchers.setDefault()` in kotlinx-coroutines, so both ran on the real JVM
    thread pool, untracked by `runTest`'s virtual scheduler. When one of those coroutines finished
    (or threw) after its originating test method had already returned, the exception surfaced as
    `UncaughtExceptionsBeforeTest` on whatever *unrelated* test's `runTest` happened to check next
    — so the failing test name in a flaky run is not necessarily where the problem is. Fixed by
    following `DefaultCastManager`'s exact precedent: a Hilt-provided `CoroutineDispatcher` binding
    (`AppModule.provideDefaultDispatcher`, real `Dispatchers.Default` in production) injected into
    the constructor, with the test constructing the ViewModel with `testDispatcher` instead.
    Verified with 10 isolated + 5 full-suite reruns (15/15 green) — for a leaked-coroutine flake, a
    single passing run after a fix proves nothing; only repeated reruns do.
*   **`git stash push -- <path>`, scoped to a single file, is a clean way to verify "does this
    test actually catch the bug" without a throwaway branch** — stash just the production fix,
    run the new test (expect red), `git stash pop` to restore. Safe because it's fully reversible
    and scoped, unlike editing the fix out and back in by hand.
