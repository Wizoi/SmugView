# Design: SmugView on iOS, and auto-publishing to the App Store

Status: **PROPOSAL, nothing built.** Written 2026-10-13. Needs the owner's answers in section 7 before any stage starts.
Related: [DESIGN.md](../DESIGN.md), [SMUGMUG.md](../SMUGMUG.md), [PUBLISH.md](../PUBLISH.md), [new-gallery-notification.md](new-gallery-notification.md), [phase-5-offline-files.md](phase-5-offline-files.md).

Labels: **VERIFIED** means checked in this repo's code or on an authoritative page (URL given). **UNVERIFIED** means
inferred, from a search summary or third-party page, or from memory. Effort is in person-weeks of focused full-time
work by one developer (FTE-weeks). These are estimates, not measurements.

## Recommendation (one paragraph)

Use **Kotlin Multiplatform with Compose Multiplatform (option A)**. Add a `shared` module holding the API client,
repository, Room database, view models and Compose screens. Android keeps a thin `app` module. iOS gets a thin Xcode
shell plus Kotlin `iosMain` code for the parts Apple does differently (Keychain, Photos, share sheet, video, background
tasks, notifications).

About **85% of today's 28,343 lines would live in `commonMain`** (section 1.3 shows how that was counted). The
Android app keeps shipping throughout, because every library swap lands on Android first, under the existing 909 tests.

iOS v1 should ship **without casting**. It should replace the 6-hourly new-gallery poll with a **best-effort
`BGAppRefreshTask` plus a check on every app open**, and should **not** add a push server.

The pipeline is GitHub Actions. macOS runners are free for this public repo. It uses fastlane (`match`, `build_app`,
`upload_to_testflight`, `deliver`), and a **GitHub environment with the owner as required reviewer** gates anything
that goes to App Review or release.

Three things block a public iOS release and none of them is code:
- **SmugMug's API terms:** "not permitted to reuse an API Key in more than one Application", and app names must not
  include a SmugMug mark.
- **Owner budget:** $99/year for the Apple Developer Program.
- **A Mac for debugging:** an iOS build cannot be produced or debugged on Windows.

Rough total to a first App Store release: **15 to 22 FTE-weeks**.

---

## 1. Inventory (measured)

### 1.1 How it was measured (VERIFIED, commands run 2026-10-13)

- Per package: `find <dir> -maxdepth 1 -name '*.kt' | wc -l` and `cat … | wc -l` under `app/src/main/java/com/smugview/app`.
- A per-file classifier script (`classify.sh` in the session scratchpad, not committed) greps each file's `import` lines
  into these classes:
  - **P:** `android.*`.
  - **X:** AndroidX that is not KMP-capable: `core`, `activity`, `work`, `hilt`, `security`, `palette`, `media3`,
    `mediarouter`, `exifinterface`, `sqlite.db`.
  - **J:** JVM or Android-only third-party libraries: `retrofit2`, `okhttp3`, `com.google.gson`, `org.json`, `coil.`
    (Coil 2), `dagger.`, `javax.inject`, `com.google.zxing`, `com.google.android` (Cast).
  - **V:** `java.*`.
  - **C:** `androidx.compose.*`, which Compose Multiplatform provides under the same package names.
  - **K:** KMP-capable AndroidX: `room`, `paging`, `lifecycle`, `navigation`.
- Code lines that touch platform types: for each file, the count of non-import, non-comment lines matching a token
  list. The list includes `Context|Toast|Intent|SharedPreferences|Log.d(|BuildConfig|File(|java.|okhttp3|retrofit2|
  HttpException|WorkManager|Bitmap|Palette|ExoPlayer|hiltViewModel|ImageRequest|ConcurrentHashMap|Atomic*|
  System.currentTimeMillis|BackHandler|MediaStore|SupportSQLiteDatabase|Notification|MessageDigest|URLEncoder|
  Interceptor|CookieJar|HttpUrl|Executor|JSONObject|InputStream|IOException|ExifInterface|QRCodeWriter|MediaRouter|…`.
  This is an upper bound on the lines that need editing. It does not count the import lines themselves.
- Tests: `grep -cE '^\s*@Test\b'` per file, plus `grep -l` for Robolectric, Compose rules, Room, work-testing,
  Mockito and OkHttp.

**Totals (VERIFIED):**
- Main source: **110 Kotlin files, 28,343 lines**.
- **51 files (6,554 lines) import nothing in P, X or J.**
- Only **642 non-import lines (2.3%) touch a platform type**. A third of them sit in four files:
  - `SmugMugRepository` (60).
  - `SmugViewModel` (58).
  - `CastManager` (56).
  - `AppModule` (47).

  In the repository and view models these are mostly `android.util.Log` (≈60 calls), `BuildConfig.DEBUG`,
  `retrofit2.HttpException` (17 uses), `Context`/`SharedPreferences`, `Toast` and `Uri.fromFile`.
- User-visible text is Kotlin (`ui/text/UserMessages.kt`, `OfflineMessages.kt`). `res/values/strings.xml` is 4 lines,
  so there is almost no Android resource coupling.

### 1.2 Table: layer by layer (VERIFIED counts; the verdicts are this design's judgement)

| Layer | Files | LOC | Files with P/X/J imports | Files with `java.*` | Platform-touching lines | Verdict |
|---|---|---|---|---|---|---|
| app-root (`MainActivity`, `SmugViewApp`) | 2 | 448 | 2 | 0 | 13 | **Rewrite per platform** (thin) |
| data/api (Retrofit interface, models, retry, cache policy) | 8 | 1,173 | 7 | 2 | 41 | **Shares after library swap.** Models and `Pager`/`ErrorMapper` move as is. ~330 lines of HTTP core (`RetryingCallFactory`, `CachePolicy`, `HttpClients`, `SyntheticCacheMiss`) are rewritten once, in common code |
| data/repository | 7 | 2,972 | 2 | 1 | 65 | **Shares after swap** (logging, `HttpException` → own `ApiException`, `Context` removed) |
| data/db (Room) | 7 | 1,585 | 1 | 0 | 9 | **Shares after swap** (Room KMP; 6 migrations move from `SupportSQLiteDatabase` to `SQLiteConnection`) |
| ui/viewmodel | 11 | 4,606 | 3 | 2 | 118 | **Shares after swap** (`AndroidViewModel` → `ViewModel`, `SharedPreferences` → settings, `Toast` → message flow) |
| ui/* (Compose: browser, component, detail, explorer, grid, navigation, text, theme) | 29 | 11,082 | 21 | 2 | 114 | **Shares after swap** (Compose Multiplatform). Per-platform pieces: the video player (ExoPlayer), system bars, the share intent and the permission launcher. QR moves to a KMP library |
| data/offline | 11 | 1,955 | 5 | 6 | 47 | **Shares after swap** (file IO → kotlinx-io/okio, OkHttp → Ktor). `OfflineScheduler` (94) is per platform |
| data/notify | 6 | 261 | 2 | 1 | 11 | Check, runner and follow (153) **share as is**. `NewGalleryNotifier` and `NewGalleryScheduler` (108) are **per platform** |
| diag | 12 | 1,432 | 5 | 9 | 49 | **Shares after swap** (file IO, `org.json` → kotlinx.serialization). `CrashRecorder` and `DiagnosticsInitializer` (149) are **per platform** |
| data/cast | 6 | 1,499 | 3 | 1 | 71 | **Rewrite per platform.** The models (`CastDevice`, `CastIo`, `CastNaming`, 91 lines) share |
| data/security (`PasswordStore`) | 2 | 262 | 1 | 1 | 14 | **Rewrite per platform** (Keychain on iOS) |
| data/worker | 2 | 89 | 2 | 0 | 2 | **Rewrite per platform** |
| di (Hilt) | 2 | 560 | 2 | 2 | 56 | **Rewrite** as Koin modules, mostly common |
| download (`PhotoDownloader`, MediaStore) | 2 | 218 | 1 | 1 | 23 | **Rewrite per platform** (Photos). `Renditions` shares |
| share (`JpegStrip`, `ShareFiles`) | 2 | 168 | 1 | 2 | 7 | `JpegStrip` (pure byte code) **shares as is**. Reading orientation with `ExifInterface` moves into it or goes per platform |
| util (`SmugLog`) | 1 | 33 | 1 | 0 | 2 | expect/actual, or a KMP logger |
| **Total** | **110** | **28,343** | **59** | **30** | **642** | |

**Tests (VERIFIED):**
- Unit tests: 146 files, 23,554 lines, **909 `@Test`**.
  - Of those, 554 tests in 83 files run under Robolectric.
  - 484 tests in 63 files go through OkHttp or `FakeSmugMugServer`, an 816-line OkHttp `Interceptor`.
  - 160 tests in 16 files use Mockito, 70 tests in 6 files use work-testing and 69 tests in 20 files are Compose UI
    tests.
  - 43 tests use Room in memory or `MigrationTestHelper`.
  - **305 tests in 55 files have no Android dependency.**
- `androidTest`: 2 files, 5 tests.
- Recorded API snapshots: 171 files in `app/src/test/resources/snapshots/`. They are gitignored because they embed
  the key. There are also 11 OPTIONS contract files in `api-contract/`.
- **There is no CI today** (no `.github/`). The repo `github.com/Wizoi/SmugView` answers anonymous requests (HTTP 200
  on the web page and the REST API), so it is **public**.

**Build facts (VERIFIED, `app/build.gradle.kts` and the root `build.gradle.kts`):**
- Kotlin 2.0.20 with **kapt**, not KSP.
- Room 2.6.1, Compose BOM 2024.04.00, Hilt 2.50, Retrofit 2.9.0, OkHttp 4.12, Coil 2.5.0, Paging 3.2.1, WorkManager
  2.9.0, Media3 1.3.0.
- `security-crypto:1.1.0-alpha06`, `play-services-cast-framework` 21.4.0, ZXing core, `exifinterface`.
- minSdk 26, targetSdk 36. `versionCode = 28`, `versionName = "0.9.0"`.

### 1.3 The reuse number and how it was derived

Each layer's verdict above was turned into lines that end up in `commonMain`:
- Moving with mechanical edits only:
  - ui 11,082, less about 700 platform lines (video player, system bars, share and permission launchers;
    UNVERIFIED estimate from the 64 touch lines in `ui/detail` and the ExoPlayer/`Intent` call sites).
  - viewmodel 4,606, repository 2,972, db 1,585.
  - offline 1,861 (less the scheduler), notify 153, diag 1,283.
  - api 1,173, of which ~600 are rewritten (HTTP core plus migrations).
  - cast models 91, `JpegStrip` 94, `Renditions` 20, `PasswordPrefsCompat` 42.
- Total: **≈24,260 of 28,343 lines, about 85%**. Leaving out the ~600 lines rewritten inside common code gives
  ≈83% that move with mechanical edits only.
- About 4,100 lines stay per platform. The largest pieces are casting (1,408), DI (560), the app shell (448) and the
  UI carve-outs (700).
- iOS needs new code for those parts, estimated at 2,000 to 3,000 lines of Kotlin `iosMain` and Swift without
  casting (UNVERIFIED estimate).

---

## 2. Options

| | (A) KMP + Compose Multiplatform | (B) KMP data layer + SwiftUI | (C) Full Swift rewrite | (D) Flutter / React Native rewrite | (E) Web app or wrapper |
|---|---|---|---|---|---|
| Reused code | ≈85% (section 1.3) | ≈45%: api, repository, db, offline, diag and most view-model logic (≈12,900 lines). All 11,082 UI lines are rewritten in Swift | 0% shared code. The *knowledge* in SMUGMUG.md and findings carries over | 0%, and Android is rewritten too | 0% |
| Effort to first App Store release | 15–22 FTE-weeks | 24–32 (a SwiftUI rewrite of ~11k UI lines while learning Swift, plus the bridging) | 30–45 (28k lines, plus re-learning every SmugMug quirk in findings #1–#26) | 35–50 for both platforms | 6–10 for a site, but it fails the requirements |
| Ongoing cost | One codebase. iOS adds checks per feature (+20–30%, UNVERIFIED) and a yearly Xcode SDK bump | Every screen is written twice | Every feature twice, in two languages | One codebase, but the Kotlin skill and the 909 tests are thrown away | Low, but no store app |
| Tests | All 909 keep running on the JVM. Pure ones move to `commonTest` and also run on the iOS simulator | Data-layer tests shared; iOS UI tests new | None shared | None shared | n/a |
| Main risk | Compose on iOS feel (text input, scroll physics); Kotlin/Native build times | Owner has no Swift experience; two UIs drift | Two products; the "new"-dot and offline fixes have to be re-learned | Abandons a working, tested Android app | App Review |

Notes on each option:
- **(A)** Compose Multiplatform has been **Stable for iOS since 1.8.0 (May 2025)**. VERIFIED, [JetBrains blog](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/).
  The current release is 1.12.1, and the minimum iOS is 14. VERIFIED, [compatibility page](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html).
  CMP 1.11 added *opt-in* native iOS text input, which implies the default text field is still not fully native.
  UNVERIFIED, from a search summary.
- **(B)** This option makes sense only if a "feels exactly like iOS" UI is worth roughly 10 extra weeks and a second UI
  to maintain for ever.
  KMP-to-Swift interop is via an Objective-C header. Swift export is **Alpha**. VERIFIED, [kotlinlang](https://kotlinlang.org/docs/native-swift-export.html).
  So the view models' `StateFlow`s need wrappers (SKIE or hand-written).
- **(C)** and **(D)** Both lose the 909 tests and the encoded knowledge of the SmugMug API's quirks (findings #14–#24,
  SMUGMUG.md). Several of the bugs in findings.md came from reasonable-looking code that disagreed with real data, and
  a rewrite reopens all of them.
- **(E) PWA, or a WebView wrapper around a web build:**
  - A PWA is not an App Store app at all, so it does not meet the owner's goal.
  - A wrapper risks **4.2**: "features, content, and UI that elevate it beyond a repackaged website". 4.2.2 adds "web
    clippings, content aggregators, or a collection of links". VERIFIED, [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/).
  - It also cannot do Keychain-held passwords, Photos saves, offline files or background checks properly.
  - CORS on `api.smugmug.com` from a browser is untested (UNVERIFIED).
  - Rejected.
- **Also considered:**
  - **Kotlin/Wasm web plus a wrapper:** same 4.2 problem.
  - **Skipping Compose and porting only the data layer, with a minimal SwiftUI shell:** that is option B with a
    smaller first release. It is a fallback if the Stage 0 spike shows Compose on iOS is unacceptable.

**Recommendation: (A).**
- It is the only option where the owner's Kotlin skill, the 909 tests and the hard-won data-layer behaviour all carry
  over.
- The measured coupling is shallow: 2.3% of lines touch the platform, and user text already lives in Kotlin.
- It lets Android keep shipping from the same code at every stage.

---

## 3. Library-by-library migration map (for option A)

Risk: L = low, M = medium, H = high.

| Today (VERIFIED in code) | Target | Action | Risk | Notes |
|---|---|---|---|---|
| Retrofit `SmugMugApi` (269 lines, 22 suspend functions, `@Query`/`@Header`/`@Url`/`@FormUrlEncoded`) | Ktor client 3.x. Engines: OkHttp on Android, Darwin on iOS | swap | M | Ktorfit (KSP; 2.7.5, active, UNVERIFIED) keeps the annotated-interface shape and the least diff. **Recommended: hand-written `SmugMugApi` functions over Ktor.** 22 calls are few, and explicit `parameter(...)` makes the param contract obvious. Darwin is built on NSURLSession. VERIFIED, [Ktor engines](https://ktor.io/docs/client-engines.html) |
| `RetryingCallFactory` (retry 429/5xx, `Retry-After`, 5 attempts from 500 ms, *not* the synthetic 504, one `CallOutcome` per logical call for telemetry) | A small custom Ktor plugin (or `HttpRequestRetry` with `retryIf`, `delayMillis`) | rewrite in common | M | `HttpRequestRetry` has `retryIf`, `exponentialDelay` and `delayMillis`, but **no built-in `Retry-After`**. VERIFIED, [Ktor retry](https://ktor.io/docs/client-request-retry.html). Coroutines remove the thread-starvation reason this was a `Call.Factory` (AGENTS.md Retro v4/v6): `delay()` frees the thread. Keep `CallOutcome` and the existing `RetryingCallFactoryTest` cases as the oracle |
| `SessionCookieJar` + `CredentialEpoch` (in memory, host-keyed, bumps the epoch on a name/value change) | Ktor `HttpCookies` with a custom `CookiesStorage` | rewrite in common (the logic carries over) | L | Default storage is in memory; custom `CookiesStorage` is supported. VERIFIED, [Ktor cookies](https://ktor.io/docs/client-cookies.html). Disable the Darwin engine's own cookie handling so there is one jar (`configureSession { HTTPShouldSetCookies = false }`; UNVERIFIED API detail) |
| OkHttp `Cache` (50 MB) + `cachePolicyInterceptor` (offline `only-if-cached, max-stale=7d`; online `max-age=<seconds since epoch>` < 300) + `smugMugCacheRewriteInterceptor` (rewrites `no-store` to `max-age=300`) | **An app-owned API response cache** in common code (a Ktor plugin over a file store) | **rewrite** | **H** | **Biggest risk, see 4.9.** Ktor `HttpCache` answers `only-if-cached` with a synthetic 504 when there is no entry, as OkHttp does, but its source has **no `max-stale`** and it skips storing on `no-store`. VERIFIED, [HttpCache.kt](https://raw.githubusercontent.com/ktorio/ktor/main/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/cache/HttpCache.kt). So today's offline browsing cannot be reproduced by configuration. Write the rules in `CachePolicy` (already "the one owner") over a simple keyed file store with `sentAt`/`receivedAt`, and keep `isSyntheticCacheMiss` as a typed `CacheMiss` result |
| `HttpTelemetry`, logging interceptor + `Redactor` | Ktor `Logging` plugin with a `Redactor` sink, plus a telemetry hook in the retry plugin | swap | L | `Redactor` is pure Kotlin (`java.net.URLEncoder` → a small encoder) |
| Gson + `@SerializedName` (151 fields), `org.json` in `SmugMugErrorMapper`, `SyncReport`, `FileSyncReporter` | kotlinx.serialization: `@Serializable` and `@SerialName`; `Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }` | swap | **H** | **Gson fills missing non-null fields with null; kotlinx throws.** Many model fields are non-null with no default (e.g. `Uris`, `Uri`, `Pages.Total`, `NodeID`). See 4.10. The `Uris` value can be an object instead of a string when `_verbosity`/`_shorturis` is lost (findings #22). Guard every `Uris` entry with a `JsonTransformingSerializer` that takes `.Uri` from an object. VERIFIED that this is the documented tool, [kotlinx docs](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md). `Expansions: Map<String, …>` maps directly. Gson's `@Transient` field becomes `@kotlinx.serialization.Transient` |
| `ApiContractTest` (OPTIONS-driven: every method's params ⊆ the accepted set; the echo in `Response.Uri` equals the params sent) | The same test over Ktor `MockEngine` | swap | M | Split `FakeSmugMugServer` into a pure `handle(method, url, headers, body) → FakeResponse` core with two adapters: the OkHttp interceptor (today's tests, unchanged) and a Ktor `MockEngine` handler. The contract files in `api-contract/` are reused as is |
| Hilt (`@HiltViewModel`, `@HiltWorker`, `@Inject`, 2 modules, 560 lines) | **Koin 4.x** (`koin-compose-viewmodel`) | rewrite | M | Koin's ViewModel API is multiplatform since 4.0 (UNVERIFIED, Koin docs). Metro 1.x is the compile-time-safe alternative (UNVERIFIED; newer). Koin is chosen for its smaller learning curve and no KSP per target. Lost: compile-time graph checks. Add a `verify()` module test |
| Room 2.6.1 with kapt, `SupportSQLiteDatabase` migrations `MIGRATION_12_13` … `MIGRATION_17_18`, `fallbackToDestructiveMigrationFrom(1..11)`, exported schemas 13–18, `withTransaction`, `PagingSource` in `CollectionDao`, recursive CTEs | **Room 2.8.x KMP** with KSP per target and `BundledSQLiteDriver` | swap | M | Room has been KMP since 2.7.0; 2.8.5 is stable. VERIFIED, [Room releases](https://developer.android.com/jetpack/androidx/releases/room). Room 3 (`androidx.room3`, 3.0.3) is KMP-first but renames APIs. VERIFIED, [Room 3 releases](https://developer.android.com/jetpack/androidx/releases/room3). Stay on 2.8 and take Room 3 later as its own step. Migrations take a `SQLiteConnection`, and DAOs must be `suspend` or `Flow` outside Android. VERIFIED, [Room KMP guide](https://developer.android.com/kotlin/multiplatform/room). The six migrations are plain `execSQL`, so the SQL is unchanged and only the receiver changes. `withTransaction` → `useWriterConnection { immediateTransaction { } }`. `@Insert(onConflict = REPLACE)` keeps the REPLACE semantics `cached_nodes` relies on (findings #8); Stage 0 tests this on iOS. Bundled SQLite gives both platforms the same SQLite version for the recursive CTEs. `MigrationTestHelper`'s KMP form is UNVERIFIED |
| Paging 3 (`PagingData.from(list)`, `collectAsLazyPagingItems` in 9 UI files, one Room `PagingSource`) | **Drop Paging**: plain `List`/`StateFlow` into `LazyColumn`/`LazyVerticalGrid` | rewrite (small) | L | Every pager wraps an in-memory list (VERIFIED: `SmugViewModel.photosFlow` ends in `PagingData.from(sorted)`), so Paging adds nothing. `paging-compose` has supported iOS since 3.4.0. VERIFIED, [Paging releases](https://developer.android.com/jetpack/androidx/releases/paging). So keeping it is a fallback, not a blocker |
| Coil 2.5 (`AsyncImage`, `ImageRequest`, `imageLoader`, `ImageLoaderFactory`, cache-less `@Named("images")` call factory, `respectCacheHeaders(false)`) | Coil 3.x (`coil3.compose.AsyncImage`) with `coil-network-ktor3` on iOS (and OkHttp or Ktor on Android) | swap | L | 3.6.3 is current, and `iosX64` was removed in 3.5. VERIFIED, [changelog](https://coil-kt.github.io/coil/changelog/). Coil 3 ignores Cache-Control by default, which is what `respectCacheHeaders(false)` did. VERIFIED, [Coil network](https://coil-kt.github.io/coil/network/). Give it its own Ktor client with the retry plugin and no API cache (keeps R-37) |
| Coroutines / Flow / StateFlow | unchanged | reuse | L | Already multiplatform. `Dispatchers.IO` exists on iOS |
| `java.io.File`, `RandomAccessFile`, `MessageDigest` (MD5 check of originals), `java.time`, `ConcurrentHashMap`, `Atomic*`, `java.net.URI/URLDecoder` (30 files) | kotlinx-io or okio (both KMP), an MD5 from okio's `ByteString.md5()`, kotlinx-datetime, atomicfu or `Mutex`, Ktor `Url` | swap | M | Mechanical, but `OfflineStore`'s `.part` file plus rename and the free-space check (1 GiB) need a per-platform `freeBytes()` |
| WorkManager: `OfflineFilesWorker`, `NewGalleryWorker`, `OfflineScheduler` (unique work, network constraints), `NewGalleryScheduler` (6 h periodic) | Android: unchanged. iOS: a `BackgroundScheduler` interface; downloads in the foreground plus `BGProcessingTask`; new galleries via `BGAppRefreshTask` plus a check on app open | rewrite per platform | **H** (product) | See 4.5 and 4.6. Keep the *runners* (`NewGalleryRunner`, `OfflineDownloader` pass) common; only scheduling differs |
| `EncryptedSharedPreferences` (`PasswordStore`) | Android: keep for now. iOS: Keychain (`kSecClassGenericPassword`, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) behind the existing `PasswordStore` interface | rewrite per platform | M | security-crypto deprecated all its APIs (1.1.0-alpha07, then 1.1.0 stable) with no further releases. VERIFIED, [security releases](https://developer.android.com/jetpack/androidx/releases/security). The Android side needs its own migration later (Keystore + DataStore). That is a one-way data move and needs the owner. `multiplatform-settings`' `KeychainSettings` is experimental (VERIFIED, GitHub). A ~60-line `iosMain` Keychain class is simpler |
| `SharedPreferences` (`smugview_prefs`, search status, `OfflineSettings`, `SyncStateStore`) | multiplatform-settings (`NSUserDefaults` on iOS), or DataStore KMP | swap | L | Using `NSUserDefaults` puts reason `CA92.1` in the privacy manifest (4.2) |
| MediaStore save to `Pictures/SmugView` (`PhotoDownloader`) | iOS: `PHPhotoLibrary` with **add-only** access (`NSPhotoLibraryAddUsageDescription`) | rewrite per platform | M | See 4.7 |
| `JpegStrip` (pure byte-level EXIF strip) plus `ExifInterface` for orientation | Reuse `JpegStrip` in common; read orientation inside it (APP1 parse) | reuse | L | Do **not** rely on `kCGImageMetadataShouldExcludeGPS`. Apple: it "doesn't filter any proprietary location data in the manufacturer's EXIF MakerNote", and it leaves serial numbers and the owner name (SMUGMUG.md L7). VERIFIED, [ImageIO](https://developer.apple.com/documentation/imageio/kcgimagemetadatashouldexcludegps) |
| Share (`Intent.ACTION_SEND`, FileProvider) | `expect fun shareLink/shareImage`; iOS `UIActivityViewController` | rewrite per platform | L | |
| QR (ZXing core) | **QRose** (KMP, MIT) | swap | L | 1.1.2, iOS targets listed (UNVERIFIED, klibs.io) |
| Palette (theme primary from a photo) | Drop, or a ~40-line common dominant-colour function | rewrite (small) | L | |
| Media3 ExoPlayer video | `expect @Composable VideoPlayer`; iOS AVPlayer through `UIKitView` | rewrite per platform | M | |
| Casting: Google Cast framework, `MediaRouter`, SSDP M-SEARCH on `MulticastSocket` 239.255.255.250:1900, Roku ECP HTTP to :8060, Fire TV DIAL, `WebCompanionServer` (`ServerSocket`, port 8080) | iOS v1: **none**. Later: Google Cast iOS SDK; Roku/Fire TV only if the multicast entitlement is granted *and* the ECP terms allow it | rewrite per platform | **H** | See 4.8 |
| `CrashRecorder` (`ApplicationExitInfo`), `DiagnosticsInitializer`, report files with `adb pull` | iOS: Kotlin `setUnhandledExceptionHook` writing the ring buffer to a file; reports exported through the share sheet (no adb) | rewrite per platform | M | Release logging rule (findings #7) applies the same way |
| Navigation Compose 2.7.7 (string routes, `navArgument`) | `org.jetbrains.androidx.navigation:navigation-compose` (KMP) | swap | L | VERIFIED as the documented artifact, [kotlinlang](https://kotlinlang.org/docs/multiplatform/compose-navigation-routing.html). `Routes.kt` uses `android.net.Uri.encode` → Ktor `encodeURLParameter` |
| Theme ("always dark", `SmugViewTheme` sets a `DeepDarkBackground` status bar with light icons, finding #25) | Shared Material 3 theme; iOS status bar style `.lightContent` from the shell | reuse + small per platform | L | Material Icons Extended: the app uses 60 distinct icons. Google stopped shipping the extended set after Compose 1.7 (UNVERIFIED). Copy the 60 `ImageVector`s (Apache 2.0) into `shared` |
| `BackHandler` (6 files) | CMP back handling; iOS has no back button, so add an edge-swipe gesture | swap | M | Every screen's back path needs a visible control on iOS. 4.2 reviewers check navigation |
| Diagnostics adb workflow, `Toast` (20 files) | A shared snackbar/message `Flow` | swap | L | Also fixes "toast from a view model" (`SmugViewModel` uses `Toast.makeText(getApplication())`) |

---

## 4. iOS risks and App Store rules

### 4.1 App Review: 4.2 minimum functionality, 5.2.2 third-party services, naming (4.1(c), 5.2.1)

**What Apple requires (VERIFIED, [guidelines](https://developer.apple.com/app-store/review/guidelines/)):**
- **4.2:** the app needs features "beyond a repackaged website". SmugView is a native client with search, tags,
  collections, offline saves and a zoom viewer, so it should clear 4.2 if the first release is not reduced to a
  browser of links. UNVERIFIED (a reviewer's judgement).
- **5.2.2:** "If your app uses, accesses … or displays content from a third-party service, ensure that you are
  specifically permitted to do so under the service's terms of use. **Authorization must be provided upon request.**"
- **4.1(c):** "You cannot use another developer's icon, brand, or product name in your app's icon or name, without
  approval."

**What SmugMug's API terms say (VERIFIED, [terms](https://api.smugmug.com/api/v2/doc/pages/termsandconditions.html)):**
- The app name "must not include the SmugMug name, logo or other SmugMug Mark".
- "All calls … must use a unique API Key", and you are "not permitted to reuse an API Key in more than one
  Application".
- Keys must be kept secret.
- No fees "specifically for the functionality provided by the SmugMug API".
- A privacy policy must be posted.

**What this means here:**
- PUBLISH.md §5 records that SmugMug confirmed the name is not a policy risk *for Play*.
- **Before submission: get SmugMug's confirmation in writing (an email is enough).** It should cover:
  - (a) "SmugView" on iOS.
  - (b) A **separate API key for the iOS app** (recommended, since it removes the "one key, more than one
    Application" question and gives iOS its own rate-limit bucket).
  - (c) Permission Apple can be shown on request (5.2.2).
- The full description must keep the non-affiliation disclaimer.

### 4.2 Privacy manifest, required-reason APIs and App Privacy labels

**Privacy manifest:**
- Since 2024-05-01, uploads that use a required-reason API without describing it in `PrivacyInfo.xcprivacy` are not
  accepted. VERIFIED, [Apple](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api).
- This app will use:
  - `NSUserDefaults` (reason `CA92.1`).
  - File timestamp and size APIs for `OfflineStore` and the diagnostics files (`C617.1`).
  - Disk space for the 1 GiB check (`E174.1` or `85F4.1`).
- The reason codes are UNVERIFIED because the code table did not render. Confirm them in Xcode's privacy report.
- Kotlin/Native and Ktor are not on Apple's third-party SDK list (VERIFIED, [list](https://developer.apple.com/support/third-party-SDK-requirements/)).
  The app's own manifest must still cover API calls made by the compiled Kotlin code.

**App Privacy label:**
- Apple's definition of "collect": "transmitting data off the device … for a period longer than what is necessary to
  service the transmitted request in real time". VERIFIED, [app privacy details](https://developer.apple.com/app-store/app-privacy-details/).
- SmugView sends requests only to SmugMug and has no developer server, analytics or ads (VERIFIED in
  `build.gradle.kts`; PUBLISH.md §4 data-safety answers).
- So the expected answer is **"Data Not Collected"**, matching the Play data-safety form ("no data collected/shared …
  beyond SmugMug itself"). UNVERIFIED reading of Apple's rule. Check it against the SmugMug-as-third-party question in
  App Store Connect's own help text when filling it in.
- **Adding a push server (4.5, option ii) would break this answer**, because saved gallery passwords or nicknames
  would leave the device.

### 4.3 The API key in the binary, and saved passwords

**API key:**
- Today the key is compiled into `BuildConfig` and R8-obfuscated (AGENTS.md).
- On iOS it would be a Kotlin constant in the framework, and anyone with the IPA can extract it. That is the same
  exposure as Android.
- SmugMug's "keep keys secret" term cannot be fully met by any client-only app (UNVERIFIED reading).
- A separate iOS key (4.1) limits the blast radius.
- In CI, inject the key from a GitHub secret into a generated Kotlin file at build time. Never `echo` it, and turn on
  masking (CLAUDE.md).

**Saved gallery passwords:**
- On iOS these go to the Keychain with `ThisDeviceOnly`, so they are not in iCloud Keychain sync or device-transfer
  backups.
- That matches Android's `data_extraction_rules.xml` (R-54).

### 4.4 Sign-in details for App Review

2.1(a) asks for "demo account info … if your app includes a login" (VERIFIED). SmugView has no login. The review notes
should say what was given to Play:
- No account is needed.
- Example public sites to type: `smugmugfilms`, `Tutorial`, `corvettemuseum` (AGENTS.md featured-sites rule).
- Password galleries are optional, and the password is entered by the user.
- Do not give a password for the owner's private Family folder.

### 4.5 Background polling for new galleries (the hard product difference)

**Android today (VERIFIED, `NewGalleryScheduler`, design new-gallery-notification.md):** a periodic WorkManager job
runs every 6 hours with a 2 hour flex. It unlocks the saved password roots, reads `user!albums?Order=Date Added
(Descending)` page 1 and posts a notification.

**iOS facts:**
- `BGAppRefreshTask`: "The system decides the best time … up to 30 seconds of background runtime". VERIFIED,
  [Apple](https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app).
- In practice the run rate follows how often the user opens the app. A rarely used app may get no runs at all.
  UNVERIFIED; the forum and blog consensus is that runs are not guaranteed.
- Silent push: "don't try to send more than two or three per hour", about 30 s of run time, and it needs a server.
  VERIFIED, [Apple](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app).

**Options:**
1. **`BGAppRefreshTask` only, plus a check whenever the app comes to the foreground (recommended).**
   - Zero infrastructure and no privacy change.
   - The 30 s budget fits one unlock per saved root plus one `user!albums` page per followed site. Measure this in
     Stage 3 with a real Family unlock.
   - Product difference: on iOS the Hub switch reads "Notify me of new galleries. iOS decides when to check, often a
     few times a day if you use SmugView, less if you don't." The strings need owner approval.
   - A check on open shows a new-gallery banner, so nothing is lost, only delayed.
2. **A silent push from a small backend that polls SmugMug.**
   - Requires a server running 24/7, APNs certificates, and a device-token store.
   - The server would also need each user's **saved gallery passwords** to see password folders (findings #16). That
     moves secrets off the device, makes the App Privacy answer "Data Collected", and adds an operational and security
     burden for a solo developer.
   - Rejected.
3. **Drop the feature on iOS.**
   - Simplest, but the feature is what the v0.9.0 release notes lead with.
   - Option 1 is cheap enough to beat this.

**How it fails:**
- The task never runs: the user learns on the next app open (banner).
- The 30 s budget expires mid-unlock: abandon the run. The known set is written only after the notification, as on
  Android, so nothing is lost.
- Notification permission is denied: same as Android (§4 of that design).
- Low Power Mode or Background App Refresh turned off: the Hub shows "Background App Refresh is off for SmugView", read
  from `UIApplication.backgroundRefreshStatus`.

### 4.6 Offline downloads in the background

**Android (VERIFIED, DESIGN.md §5):** unique work `offline-files` and `offline-files-wifi` with network constraints and
no foreground service.

**iOS tools:**
- Apple recommends **background `URLSession`** for long downloads. VERIFIED, [Apple](https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app).
- `BGProcessingTask` runs at low-activity times, such as overnight while charging. VERIFIED, same page.
- iOS 26's `BGContinuedProcessingTask` starts in the foreground and shows cancellable progress. VERIFIED,
  [Apple](https://developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtask).

**Recommendation:**
- **v1:** downloads run while the app is open and continue in `BGProcessingTask` (network required). The existing
  `OfflineDownloader` pass and row states stay shared.
- Kept galleries say "Saving continues when SmugView is open or your iPhone is charging" (owner approves the string).
- **Later:** a background `URLSession` actual for the per-file transfer. This needs a Swift or `iosMain` delegate that
  completes the `offline_files` row after relaunch, which is the process-death case. That costs 1–2 FTE-weeks.
- The Wi-Fi-only rule (`OfflineSettings`) maps to `allowsExpensiveNetworkAccess = false` /
  `allowsConstrainedNetworkAccess` (UNVERIFIED mapping).

### 4.7 "Pictures/SmugView" and Photos permission

- Add-only access (`NSPhotoLibraryAddUsageDescription`, `.addOnly`) is Apple's privacy-preserving path. VERIFIED,
  [Apple](https://developer.apple.com/documentation/photokit/delivering-an-enhanced-privacy-experience-in-your-photos-app).
- Creating or fetching a named "SmugView" album appears to need full read/write access (UNVERIFIED inference: limited
  mode "can't create or fetch user albums").
- **Recommend add-only, with no album on iOS.** The saved-photo toast then says "Saved to Photos".
- Behaviour difference: photos land in Recents, not in a SmugView album. If the owner wants the album, the app must ask
  for full library access, which is a heavier prompt for a privacy-minded app.

### 4.8 Casting on iOS

**Local network facts** (TN3179, VERIFIED, [Apple](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)):
- Any LAN TCP connection or UDP unicast needs `NSLocalNetworkUsageDescription` and triggers the prompt.
- **UDP multicast or broadcast** (SSDP M-SEARCH, which is how `CastManager.performSsdpDiscovery` finds Roku and Fire
  TV) needs the **`com.apple.developer.networking.multicast` entitlement**, which Apple grants on request.
- Unicast HTTP to a known IP does not need the entitlement.
- The simulator does not enforce local network privacy, so casting is a phone-only check (CLAUDE.md "phone-only means
  real LAN").

**Per target:**
- **Google Cast:** the iOS sender SDK (4.8.4 or later) needs `NSBonjourServices` (`_googlecast._tcp`,
  `_<APPID>._googlecast._tcp`) and the local-network description, and guest mode is gone. VERIFIED,
  [Google](https://developers.google.com/cast/docs/ios_sender/ios_permissions_changes).
  It is feasible, but it is a CocoaPods/SPM dependency linked into a KMP app, about 2–3 FTE-weeks (UNVERIFIED estimate).
- **Roku:** Roku's ECP document says **"ECP commands may not be sent from 3rd-party platforms (for example, mobile
  applications)"**, and keypress needs "Control by mobile apps" enabled. VERIFIED, [Roku ECP](https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md).
  **This applies to the shipping Android app too.** It is not an iOS-only problem and should become a findings row.
  This design does not edit findings.md.
- **Fire TV (DIAL):** discovery is SSDP, so it needs the entitlement.
- **Web Companion server on iOS:** Ktor's CIO server lists iOS targets (UNVERIFIED for iOS runtime behaviour). It would
  serve only while the app is in the foreground.
- **AirPlay:** `AVRoutePickerView` covers audio and video only. VERIFIED, [Apple](https://developer.apple.com/documentation/avkit/avroutepickerview).
  For photos, the user mirrors the screen. An app can present on an external display scene, and from iOS 27 that needs
  a `UISceneAccessory`. VERIFIED, [Apple](https://developer.apple.com/documentation/uikit/presenting-content-on-a-connected-display).
  Screen mirroring of the full-screen viewer works with no code.

**Recommendation:**
- iOS v1: no cast button. AirPlay screen mirroring is the documented answer in the App Store description.
- v1.1: Google Cast.
- Roku and Fire TV: only after the entitlement and Roku's term are resolved.

### 4.9 Risk: HTTP cache and offline semantics (highest technical risk)

**What offline browsing depends on today (VERIFIED, `CachePolicy.kt`, `AppModule`):**
- OkHttp's disk cache serves `only-if-cached, max-stale=7d` responses when offline.
- A synthetic 504 is excluded from retry (`isSyntheticCacheMiss`).
- Online reuse is limited by the cookie epoch (R-35, R-36).
- A network interceptor rewrites SmugMug's `no-store` into `max-age=300`.

**Why the Ktor plugin is not enough:**
- Ktor's `HttpCache` has no `max-stale` and does not store `no-store` responses (VERIFIED in its source).
- The Darwin engine's own NSURLCache is a different cache with different rules.

**Design:**
- One common `ApiResponseCache`: key = URL, body, `sentAtMs`, `receivedAtMs`, stored in files under the app cache dir
  with an LRU cap of 50 MB.
- Two plugins, an application plugin (the `CachePolicy` rules) and a store step. They replace OkHttp `Cache` on
  **both** platforms, so Android and iOS behave identically.
- Engine caches are turned off.

**How it fails:**
- After the update, the old OkHttp cache is not read, so offline browsing of pages cached *before* the update is lost
  once. `cached_nodes` and saved photos are unaffected. Decide whether to accept this (decision 7.10).
- A corrupt cache file is treated as a miss and deleted.
- With the disk full, nothing is stored and browsing still works online.

**Tests:** the existing `CachePolicyScenarioTest`, `ForcedRefreshBypassesCacheTest`, `ImageDetailsOfflineTest` and
`HttpClientsTest` are the oracle. Each must pass on the old stack and on the new one before the old one is deleted.

### 4.10 Risk: Gson-to-kotlinx nullability

Gson creates objects without constructors, so a non-null Kotlin field missing from the JSON becomes `null` at runtime
and fails only where it is used. kotlinx.serialization throws `MissingFieldException` at parse time.

**Plan:** a JVM test (`ModelParityTest`) parses **all 171 recorded snapshots** with both libraries and compares the
resulting objects field by field. It then flips each field that is ever missing to nullable or defaulted.

**How it fails:** the snapshots are gitignored and embed the live key (AGENTS.md), so this test runs only on the
owner's machine, not in public CI. Mark it with an assumption guard (skip when the folder is absent), and record its
run and count in findings.md when it passes. A field that is missing in a shape no snapshot covers still throws in
production. Mitigate by making every non-key field nullable with a default, and keep the `Uris` transformer.

### 4.11 Other iOS points

- **Cleartext / ATS:** SmugMug traffic is HTTPS. Only the Web Companion and Roku ECP use LAN HTTP, and they are not in
  iOS v1. If added later, use `NSAllowsLocalNetworking` (UNVERIFIED key behaviour).
- **Export compliance:** set `ITSAppUsesNonExemptEncryption = NO`. HTTPS through the OS (URLSession, which Ktor
  Darwin uses) is typically exempt. VERIFIED, [Apple](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations).
- **Sign in with Apple:** not needed, because there are no accounts (the rule applies to apps with third-party logins).
  UNVERIFIED citation.
- **SDK and minimum iOS:**
  - Uploads must use Xcode 26 and the iOS 26 SDK since 2026-04-28. VERIFIED, [Apple](https://developer.apple.com/news/upcoming-requirements/).
  - Compose Multiplatform needs iOS 14 or later (VERIFIED). The Cast SDK needs iOS 16 or later (UNVERIFIED).
  - **Recommend iOS 17 minimum.** It covers almost all active devices (UNVERIFIED) and keeps `.addOnly` Photos and
    modern background APIs simple.
- **iPad:** CMP renders on iPad, but the layouts are phone-designed. If iPad is supported, a 13" iPad screenshot is
  required. VERIFIED, [screenshot specs](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications).
  **Recommend iPhone-only for v1** (`TARGETED_DEVICE_FAMILY = 1`). iPhone apps still run on iPad in compatibility
  mode (UNVERIFIED for current iPadOS).
- **TestFlight:** up to 100 internal and 10,000 external testers. The first build to an external group goes through
  review, and builds last 90 days. VERIFIED, [Apple](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview).
- **Always dark:** set `UIUserInterfaceStyle = Dark` in Info.plist so system sheets (share sheet, alerts) match. Check
  every screen in both system themes (CLAUDE.md).

---

## 5. Auto-publish pipeline

### 5.1 What must exist

| Item | How | Who / when |
|---|---|---|
| Apple Developer Program membership | $99/year. VERIFIED, [Apple](https://developer.apple.com/programs/). Individual enrolment uses the owner's Apple ID and identity check; an organisation needs a D-U-N-S number (UNVERIFIED) | **Owner, once.** Enrolment approval can take days (UNVERIFIED) |
| Bundle ID | Proposal: `com.smugview.app`, matching Android's `applicationId` | Registered by fastlane `produce` (needs an Apple ID login, not an API key: "Partial" support, VERIFIED, [fastlane](https://docs.fastlane.tools/app-store-connect-api/)), or by hand in the portal. **Owner, once** |
| App record in App Store Connect | "Don't use this API to create new apps; instead, create new apps on the App Store Connect website." VERIFIED, [Apple](https://developer.apple.com/documentation/appstoreconnectapi/apps) | **Owner, once, in the browser** (works from Windows) |
| App Store Connect API key (`.p8`, key ID, issuer ID), Team key with App Manager role | Created in App Store Connect → Users and Access → Integrations. Stored as GitHub secrets `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8` (base64) | **Owner, once.** match/sigh provisioning needs a Team key (VERIFIED, fastlane page above) |
| Signing | **fastlane `match`** with a **private** git repo (for example `Wizoi/smugview-certs`), encrypted with `MATCH_PASSWORD`. CI runs `match(type: "appstore", readonly: true)`. Alternative: Xcode cloud-managed signing via `-allowProvisioningUpdates` with the API key (fewer moving parts, less control; UNVERIFIED in a KMP setup) | First `match` run creates the distribution certificate and profile. It can run in a one-off manual CI job, **so no Mac is needed** (UNVERIFIED but standard) |
| Xcode project | `iosApp/` generated from a checked-in **XcodeGen** `project.yml`, so the owner never hand-edits `project.pbxproj` on Windows | Stage 3 |
| Privacy policy URL, support URL, category (Photography), age rating questionnaire, App Privacy answers, export compliance | App Store Connect web; `deliver` can push some metadata | **Owner, once** (the age rating and privacy questionnaires are web forms; UNVERIFIED whether the API covers them all) |
| Screenshots | iPhone 6.1" Dynamic Island size required, plus 13" iPad only if iPad is supported. VERIFIED (the page's notes are partly inconsistent; check in App Store Connect). Capture on the simulator in CI (`snapshot` or `xcrun simctl io booted screenshot`) after seeding public sites | Automated after Stage 4. Owner approves the set once |

### 5.2 CI choice

| | GitHub Actions macOS | Xcode Cloud | Codemagic | Own or cloud Mac |
|---|---|---|---|---|
| Cost | **Free for a public repo** (standard runners). Private: $0.062/min macOS. VERIFIED, [GitHub](https://docs.github.com/en/billing/concepts/product-billing/github-actions) | 25 compute hours/month included; 100 h $49.99/mo. VERIFIED, [Apple](https://developer.apple.com/xcode-cloud/) | 500 free macOS M2 min/month (personal), then $0.095/min. VERIFIED, [Codemagic](https://codemagic.io/pricing/) | Mac mini about $599–799 (UNVERIFIED). AWS EC2 Mac has a 24 h minimum per allocation (VERIFIED, [AWS](https://aws.amazon.com/ec2/instance-types/mac/)) |
| Fits a Gradle + KMP build | Yes, full control; caches `~/.gradle` and `~/.konan` | Possible, but needs a `ci_post_clone.sh` to install a JDK and run Gradle; less control (UNVERIFIED friction) | Yes, good KMP and Flutter support | Yes |
| Secrets on a public repo | Encrypted secrets; not passed to fork PRs; environment approvals | Inside Apple | Inside Codemagic | Local |

**Recommendation: GitHub Actions.** It is free here, it already hosts the repo, and environments with required
reviewers give the manual gate. Required reviewers on environments for public repos on a free plan is UNVERIFIED;
**check it in Stage 4, and if it is not available, the gate is a `workflow_dispatch` job the owner starts by hand**.

The repo being public means the workflow file and fastlane config are public. That is fine. Secrets must never be
printed: the build uses `::add-mask::`, and the API key goes into a generated file, not the command line.

**Build-time estimate:**
- A Kotlin/Native release link is "an order of magnitude longer" than debug. VERIFIED, [kotlinlang](https://kotlinlang.org/docs/native-improving-compilation-time.html).
- Assume 15–30 min per iOS release build with warm caches (UNVERIFIED; Stage 0 measures it).
- Ten release builds a month plus PR simulator tests is about 600–900 macOS minutes: free on a public repo, about
  $40–55/month if the repo goes private.

### 5.3 Workflow

The trigger is a tag `v<versionName>`, for example `v1.0.0`, pushed by the owner. Merges to `main` run only the test
jobs.

```
on: push (tags v*), pull_request, workflow_dispatch
jobs:
  jvm-tests      (ubuntu)  ./gradlew testDebugUnitTest :shared:jvmTest   # the 909 tests; grep BUILD SUCCESSFUL (R-69)
  ios-tests      (macos)   ./gradlew :shared:iosSimulatorArm64Test       # commonTest on iOS
  ios-build      (macos, needs both, tags only)
       fastlane ios build   -> match(readonly) ; generate Secrets.kt from ASC/SmugMug secrets ;
                               xcodegen ; build_app(export_method: app-store) ; archive .ipa + dSYM as artifact
  testflight     (macos, environment: testflight)          # internal testers only; no Apple review involved
       fastlane ios beta    -> upload_to_testflight(changelog: <release notes>, distribute_external: false)
  submit-review  (macos, environment: app-store-review  <-- REQUIRED REVIEWER = owner, manual click)
       fastlane ios submit  -> deliver(submit_for_review: true, automatic_release: false,
                                       skip_screenshots: <unless changed>, release_notes from repo)
  (release is NOT automated: the owner presses "Release" in App Store Connect after approval,
   or a later gated job `release` in environment app-store-release)
```

- **Two gates, matching CLAUDE.md and AGENTS.md "Silent Mode EXCLUSIONS":**
  - Nothing reaches Apple's review or the public without the owner's click in that moment.
  - TestFlight internal upload is also gated (`testflight` environment) until the owner says it may run unattended.
    Recommended: keep it gated for the first three releases.
- **Android on the same rail (optional, later):** a `play-internal` job running `publishReleaseBundle` behind its own
  environment gate. Today's rule ("publishing to Play needs explicit confirmation in the moment") is met by the
  environment approval, but **the owner must say so explicitly** before this replaces running `publish-app` by hand.
- **Versioning:** move `versionCode`/`versionName` out of `app/build.gradle.kts` into a root `version.properties`
  read by Gradle.
  - A Gradle task writes `iosApp/Config/Version.xcconfig` with `MARKETING_VERSION = versionName` and
    `CURRENT_PROJECT_VERSION = versionCode`.
  - One number on both stores. Play and Apple each require it to increase, so it does (VERIFIED for Play in PUBLISH.md;
    for Apple, `CFBundleVersion` must increase per version, UNVERIFIED detail).
  - A rejected or failed upload still burns the number on both (CLAUDE.md).
- **Release notes:**
  - Keep the single source `app/src/main/play/release-notes/en-US/<versionCode>.txt`.
  - Lane `beta` passes it as `changelog`, and lane `submit` copies it to `fastlane/metadata/en-US/release_notes.txt`.
    `deliver` reads `fastlane/metadata/<locale>/release_notes.txt`. VERIFIED, [deliver](https://docs.fastlane.tools/actions/deliver/).
  - Apple allows longer text than Play's 500 characters (UNVERIFIED limit), so no change is needed. The notes must not
    mention Android-only features (casting) in an iOS release: add an optional `<versionCode>.ios.txt` override.
- **Uploading without macOS:** the App Store Connect API has a build-upload endpoint (`POST /v1/buildUploads`).
  VERIFIED, [Apple](https://developer.apple.com/documentation/appstoreconnectapi/post-v1-builduploads). But building
  and signing still need macOS and Xcode (VERIFIED, [kotlinlang](https://kotlinlang.org/docs/multiplatform/quickstart.html)),
  so CI on macOS remains necessary.

### 5.4 Automated vs manual

**Automated:**
- Tests, build, signing (after the first `match`), TestFlight upload, metadata text, release notes and screenshot
  upload.
- Submit for review, behind the gate.

**Manual, once (owner):**
1. Enrol in the Apple Developer Program.
2. Create the App Store Connect app record (bundle ID, name, SKU, primary language).
3. Create the API key.
4. Create the private `match` repo.
5. Add the GitHub secrets.
6. Fill in App Privacy, the age rating, category, pricing (free), privacy policy URL and support URL.
7. Answer export compliance once (or rely on the Info.plist key).
8. Write the review notes (4.4).
9. Request the multicast entitlement, only if casting is wanted.
10. Email SmugMug (4.1).

**Manual, every release:** the approval click(s), and pressing Release after Apple approves. Approving the first
external TestFlight group's review is also manual.

---

## 6. Staged plan

Every stage keeps the Android app shippable and the full unit suite green, run by the lead (CLAUDE.md). Each library
swap ships in a normal Android release with the usual phone checks, before any iOS work depends on it. Implementing
agents get the 45-minute box per step.

**Test strategy (decided up front):**
- **The 909 tests stay in `:app/src/test` and keep running there**, against `:shared`, which `:app` depends on. That
  avoids moving Robolectric, Mockito and work-testing into a KMP test source set.
- Tests that are already pure (305) move to `:shared/src/commonTest` **one class at a time**, rewritten from JUnit4 and
  Mockito to `kotlin.test` with fakes. They then also run on the iOS simulator.
- What breaks if a Robolectric test moves:
  - `Room.inMemoryDatabaseBuilder(context)`.
  - `MigrationTestHelper` reading schemas from debug assets.
  - `ApplicationProvider`.
  - `TestListenableWorkerBuilder`.
  - `createComposeRule`. CMP has `runComposeUiTest` for common UI tests (UNVERIFIED API name for the current version).
- These stay JVM-only until there is a reason.

### Stage 0: Spike (1–1.5 FTE-weeks; $0 on GitHub's free public macOS runners, or Codemagic's free 500 min)

**Goal:** retire the unknowns before any refactor.

**Work** on a throwaway branch `spike/ios`, nothing merged:
1. **Real data first:** `ModelParityTest` over the 171 snapshots (4.10), on Windows/JVM. Record how many fields were
   ever missing.
2. **The `shared` module:**
   - Targets: `androidTarget`, `jvm`, `iosArm64`, `iosSimulatorArm64`.
   - Copies of `ResponseModels` (kotlinx), the `user/{nick}` and `node/{id}!children` calls over Ktor, and Room 2.8 KMP
     with `Entities` and DAOs.
   - The six migrations ported to `SQLiteConnection`.
3. iOS simulator tests on a GitHub macOS runner:
   - (a) Parse a recorded `!children` page (MockEngine; no key in CI). One optional live call with the key from a
     secret, `workflow_dispatch` only.
   - (b) Open a schema-12 database built from the exported JSON and migrate to 18, checking that the
     `offline_collections` rows survive.
   - (c) `insertNodes` twice with the same NodeID gives one row (REPLACE, findings #8).
   - (d) The recursive-CTE dot query returns the same rows as on Android.
4. A minimal Compose Multiplatform screen listing one site's folders, run in the simulator (screenshot artifact).
5. Measurements: framework size, release-link time with cold and warm caches, IPA size.
6. ~~Email SmugMug (4.1). Owner sends; agent drafts.~~ Dropped by the owner, 2026-10-29.

**Verification:** a CI run link with all four simulator tests green, a measurements table appended to this doc, and
the parity count.

**How it fails:**
- The migration chain does not run under the bundled driver: stay on the platform driver on Android
  (`AndroidSQLiteDriver`) and investigate. This blocks iOS persistence, so stop and report.
- Release link takes over 45 min: limit targets, split frameworks or use a bigger runner.
- Parity shows many missing fields: Stage 1c grows.
- SmugMug says no to a second key or the name: stop iOS work (decision 7.1).

### Stage 1: KMP on Android only (6–8 FTE-weeks; several Android releases)

Each step is one commit, with red-first tests where behaviour could change.

| Step | Work | Verification | How it fails |
|---|---|---|---|
| 1a | Gradle: Kotlin 2.0.20 → current 2.x, kapt → KSP, add the empty `:shared` KMP module, `:app` depends on it | Full suite green; release AAB builds; R8 keep rules unchanged | A kapt→KSP Room or Hilt difference. Keep Hilt on KSP until 1f |
| 1b | Move the 51 import-clean files (6,554 lines) to `commonMain`. Add `Log`/`BuildConfig.DEBUG` → `SmugLog` + `AppConfig`, `java.time` → kotlinx-datetime, atomics, file IO → kotlinx-io | Suite green; no behaviour change | Hidden JVM API in "clean" files (`String.format`, `java.util.Locale`). Compile errors show them |
| 1c | Gson → kotlinx.serialization, with `ModelParityTest` as the gate | Parity test plus suite green; release build smoke test (AGENTS.md: "assembleRelease compiling clean proves shrinking works, NOT that JSON still parses") | A missing field throws in production. Keep everything nullable with defaults; owner phone check of galleries, search and a password folder |
| 1d | Retrofit/OkHttp → Ktor (OkHttp engine on Android). Retry plugin, `SessionCookieJar` → `CookiesStorage`, `ApiResponseCache` (4.9), `FakeSmugMugServer` core split, `ApiContractTest` on MockEngine, `HttpException` → `ApiException(code)` | The cache, retry, unlock and offline scenario tests pass on old and new; emulator airplane-mode check (`scripts/emulator-checks.sh`) | Offline regressions. **Needs owner sign-off (decision 7.10) because the HTTP cache is reset once** |
| 1e | Room 2.6.1 → 2.8 KMP: migrations to `SQLiteConnection`, `withTransaction` replaced, `PagingSource` removed | `MigrationTestHelper` tests (24) and DAO tests green; upgrade-install over v0.9.0 on the emulator keeps collections and saved photos | Data loss on upgrade. Install over the Play build on the emulator before release |
| 1f | Hilt → Koin, `AndroidViewModel` → `ViewModel`, `Toast` → message flow, `SharedPreferences` → multiplatform-settings (same file names, so no migration) | Suite green (Mockito view-model tests rewired); Koin `verify()` test | A runtime-missing binding. The `verify()` test catches it |
| 1g | Coil 2 → 3, Paging dropped, ZXing → QRose, Palette dropped or replaced, 60 icons copied | Screen tests green; both-themes screenshot sweep (`ThemeSweepSmokeTest`) | Image cache behaviour (R-37). Check that a photo never enters the API cache |

### Stage 2: Compose UI into `commonMain` (2–3 FTE-weeks, Android release)

- Move `ui/**` and `ui/viewmodel/**` to `commonMain` and switch to the JetBrains navigation artifact.
- Add `expect` for `VideoPlayer`, `ShareLauncher`, `PhotoSaver`, `SystemBars` and `BackHandler`.

**Verification:** the 69 Compose UI tests green; phone check of the viewer zoom, swipe and video.

**How it fails:** Compose version skew between Jetpack and CMP; pin the CMP version that maps to the current Jetpack
Compose (VERIFIED mapping table, [kotlinlang](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)).

### Stage 3: iOS shell and platform pieces (3–5 FTE-weeks)

- XcodeGen `iosApp`, `MainViewController`, Koin start-up.
- Platform pieces: Keychain `PasswordStore`, Photos add-only saver, `UIActivityViewController`, an AVPlayer
  `VideoPlayer`.
- `BGAppRefreshTask` with a check on open (4.5); downloads in the foreground plus `BGProcessingTask` (4.6).
- Local notifications, crash hook and diagnostics export, `PrivacyInfo.xcprivacy`, Info.plist strings, dark style.

**Verification:**
- `commonTest` green on the simulator.
- A TestFlight internal build on the owner's (or a borrowed) iPhone: browse a public site, unlock Family, save a
  photo, keep a gallery offline in airplane mode, share a stripped picture (check EXIF with `exiftool`), and follow a
  site and see a check run.
- These are phone checks, batched last (owner's memory rule).

**How it fails:**
- The Keychain is not reachable in the background before the first unlock: use `AfterFirstUnlock`.
- The 30 s budget is too short for Family plus several sites: rotate through sites across runs.
- No iPhone available: blocked; see decision 7.5.

### Stage 4: Pipeline (1–2 FTE-weeks)

Section 5: workflows, fastlane lanes, `match` bootstrap, environments with the owner as reviewer, version file, release
notes mapping, simulator screenshots.

**Verification:** a dry run to TestFlight internal from a test tag, approved by the owner, and the
`submit-review` job shown blocked on its gate.

**How it fails:**
- A secret is printed in a log: rotate it (the owner's job, AGENTS.md).
- The environment reviewer is not available on this plan: fall back to `workflow_dispatch`.

### Stage 5: First submission (1–2 FTE-weeks plus Apple's review time)

- App Store Connect forms, screenshots, review notes (4.4).
- An external TestFlight group (needs beta review).
- Then submit through the gate.

**How it fails:**
- Rejection under 4.2 or 5.2.2: answer with SmugMug's written permission and a feature list.
- Rejection for a missing purpose string or privacy-manifest reason: fix and resubmit, which costs a build number.

### Stage 6 (later, separate designs)

- Google Cast on iOS (2–3 FTE-weeks).
- Background `URLSession` downloads (1–2).
- iPad layouts.
- Room 3.
- Android `PasswordStore` off the deprecated security-crypto (needs the owner, one-way).

**Total to the first App Store release: Stages 0–5, about 15–22 FTE-weeks**, with Stage 1 (Android-only refactor) the
largest.

---

## 7. Decisions only the owner can make

1. **OWNER DECISION 2026-10-29: no email to SmugMug.** The owner does not think it is needed, so Stage 0 step 6 is dropped and the Stage 3 gate on a written yes no longer applies. Risk stays recorded: the API terms (4.1) say one key per application and no SmugMug marks in an app name, so a public iOS release could still be challenged later (iOS only; Android unaffected). Original item: **Go/no-go on SmugMug's answer** (4.1): a separate iOS API key, the name "SmugView" on iOS, and written permission
   for Apple. *Recommend: ask in Stage 0 and do not start Stage 3 without a yes.*
2. **Budget:**
   - $99/year Apple Developer Program.
   - CI: $0 while the repo is public. If it ever goes private, about $40–55/month on GitHub, or Codemagic's free 500
     min.
   - *Recommend: GitHub Actions on the public repo.*
3. **Scope of iOS v1:** *Recommend a smaller first release:* browse, search, tags, collections, offline saves, share,
   save to Photos, and new-gallery checks (best effort). No casting and no iPad layouts.
4. **Casting on iOS:** *Recommend:* none in v1; Google Cast in v1.1; Roku and Fire TV only after the multicast
   entitlement and Roku's "no mobile apps" ECP term are resolved. **Separately: decide whether Roku casting on Android
   needs a findings row now** (4.8).
5. **A Mac:** an iOS build needs macOS. Without one, every iOS bug is debugged through CI logs and TestFlight.
   - *Recommend buying or borrowing a Mac mini before Stage 3* (about $599–799, UNVERIFIED price).
   - *Also needed: an iPhone for the phone checks.*
6. **New-gallery notifications on iOS:** *Recommend* `BGAppRefreshTask` plus a check on open, with honest Hub text. No
   push server (4.5).
7. **Minimum iOS and devices:** *Recommend iOS 17, iPhone only for v1.*
8. **Name and bundle ID:** *Recommend* "SmugView" (subject to 7.1) and `com.smugview.app`.
9. **Photos album:** *Recommend add-only saving, no "SmugView" album on iOS* (4.7).
10. **Accept a one-time loss of the old HTTP cache** on the Android update that ships Stage 1d (4.9). Saved photos and
    the folder tree are kept. *Recommend accept, with a release-note line.*
11. **Pipeline gates:** confirm that an environment approval click counts as the "explicit confirmation in the moment"
    CLAUDE.md requires for TestFlight and App Store submission, and later for Play. *Recommend yes for TestFlight
    internal after three supervised releases; always a click for review and release.*
12. **Model and agent assignment:** per the owner's rule, Opus plans each stage and Sonnet implements. Stage 1d and 1e
    are the risky ones. *Recommend an Opus review of their diffs before commit.*

---

## Stage 0 results (running log, branch `spike/ios`)

### 2026-10-29: steps 1 and 2, the parity check (VERIFIED, measured)

- **Why the original plan changed.** `app/src/test/resources/snapshots/` (174 files) is git-ignored, 100 of them are tiny unlock replies,
  50 more are the same album-keywords answer, and nothing in it covers `user!albums`, `!parents`, `!sizedetails` or video search. So it
  cannot be the parity gate, and a macOS runner could not read it. A new, tracked set was recorded instead.
- **`ParityRecorder`** (opt-in: `PARITY_RECORD=1`, live, anonymous, read-only) calls every GET of `SmugMugApi` with the app's own request
  shapes and saves the answers to `app/src/test/resources/parity/<method>__<label>.json` (23 files, 297 KB; the API key is replaced by
  `TESTKEY`, and a search of the saved files found no key). Nothing from a password folder is in it.
- **`ModelParityTest`** parses each file with Gson into the model the method returns, then walks the object and counts non-null Kotlin
  properties that came back null: the ones kotlinx.serialization would throw on (no default) or fill with a default.
- **Result: 22 of 23 answers are clean (0 THROW, 0 DEFAULT).** The one exception is `searchUsers`: a user such as `mdkahn2` (an account
  with no gallery) has no `Name` and no `Uris.Node`, so `UserData.name` and `UserUris.node` would throw. The app reads only `nickName` and
  `webUri` from search users (`searchPublicSites`), so the Stage 1c change is small: make those two nullable (or give search its own
  model).
- **Limits.** Only anonymous, public answers (no password-folder data, no error bodies, no unlock replies). A field that is absent in
  all 23 answers is not reported. Gson's leniency about types (a number in quotes, a string where an object is expected) is not covered;
  the real kotlinx models in step 3 will show those.
