# Design: Phase 1: see what's happening

Status: **APPROVED with amendments (owner, 2026-09-30)** — see §0. Written 2026-09-30 by the planning agent.
Scope: review §4 Phase 1 ([whole-app review](../review/2026-09-29-whole-app-review.md)): R-50, R-51,
R-52 (T9), R-63, R-67 (T8), findings #7 and #11 ([findings.md](../findings.md)).
Borrowed ideas (not code) from TagPup: `tagpup/logs.py` (bounded rotating file log, read from the
end), `tagpup/core/runs.py` (a run tag carried in context, stamped on every line), `tools/doctor.py`
(read-only invariant checks, **counts by default, examples only on request because they name
people**).

## 0. Owner decisions and amendments (2026-09-30) — these override the rest of this doc

| Q | Owner's answer | Effect on this design |
|---|---|---|
| 1 install path | "No idea; I assume upgrading didn't wipe the app data." (Play upgrades keep data.) The phone was not connected when I tried to check the install. | Unresolved mechanics: getting the build onto the phone needs a Play internal-track upload (confirmed at that moment) or a side-by-side debug build. **Decide at step 1b-6, not before.** Code work in 1a–1c doesn't depend on it. |
| 2 gesture/footer | "That could work, but I plug my phone in and Claude can access the files directly, so no UI needed." | **No footer, no 7-tap gesture, no Diagnostics screen, no share-sheet export.** Replace 1b-4/1b-5 with a `DiagnosticsFileWriter` that, after launch sync and after each doctor run, writes `report.txt` (doctor counts + last sync + log tail, already redacted, with the §3.4 leak scan) into `context.getExternalFilesDir("diagnostics")`. Claude pulls it with `adb pull`. **Assumed, to verify on the phone:** adb shell can read `/sdcard/Android/data/com.smugview.app/files/diagnostics` on this Android version (run-as does not work on release builds). If not, add the share-sheet export back. The doctor runs automatically once per launch after the sync (read-only). No `file_paths.xml` change and no `PRIVACY_POLICY.md` change are needed while nothing leaves the device by itself. |
| 3 release logcat | yes | Mirror redacted W/E lines to logcat in release. |
| 4 export contents | sure (recommendation) | Counts by default; node IDs only behind a switch (a debug-only/`adb`-readable second file `report-ids.txt` is NOT written in Phase 1); titles and paths never. |
| 5 red tests | "If a test goes red, fix it." | No `@Ignore`. Fix the fixture, or the bug if it is small and clearly in scope; if a red test exposes a large bug, stop and tell the owner rather than parking it. |

## 1. Goals, non-goals, acceptance

**Goals**
1. A release build writes a bounded, persisted, redacted log that survives process death.
2. Every API call leaves one line with method, redacted path, status, duration, cache source,
   retry count, and the `actionId` of the user action or background run that caused it.
3. Each gallery sync and launch unlock leaves a **sync report**.
4. A read-only **cache doctor** counts tree/index invariant violations in the real DB, in release.
5. A hidden **Diagnostics screen** shows 1–4 and exports one redacted text file via the share sheet.
6. Crash traces are readable (line numbers + archived `mapping.txt`); crashes and ANRs are recorded.
7. Test infrastructure: repository tests on real Room (Robolectric), plus a Room migration harness.

**Non-goals (Phase 1 changes no app behaviour).** No fix to any T1–T7 bug, even ones the new
diagnostics make visible (R-22's skipped unlocks, R-05's null `parentNodeId`, #6's 504 retries).
There's no doctor "repair" button, no remote upload or analytics SDK, and no Room migration or
schema change (§2.7). The 93 `BuildConfig.DEBUG` log sites are not converted wholesale; only the
list in §2.2 is.

**Acceptance check (on the owner's phone, measured, not inferred).** A release build with
versionCode ≥ 26 is installed **over the existing data** (see Q1). Then:
1. Hub → tap the version footer 7× → Diagnostics opens.
2. "Run doctor" lists numbers from the real DB for at least: self-parented rows, `!`-suffixed
   parents, orphan rows/missing parents, Album nodes missing from `cached_albums`, and recent
   index galleries the dot can't see.
3. "Last sync" shows the launch's gallery sync (pages, albums seen, stop reason) and the launch
   unlock run (saved keys, skipped, attempted, each outcome).
4. Export → the share sheet → the file saved to the PC. On the PC,
   `grep -c "$KEY" export.txt` prints 0 (the key comes from `local.properties`; never echo it). The
   same holds for each saved gallery password, typed by the owner. The file contains HTTP lines with
   `actionId`s.
5. A stack trace in the export retraces to file:line with that build's archived `mapping.txt`.

## 2. Architecture

### 2.1 New files

New package `com.smugview.app.diag` (pure Kotlin unless noted):

| File | Contents |
|---|---|
| `Redactor.kt` | `class Redactor { fun redact(text: String): String; fun setSecrets(values: Collection<String>) }`. Rules in §3.2. Thread-safe (`@Volatile` secret list, precompiled regexes). |
| `DiagLog.kt` | `enum class Level { D, I, W, E }`; `class DiagLog(sink: LogSink, redactor: Redactor, clock: () -> Long = System::currentTimeMillis, ringSize: Int = 2000)` with `log(level, cat, msg, t: Throwable? = null)`, `i/w/e(...)`, `recent(n, minLevel): List<String>` (memory ring), `readPersisted(maxBytes: Int): String`, `flush(timeoutMs: Long): Boolean`, `clear()`, `status(): LogStatus` (bytes on disk, dropped lines, sink error or null). Never throws: the body is inside `runCatching`. `object Diag { @Volatile var log: DiagLog = DiagLog.NOOP }` is the static holder used by `SmugLog` and the crash handler. |
| `FileLogSink.kt` | `interface LogSink { fun append(line: String); fun flush(timeoutMs: Long): Boolean; fun read(maxBytes: Int): String; fun clear(); val status: SinkStatus }`. `FileLogSink(dir: File, maxFileBytes = 512 * 1024, queueCapacity = 2000)`: files `diag-0.log` (current) and `diag-1.log` (previous). A single daemon writer thread `SmugView-diag` drains a `LinkedBlockingQueue`. When the queue is full, it drops the **oldest** entry and increments `dropped`. It flushes when the queue is empty. It rotates at `maxFileBytes`: close, delete `diag-1`, rename `diag-0`→`diag-1`, reopen. |
| `DiagContext.kt` | `object DiagContext { val action = ThreadLocal<String?>(); fun newActionId(kind: String): String` (`"$kind#${counter.incrementAndGet()}"`)`; fun element(id: String) = action.asContextElement(id); fun currentActionId(): String? }`. |
| `HttpTelemetry.kt` | `enum class CacheSource { NETWORK, CACHE, CONDITIONAL, SYNTHETIC_504, NONE }`, `fun classify(response: Response): CacheSource` (§2.3), `fun describeUrl(url: HttpUrl): String` (§3.3), `class HttpTelemetry(log: DiagLog) { fun record(o: CallOutcome); fun stats(): HttpStats }`. |
| `SyncReport.kt` | Data classes `SyncRun`, `UnlockAttempt`, enum `SyncKind { GallerySync, LaunchUnlock, SubtreeIndex, FolderTreeSync }`, `StopReason { ReachedKnown, NoNextPage, Error, Cancelled, Interrupted }`. They are serialized by hand with `org.json.JSONObject`, **not Gson**: the `diag` package is not kept by R8 (proguard-rules.pro:25-32 keeps only `data.api`/`data.db`), so Gson reflection would break in release. |
| `SyncReporter.kt` | `interface SyncReporter { fun begin(kind, nickname, actionId): SyncRun?; fun finish(run); fun recordUnlock(a: UnlockAttempt); fun recent(n): List<SyncRun>; companion object { val NOOP } }`. `FileSyncReporter` appends `start`/`end` JSON lines to `files/diagnostics/sync_reports.jsonl` via the `DiagLog` writer thread, and keeps the last 20 runs. Open runs live in a `ConcurrentHashMap<actionId, SyncRun>`. `recordUnlock` attaches to the open run for the current actionId, if any, and always logs a line. A `start` with no `end` reads back as `Interrupted`. |
| `CacheDoctor.kt` | `class CacheDoctor(dao: DoctorDao) { suspend fun run(activeRootId: String?, withExamples: Boolean): DoctorReport }`. `DoctorReport(checks: List<CheckResult>, totalMs, ranDuringSync: Boolean)`, `CheckResult(id, label, count, severity: OK/INFO/WARN/ERROR, examples: List<String>, ms)`. |
| `CrashRecorder.kt` | Installs the default uncaught-exception handler (§4). `recordExitReasons(context)` (API 30+). |
| `DiagnosticsExporter.kt` | `suspend fun export(opts): Uri`: builds the text (§3.4), re-redacts it, runs the leak scan, and writes `cacheDir/diagnostics_export/smugview-diag-<yyyyMMdd-HHmmss>.txt`. It returns a `FileProvider` Uri. |
| `DiagnosticsInitializer.kt` | `@Singleton` and `start()`, called from `SmugViewApp.onCreate`: sets `Diag.log`, installs `CrashRecorder`, logs `app start v<name>(<code>) <buildType> sdk=<n> pid=<p>`, and records exit reasons. On a diag scope it observes `passwordStore.unlockedKeys` → `redactor.setSecrets(passwordStore.all().values + BuildConfig.SMUGMUG_API_KEY)`. It also deletes export files older than 24 h. |

Also:
- `data/db/DoctorDao.kt`: a `@Dao` with SELECT-only `@Query` methods (§2.5). `AppDatabase` gets
  `abstract fun doctorDao(): DoctorDao`. A DAO isn't part of the schema identity hash, so there's
  no version bump (**assumed; step 1b-1 checks that `app/schemas/.../15.json` is byte-identical
  after the build**).
- `di/DiagnosticsModule.kt`: `@Provides @Singleton` for `Redactor`, `DiagLog`
  (`FileLogSink(File(context.filesDir, "diagnostics"))`), `HttpTelemetry`, `SyncReporter`
  (`FileSyncReporter`), `CacheDoctor`, `DiagnosticsExporter`, `DoctorDao`, and a
  `@DiagScope CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- `ui/diagnostics/DiagnosticsScreen.kt` and `DiagnosticsViewModel.kt` (`@HiltViewModel`). This is a
  new ViewModel; nothing is added to the 2,367-line `SmugViewModel`.

### 2.2 Existing files touched (and why)

| File | Change |
|---|---|
| `SmugViewApp.kt:13` | `@Inject lateinit var diagnostics: DiagnosticsInitializer`. Add `override fun onCreate() { super.onCreate(); diagnostics.start() }`. Hilt injects fields in `super.onCreate()`. |
| `util/SmugLog.kt:19-31` | `d` unchanged (debug logcat only, never persisted). `i(tag){}` → persisted at I **only if the message is built** (lambda kept; in release it now runs; used sparingly). New `w(tag, msg, t)`. `e`: **always** `Diag.log.e(...)`; logcat in debug, and in release redacted W/E only (Q3). Fix the stale doc (it says R8 is off; `build.gradle.kts:62` has it on). |
| `data/api/RetryingCallFactory.kt:33-40` | New constructor params `actionIdProvider: () -> String? = { null }` and `onComplete: (CallOutcome) -> Unit = {}`. `RetryingCall` captures `actionId` and `startNanos` in `newCall`/`enqueue` (the caller's thread, §2.3), counts attempts and codes, and calls `onComplete` **exactly once** on each terminal branch: success (line 124 branch), final failure, `onFailure` (line 99), cancellation, and `execute()`. It's wrapped in `try/catch(Throwable)` so telemetry can't break a call. `data class CallOutcome(request, response: Response?, error: IOException?, canceled: Boolean, attempts: Int, codes: List<Int>, durationMs: Long, actionId: String?)` goes in the same file (keeps the class free of `diag`). |
| `di/AppModule.kt:164-198` | Pass `actionIdProvider = DiagContext::currentActionId`, `onComplete = telemetry::record`, and wire `onFinalFailure` (R-50). Delete the `SmugLog.e("SmugMugApiError", …)` at 184-187 (the telemetry line replaces it; otherwise every failure is logged twice). The Toast logic is unchanged. |
| `di/AppModule.kt:33-43` | R-52: debug `HttpLoggingInterceptor(logger = { Log.d("OkHttp", redactor.redact(it)) })`, plus `redactHeader("Cookie")`, `redactHeader("Set-Cookie")`, `redactHeader("Authorization")`. It stays `NONE` in release. Extract it as `internal fun debugHttpLogger(redactor, sink: (String) -> Unit)` so it's testable. |
| `data/repository/SmugMugRepository.kt:50-55` | New last constructor param `private val syncReporter: SyncReporter = SyncReporter.NOOP`. Kotlin defaults on an `@Inject` constructor are fine for Dagger (it uses the full constructor). The 16 test call sites stay unchanged. |
| `SmugMugRepository.kt:623-722` `buildInMemoryGalleryCache` | Additive only: `val actionId = DiagContext.newActionId("sync")`; `withContext(Dispatchers.IO + DiagContext.element(actionId))`; `run = syncReporter.begin(GallerySync, nickname, actionId)`; set `isFirstSync`, `persistedCount`, `stopMarker` (`latestKnown`); per page `pagesFetched++`, `albumsSeen += albums.size`, `albumsNullLastUpdated`, `albumsWithParentNode` (R-05 evidence), `albumsPasswordSecurity`. At the break at 656: `stop = ReachedKnown(page, index)`. At 684: `NoNextPage`. In the catch at 715: `Error(e.javaClass.simpleName + (e as? HttpException)?.code())`, or `Cancelled` if `e is CancellationException`. That's recorded, **not** rethrown; the swallow is today's behaviour, and changing it is Phase 3. Set `changedCount` and `invalidatedParents` in `finally` → `syncReporter.finish(run)`. Replace the `android.util.Log.e` at 716 with `SmugLog.e`. |
| `SmugMugRepository.kt:1069-1093` `unlockNodeResult` / `unlockAlbumResult` | Time the call and `syncReporter.recordUnlock(UnlockAttempt(target = nodeId or albumKey, via = "node"/"album", result, httpCode or null, exception class or null, ms, actionId))`. **Never the password.** Every unlock path (launch, prompt, lineage, tag scan) goes through these two, so all are covered. |
| `SmugMugRepository.kt:735-770` `unlockAndIndexSubtree` | Wrap in an action `subtree#n`, with a `SubtreeIndex` run (root id, nodes fetched, skipped sub-folders). The silent `catch (e: Exception)` at 759 gets `SmugLog.w("sync", "subtree skip $currentNodeId: ${e.javaClass.simpleName}")`. |
| `ui/viewmodel/SmugViewModel.kt:120-134` | New last param `private val syncReporter: SyncReporter = SyncReporter.NOOP` (`SmugViewModelTest.kt:124` unchanged). |
| `SmugViewModel.kt:327-425` `unlockAllSavedPasswords` | `launch(Dispatchers.IO + DiagContext.element(id))` with a `LaunchUnlock` run: `savedKeys` = count, `skippedAlreadyUnlocked` = the node ids skipped at 363 (**this is R-22's on-device evidence**), and `attempted` (filled by `recordUnlock`). Convert the two debug `Log.e` at 349 and 421 to `SmugLog.w`. |
| `SmugViewModel.kt:890`, `:1562`, `:2303` | Add `DiagContext.element(newActionId("folder"/"gallery"/"tree"))` to the `viewModelScope.launch` in `loadFolderContents`, `selectAlbum` and `startFolderTreeSync`. Convert the catch at ~2340 to `SmugLog.w`. |
| `data/security/PasswordStore.kt:29-36, 94` | Interface gets `val storageKind: String get() = "unknown"`. `EncryptedPasswordStore` returns `"encrypted"` or `"fallback-plaintext"`. The `Log.e` at 94 becomes `SmugLog.e` (R-25 evidence). `FakePasswordStore` needs no change. |
| `MainActivity.kt:87` NavHost | `composable("diagnostics") { DiagnosticsScreen(onBack = { navController.navigateUp() }) }`. Thread an `onOpenDiagnostics` lambda into `BrowserScreen` → `HomeTabView`. Remove the unused `BugReport` import (line 23). |
| `ui/browser/HomeTabView.kt` | Version footer `SmugView ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})` as the last item, with the 7-tap gesture (§2.6). **Visible UI change: sign-off (Q2).** |
| `res/xml/file_paths.xml` | Add `<cache-path name="diagnostics" path="diagnostics_export/" />`. The existing `shared_images` entry stays. |
| `app/proguard-rules.pro` | `-keepattributes SourceFile,LineNumberTable` and `-renamesourcefileattribute SourceFile` (R-51). |
| `app/build.gradle.kts` | A task `archiveReleaseMapping`, finalizing `minifyReleaseWithR8`: copy `build/outputs/mapping/release/mapping.txt` → `release-mappings/<versionCode>/mapping.txt`. Add `release-mappings/` to `.gitignore`. The AAB also carries the mapping to Play for vitals deobfuscation (**assumed**, standard AGP behaviour). Also add unit-test assets for the migration harness (§2.8). |
| `docs/PRIVACY_POLICY.md:21` | Add a sentence: diagnostics are stored on the device only and leave it only when you tap Export. (Owner reviews the wording.) |

### 2.3 HTTP telemetry details

- **actionId propagation.** For Retrofit `suspend` methods, `OkHttpCall.enqueue` →
  `callFactory.newCall()` runs synchronously on the coroutine's current thread, where the
  `ThreadContextElement` has set `DiagContext.action`. So `RetryingCall` reads it at construction.
  (**assumed from Retrofit 2.9 source; test 1a-4 proves it end to end.**) Retry attempts run on the
  scheduler thread but reuse the captured id. Coil calls have no action (`-`).
- **Classification** (from the final `Response`): `networkResponse == null && cacheResponse != null`
  → CACHE; both non-null → CONDITIONAL (revalidated); `networkResponse != null` → NETWORK;
  `code == 504 && both null` → SYNTHETIC_504 (OkHttp's `only-if-cached` miss, finding #6); otherwise
  NONE. `offline=1` is appended when `response.request.cacheControl.onlyIfCached` (the
  `offlineFallbackInterceptor` rewrite, AppModule.kt:61-69).
- **Line format:**
  `<ISO local ts> <L> http [<actionId|->] <METHOD> <describeUrl> -> <code|ERR:<IOExceptionClass>|CANCELED> <source> <ms>ms tries=<n>[ codes=503,503,200][ offline=1]`.
  Level: I for 2xx/3xx, W otherwise.
- **Volume policy.** Host `api.smugmug.com`: every call is logged. Any other host (CDN images via
  Coil): log only non-2xx, SYNTHETIC_504, IO errors, or > 3 s. Everything goes into `HttpStats`
  (per category api/img: count, by status class, cache/conditional/synthetic, retried calls, total
  retries, IO errors, max and mean ms). The stats are shown on screen and exported; they're
  in-memory, per process.
- **Never logged:** bodies, headers, cookies, query values outside the allow-list (§3.3).
- **Not covered in Phase 1:** `OfflineDownloadWorker` and `CollectionsController` use raw clients
  (`forFileDownloads()`), not the factory. Phase 5 owns downloads.

### 2.4 Sync report fields

`SyncRun(runId = actionId, kind, nickname, startedAt, endedAt, isFirstSync, persistedCount,
stopMarker, pagesFetched, albumsSeen, albumsNullLastUpdated, albumsWithParentNode,
albumsPasswordSecurity, changedCount, invalidatedParents, stop: StopReason + detail,
unlocks: List<UnlockAttempt>, savedKeys, skippedAlreadyUnlocked: List<String>, notes)`.
After a `GallerySync` ends, `FileSyncReporter` launches on the diag scope, **not** on the sync's
scope (so it adds no latency and survives cancellation). It adds `newInIndex30d` (DoctorDao
`countRecentIndexAlbums(nickname)`) and `litDotNodes` (`dao.getNodesWithActiveUpdates().first().size`),
then writes an `amend` line. A gap between those two numbers is findings #1/#4, measured.

### 2.5 Doctor checks (DoctorDao; every one is a single SELECT)

All counts are global. Examples (≤ 5 **node ids only**, never titles or paths) are fetched only
when `withExamples`. SQL uses non-correlated `NOT IN (SELECT pk …)`, which SQLite materializes
once. Both key columns are `NOT NULL` (Entities.kt:14, :55), so there's no NULL trap.

| id | Severity if > 0 | SQL (WHERE clause on `cached_nodes n` unless stated) |
|---|---|---|
| `self_parent` | ERROR | `parentNodeId = nodeId` (R-01) |
| `bang_parent` | ERROR | `instr(parentNodeId,'!') > 0` (R-01 `X!parent`) |
| `bang_parent_index` | ERROR | `cached_albums`: `instr(parentNodeId,'!') > 0` |
| `two_cycle` | ERROR | `cached_nodes a JOIN cached_nodes b ON a.parentNodeId=b.nodeId WHERE b.parentNodeId=a.nodeId AND a.nodeId<b.nodeId` |
| `deep_chain` | ERROR | Rows whose parent walk reaches depth 33: `WITH RECURSIVE w(s,cur,d) AS (SELECT nodeId,parentNodeId,1 FROM cached_nodes WHERE parentNodeId IS NOT NULL UNION ALL SELECT w.s,n.parentNodeId,w.d+1 FROM w JOIN cached_nodes n ON n.nodeId=w.cur WHERE w.d<33 AND n.parentNodeId IS NOT NULL) SELECT COUNT(DISTINCT s) FROM w WHERE d>=33`. It's bounded by `d<33`, so it terminates even on cycles. It includes the self-parents (noted in the label). |
| `orphan_rows` / `orphan_parents` | WARN | `parentNodeId IS NOT NULL AND parentNodeId NOT IN ('root','search_result') AND parentNodeId<>nodeId AND instr(parentNodeId,'!')=0 AND parentNodeId NOT IN (SELECT nodeId FROM cached_nodes)`: `COUNT(*)` and `COUNT(DISTINCT parentNodeId)`. The sentinels come from the code: `"root"` (Entities.kt:76, SmugMugRepository.kt:1492), `"search_result"` (:1573, :1755), NULL for the site root (:1431). `activeRootId` is reported separately as "site root row present: yes/no". |
| `album_node_not_indexed` | WARN | `type='Album' AND nodeId NOT LIKE 'virtual:%' AND nodeId NOT IN (SELECT nodeId FROM cached_albums)` |
| `index_album_no_node` | INFO | `cached_albums`: `nodeId NOT IN (SELECT nodeId FROM cached_nodes)` (expected: nodes fill lazily) |
| `recent_index_invisible_to_dot` | WARN | `cached_albums`: `dateModified IS NOT NULL AND datetime(dateModified) >= datetime('now','-30 days') AND nodeId NOT IN (SELECT nodeId FROM cached_nodes)`. This is the count the dot design §4 promised. |
| `index_nodeid_is_albumkey` | WARN | `cached_albums`: `nodeId = albumKey` (fallback at SmugMugRepository.kt:666; AlbumKey ≠ NodeID) |
| `dup_index_nodeid` | ERROR | `cached_albums GROUP BY nodeId HAVING COUNT(*)>1` (counted via a subquery) |
| `dup_album_uri` | WARN | `cached_nodes WHERE albumUri IS NOT NULL GROUP BY albumUri HAVING COUNT(*)>1` |
| `dup_index_urlpath` | WARN | `cached_albums WHERE urlPath IS NOT NULL GROUP BY nickname, urlPath HAVING COUNT(*)>1` |
| `empty_nickname_nodes` / `_index` | WARN | `nickname = ''` on each table |
| `index_parent_set` | INFO | `cached_albums`: `parentNodeId IS NOT NULL` (R-05: the sync never sets it) |
| `index_null_date` | INFO | `cached_albums`: `dateModified IS NULL` (#1) |
| `unparseable_album_date` | WARN | `type='Album' AND dateModified IS NOT NULL AND datetime(dateModified) IS NULL` |
| `search_result_parented` | INFO | `parentNodeId = 'search_result'` |
| totals | INFO | Rows per table and per nickname, `viewed_gallery_updates` rows, lit dot nodes |

The doctor runs outside a transaction, so it never blocks writers. If a sync run is open, the
report sets `ranDuringSync = true` and the screen says "a sync was running; counts may be
transient". The doctor opens no write path; its test proves that with `SELECT total_changes()`
before and after (§5).

### 2.6 Diagnostics screen

It's reached from the Hub version footer: **7 taps within 3 s** (the counter resets otherwise).
There's no toast countdown. The footer text has no other action, and the Hub renders both with
and without a site (SmugViewModel.kt:724, 872), so it's always reachable. It's the same in debug.

Sections, top to bottom (`LazyColumn`; each section is loaded on `Dispatchers.IO` by the ViewModel):
1. **Status:** version/code/build type, device model, SDK, password store kind (encrypted or
   fallback), saved-key count (never values), log bytes, dropped lines, and the sink error if any.
2. **Cache doctor:** a "Run" button. Rows show label, count, severity colour and ms, plus the total
   ms. A "Show example IDs" switch (default off) re-runs with examples.
3. **Sync runs:** the last 5, collapsed to one line (`sync#3 GallerySync ReachedKnown p1 i12 · 100
   seen · 0 changed · 2.1s`); expanded shows every field and each unlock attempt.
4. **HTTP:** the `HttpStats` table.
5. **Log tail:** the last 200 lines, monospace, with a "Warnings and errors only" switch.
6. **Actions:** "Export" (share sheet), "Clear diagnostics" (confirm dialog; deletes logs and
   reports, not the cache), and "Refresh".

**Export** runs `DiagnosticsExporter.export()` then `Intent.ACTION_SEND` (`text/plain`,
`EXTRA_STREAM`, `FLAG_GRANT_READ_URI_PERMISSION`, `ClipData`), following PhotoDetailScreen.kt:667-690.

### 2.7 Why no Room table

Logs and reports are files, not Room entities. A table would need migration 15→16, which is a
one-way step, and it's already reserved by the dot design (new-gallery-indicator.md §5.1). It would
also put diagnostics in the DB the doctor inspects. `files/diagnostics/` survives process death, and
it's already excluded from backup and device transfer (`data_extraction_rules.xml`, domain `file`).

### 2.8 Test infrastructure (1c)

- **Real Room for repository tests (R-63).** Blast radius (verified): `FakeCollectionDao` lives
  inside `SmugMugRepositoryTest.kt:34-230` and is used only there. There are 15 `FakeCollectionDao()`
  constructions across 18 tests (lines 240 … 1207). Direct fake-field access: `nodes` ×3,
  `albumIndex` ×1, all else is via DAO methods. `SmugViewModelTest` mocks the repository
  (SmugViewModelTest.kt:61) and is **unaffected**. `PhotoPagingSourceTest` has no DAO.
  `CollectionDaoTest` already uses in-memory Room (CollectionDaoTest.kt:23-35).
  - Change: `@RunWith(RobolectricTestRunner::class) @Config(sdk = [33])`. `@Before` builds
    `TestDb.inMemory()` (new shared helper `app/src/test/.../data/db/TestDb.kt`, also used by
    `CollectionDaoTest` and the doctor tests); `@After` closes it. Replace `fakeDao.nodes` with
    `dao.getAllCachedNodes()` and `fakeDao.albumIndex` with `dao.getAlbumIndex(nick)`. Delete
    `FakeCollectionDao` last.
  - At risk of going red (**assumed**, from R-63's divergence list):
    `testActiveUpdatesDetectionBubblingAndClearing` (the fake seeds folders and treats unparsable
    dates as recent), `testGetAlbumsInScopeOrchestration` and `testMockVisibilityAnonymousState`
    (the fake's BFS vs the SQL CTE), and `testSearchImagesOrchestrationAndPaging` (the fake appends
    search results; Room REPLACEs them). Each red test is triaged: a fake-only fixture gets fixed
    to a real shape; a real bug gets a findings row and `@Ignore("findings #N")`, fixed in Phase 2
    (Q5).
- **Migration harness (R-67).** `MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
  AppDatabase::class.java)` reads `assets/com.smugview.app.data.db.AppDatabase/<v>.json`. Today
  only `androidTest` gets the schemas as assets (build.gradle.kts:99-101), and there's no
  `app/src/androidTest` dir at all. Whether Robolectric sees unit-test assets is **unverified**;
  step 1c-1 is a spike with three options in order: (a) `getByName("test").assets.srcDir("$projectDir/schemas")`
  with `@Config(sdk=[33])` (**not** `manifest = Config.NONE`, which may drop assets); (b) the
  `debug` source set's assets (ships ~40 KB of schema JSON in debug APKs only); (c) a real
  `androidTest` on the emulator (`connectedDebugAndroidTest`). Schema 12 isn't exported, so
  12→13 can't be validated. `kapt` is disabled for test source sets (build.gradle.kts:223-225),
  which is fine; no annotation processing is needed.

## 3. Privacy and security

### 3.1 Never logged or exported
The API key (`APIKey=`, and its literal value anywhere); `Password=` query or form values; saved
password values; `PasswordHint` (it often *is* the password); cookies and `Set-Cookie`;
`Authorization`; OAuth params; request and response **bodies**; CDN photo URLs (R-53: a
password-gallery CDN URL opens with no session, so a logged URL *is* the photo); gallery or folder
**titles and UrlPaths** (they name family members); EXIF/GPS; search text (length only).
Node IDs, album keys, the site nickname and HTTP status codes are allowed. They're opaque, and
gallery metadata is public anyway (AGENTS.md "Album/gallery METADATA is public").

### 3.2 Redactor rules (applied in order, at **write** time and again at export)

| # | Pattern (Kotlin regex) | Replacement |
|---|---|---|
| R1 | `(?i)(^\|[?&;\s"'])(APIKey\|api_key\|Password\|oauth_[a-z_]+\|access_token)=([^&\s#"']*)` | `$1$2=<r>` |
| R2 | `(?i)(APIKey\|Password)%3D[^&%\s"']*` | `$1%3D<r>` |
| R3 | `(?i)"(APIKey\|Password\|PasswordHint\|oauth_[a-z_]+\|access_token)"\s*:\s*"(?:[^"\\]\|\\.)*"` | `"$1":"<r>"` |
| R4 | `(?im)^(\s*)(Cookie\|Set-Cookie\|Authorization\|Proxy-Authorization)\s*:.*$` | `$1$2: <r>` |
| R5 | `(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+` | `Bearer <r>` |
| R6 | `(?i)https?://photos\.smugmug\.com/\S*` | `https://photos.smugmug.com/<r>` |
| R7 | `(?i)(https?://(?!api\.)[a-z0-9-]+\.smugmug\.com)/[^\s"'<>]*` | `$1/<path>` |
| R8 | `(/api/v2/folder/user/[^/\s!?]+)/[^\s!?"']*` | `$1/<path>` |
| R9 | `(?i)([?&](?:Text\|Keywords\|q)=)([^&\s#]*)` | `$1<len=N>` (a lambda replacement) |
| R10 | Each literal secret (API key, saved password values with length ≥ 4, and their `URLEncoder` forms), longest first | `<secret>` |

Inputs are capped at 16 KB **before** the rules run, and the trailing partial token (`[^\s&]*$`)
is cut. After redaction, lines are truncated to 4 KB (8 KB for stack traces). A password shorter
than 4 characters isn't matched literally (it would shred ordinary text). R1/R3 still cover it
wherever it appears as a parameter.

**Tests (`RedactorTest`, pure JVM):** APIKey at the start, middle and end of a query, lowercase,
and `%3D`-encoded; form body `Password=hunter2&x=1`; JSON `"Password": "x"` and `"PasswordHint":"dog"`;
`Cookie:` and `Set-Cookie:` lines (multi-cookie); `Authorization: Bearer …`; OAuth header params; CDN
URL `https://photos.smugmug.com/Family/School/i-AbCd/0/Kxyz/X3/IMG_1-X3.jpg` (assert `i-AbCd`,
`Kxyz`, `Family` are all absent); a web URL with a family path; the `/api/v2/folder/user/nick/Family/School`
path; search `Text=`; a saved password (≥ 4 chars) inside an exception message; a 3-char password
left alone (documented); the API-key literal inside a stack trace. Also: idempotence
(`redact(redact(x)) == redact(x)`); benign text unchanged (`GET /api/v2/node/2sDN5x!children -> 200`);
multi-line input; and a 100 KB input finishing in < 50 ms and capped.

### 3.3 URL description for telemetry (`describeUrl`)
It shows host only if it isn't `api.smugmug.com`. Path: API paths after R8; non-API paths are
reduced to `<path:n segs>` (CDN tier suffix such as `X3` kept). Query: values kept only for
`start, count, Order, SortMethod, SortDirection, _verbosity, _expand`; all other params are listed
by **name only** (`+APIKey,+Password,+_filter`). Presence of `Password=` on a GET is R-23 evidence;
its value never appears.

### 3.4 Export: contents, size, retention, leak risk
- **Contents:** header (app version/code/build type, device model, SDK, local time and UTC offset,
  password store kind, saved-key count); doctor (counts; IDs only if the switch is on); the last 5
  sync runs; HTTP stats; crash and exit records; then the log (`diag-1` + `diag-0` + the unflushed
  ring, oldest first).
- **Size cap: 1.5 MB.** The log section is trimmed from its oldest end to fit.
- **Belt and braces:** the whole text is passed through the Redactor again with the current
  secrets. Then a **leak scan**: if the API key or any saved password (≥ 4 chars) still occurs,
  the export is refused with "Export blocked: redaction failed", and an E line is logged, without
  the secret.
- **Retention:** 2 × 512 KB log files; 20 sync runs; export files are deleted on the next export
  and at startup when older than 24 h. "Clear diagnostics" deletes everything under
  `files/diagnostics/` and the exports.
- **Residual risk:** once shared, the file is out of the app's control. The worst it can reveal is
  node IDs, album keys, the nickname, request timings and counts, which is no access to private
  content (no CDN URLs, cookies or keys). It's stated on the Export confirm line: "Contains no
  passwords, keys or photo links."

## 4. Failure design

| Situation | Behaviour |
|---|---|
| Disk full / IO error writing | The writer catches it, sets `SinkStatus.error`, retries on the next batch; after 3 consecutive failures the file sink is disabled for the process. The memory ring keeps working; the Status section shows the error. No caller ever sees an exception. |
| Log file corrupted or unreadable | `read()` decodes with `CodingErrorAction.REPLACE`; an unreadable file yields `"<log unreadable: ExceptionClass>"`, and the screen offers "Clear diagnostics". A sync-report line that fails to parse is skipped and counted. |
| Logging from any thread | `log()` redacts on the caller thread (µs), appends to the ring under a short lock, then `offer`s to the queue. There's no IO on the caller. Main-thread safe (StrictMode-clean). |
| Coroutine cancelled | `log`, `begin`, `finish` and `recordUnlock` are non-suspending, so they work from `finally` in a cancelled coroutine. Post-sync counts run on the diag scope. |
| Process death | Queued lines not yet written are lost (at most one batch). An open sync run shows as `Interrupted`. The next launch records `ApplicationExitInfo` (API 30+; minSdk 26 guarded): reason, status, importance, pss/rss, redacted description. It keeps a "last seen" timestamp in `files/diagnostics/state.properties`. ANR traces aren't read in Phase 1 (size). |
| Uncaught exception | The handler logs the E line (redacted stack, ≤ 8 KB), then `flush(1000 ms)`. If the writer is dead, it writes `files/diagnostics/crash-<ts>.txt` directly. Then it calls the previous handler, so the system crash dialog and Play vitals still happen. |
| Export with an empty log | Still exports header, doctor and stats; the log section reads "(no log lines yet)". |
| Export when the doctor never ran | The exporter runs it (without examples). |
| Huge response body | Never read; telemetry uses only status and metadata. The existing `peekBody(1 MB)` for toasts (AppModule.kt:174) is unchanged. |
| Very long message / exception | 16 KB input cap, then 4 KB / 8 KB line cap (§3.2). |
| Sync or doctor racing the screen | The screen reads snapshots; the doctor flags `ranDuringSync`. |
| Two syncs at once (site switch, R-10) | Separate actionIds and separate runs, so the report shows both. That's evidence for Phase 3, not something to hide. |
| Hot-path cost | One API call → 1 line (~200 B) + about 10 regex passes. Coil images: counters only. Budget: < 0.2 ms per logged line on the calling thread (1a-2 has a micro-benchmark test at 10k lines). |
| Telemetry bug | `onComplete` and `record` are wrapped in `try/catch(Throwable)`; a failure increments `dropped` and never affects the call. |

## 5. Staged implementation plan

Each step is its own commit, with the full unit suite green (`./gradlew.bat testDebugUnitTest`,
checked for `BUILD SUCCESSFUL`, R-69). Each step's first test is written and run **red** before
the code (for a new class: a stub with the final signatures that returns nothing or no-ops, so the
test compiles and fails on the assertion). Record red→green in the Progress log in findings.md.

### 1a: logging, redactor, HTTP telemetry, mapping
| Step | Work | Failing-first test | Done when |
|---|---|---|---|
| 1a-1 | `Redactor` | `RedactorTest` (§3.2 list) against a stub `redact = { it }` | All cases pass; 100 KB < 50 ms |
| 1a-2 | `DiagLog` + `FileLogSink` | `FileLogSinkTest` (TemporaryFolder): rotation at the size cap keeps ≤ 2 files; 8 threads × 1,000 lines are all present or counted as dropped; a sink dir that is a *file* (IO failure) → no throw, `status.error` set, ring still returns lines; random bytes in `diag-0.log` → `read()` returns text, no throw; empty dir → `""`; secrets are redacted before they hit disk (read the raw file). | Green; no caller-thread IO (asserted with a sink that records its thread names) |
| 1a-3 | `Diag` holder, `SmugLog` rewire, `DiagnosticsModule`, `DiagnosticsInitializer`, `SmugViewApp.onCreate`, `CrashRecorder` | `SmugLogTest`: after `Diag.log = recording`, `SmugLog.e/w` land in it (red: today `e` only reaches logcat). `CrashRecorderTest`: throwing on a new thread writes an E line and calls the previous handler. | App starts on the emulator; the Status section isn't there yet, so check with `run-as` that `files/diagnostics/diag-0.log` has the start line |
| 1a-4 | `DiagContext` + `RetryingCallFactory` `onComplete`/`actionIdProvider` | `RetryingCallFactoryTest`: `onComplete` fires exactly once on **success** (red: no hook fires on success today), with `attempts=3, codes=[503,503,200]`; once on IO failure; once on cancel. A real Retrofit call inside `withContext(DiagContext.element("t#1"))` reports `actionId == "t#1"`. | Green; the existing 7 tests unchanged |
| 1a-5 | `HttpTelemetry` + AppModule wiring + `onFinalFailure` | `HttpTelemetryTest` with the loopback-server pattern from `HttpClientsTest` and a real `Cache`: 1st call NETWORK, 2nd CACHE; `only-if-cached` on an uncached URL → SYNTHETIC_504, `offline=1`; the line has no APIKey value, `+APIKey` present; CDN host failure logged, CDN success not logged but counted | Green; on the emulator, a folder open shows `http [folder#n]` lines |
| 1a-6 | R-52 debug logger | `DebugHttpLoggerTest`: a request with `APIKey=K`, form `Password=P`, `Cookie: s=C` through `debugHttpLogger` → the captured output has none of K/P/C (red against the current config, which only redacts `Authorization`) | Green |
| 1a-7 | R8 line numbers + mapping archive | `BuildConfigGuardTest` (file-based, like `ManifestGuardTest`): proguard-rules.pro contains `SourceFile,LineNumberTable` | `assembleRelease` BUILD SUCCESSFUL; `release-mappings/<code>/mapping.txt` exists; release smoke test on the emulator (AGENTS.md R8 rule): galleries load |

### 1b: doctor, sync report, Diagnostics screen
| Step | Work | Failing-first test | Done when |
|---|---|---|---|
| 1b-1 | `DoctorDao` + `CacheDoctor` + `TestDb` helper | `CacheDoctorTest` (Robolectric, real Room) with a **real-shaped** fixture: site root (parent NULL) → password folder `2sDN5x` → sub-folder `P4BKB` → gallery (NodeID `LCdk7F`, AlbumKey `FfHCms`, date = now − 2 days), plus one self-parent, one `X!parent`, one orphan under a missing parent, one album node missing from `cached_albums`, one index row with `nodeId == albumKey`, one 2-cycle. Assert each count exactly, and that `total_changes()` is equal before and after. Red against a stub returning 0s. | Green; `15.json` byte-identical after build; 5k-row fixture < 1 s |
| 1b-2 | `SyncReport`, `FileSyncReporter`, repository hooks | `SyncReportTest` with the `createMockApi` interceptor pattern: (a) 2 pages, no stop marker → `NoNextPage`, `pagesFetched=2`; (b) a known marker hit at page 1 index 3 → `ReachedKnown(1,3)`; (c) HTTP 429 on page 2 → `Error(HttpException 429)`; (d) an unlock returning 401 → `Rejected` attempt recorded, password absent from the log file. Payloads use the real shape: **no `ParentNode` in `user!albums`** (R-05), `Uris.Folder` present, an album with no `LastUpdated` (#1). | Green |
| 1b-3 | VM hooks (`unlockAllSavedPasswords`, 3 action ids) | `SmugViewModelTest.launchUnlock_recordsSkippedKeys`: 2 saved keys, one covered by its saved parent → report `skippedAlreadyUnlocked=[child]` | Green; existing VM tests unchanged |
| 1b-4 | `DiagnosticsExporter` + `file_paths.xml` | `ExporterTest` (Robolectric): an empty log still exports; a planted API-key literal in a (fake) log is redacted; a sink that bypasses redaction triggers the **leak scan** refusal; the file is ≤ 1.5 MB with a 3 MB log. A guard test checks that `file_paths.xml` has `diagnostics_export/`. | Green |
| 1b-5 | Screen, ViewModel, route, footer gesture (**owner sign-off: Q2**) | `TapCounterTest` (pure): 7 taps in < 3 s → true; 6 taps, or a gap > 3 s → false. | On the emulator: 7 taps open the screen; doctor, sync runs, log and export all work; the export opens in a text viewer |
| 1b-6 | On-device acceptance (§1) | — | All 5 acceptance points are met on the owner's phone; numbers recorded in findings.md (**install path: Q1**) |

### 1c: test infrastructure
| Step | Work | Failing-first test | Done when |
|---|---|---|---|
| 1c-1 | Spike: MigrationTestHelper under Robolectric (options a/b/c, §2.8) | `MigrationTest.migrate14To15` | It passes, and **goes red** when `ALTER TABLE` in `MIGRATION_14_15` (AppDatabase.kt:115) is commented out. Record which option worked. |
| 1c-2 | Migration tests | `migrate13To15_keepsUserData`: create v13; insert `offline_collections`, `collection_photos`, `collection_bookmarks`, `viewed_gallery_updates`, `cached_nodes`; migrate with `MIGRATION_13_14, MIGRATION_14_15`; validate and assert the rows. `SchemaGuardTest` (file-based): `schemas/…/<AppDatabase.version>.json` exists. | Green; red-check done as in 1c-1 |
| 1c-3 | `SmugMugRepositoryTest` on real Room | Convert tests one at a time; run each converted test and record pass/red | Every red test triaged (Q5); `FakeCollectionDao` deleted; suite green |

**Needs owner sign-off (one-way or meaning-changing):** the Hub version footer and hidden gesture
(visible UI, Q2); release logcat mirroring (Q3); what the export may contain (Q4); installing a
build over the phone's data, which may mean a Play internal-track upload (Q1; `publish` still needs
confirmation at that moment, per AGENTS.md "Silent Mode EXCLUSIONS"); `@Ignore`ing tests that real
Room shows to be wrong (Q5); the PRIVACY_POLICY.md wording. **Not needed:** a Room migration (none
in Phase 1).

## 6. Open questions (recommended answer first)

1. **How does the Phase 1 build reach your phone without wiping its data?** The phone runs the Play
   build. A locally signed APK can't install over it (a signature mismatch, **assumed**: new apps
   use Play App Signing), and uninstalling erases the cache the doctor must inspect, plus your
   saved passwords. *Recommend:* upload versionCode 26 to the **internal testing** track only (you
   as the sole tester). It's not production, but it counts as a publish, so it's confirmed at that
   moment. The alternative is uninstall + sideload + re-browse, which loses today's evidence.
2. **Gesture and visible footer.** *Recommend:* a small grey footer "SmugView 0.7.x (NN)" at the
   bottom of the Hub, with 7 taps in 3 s and no countdown toast. (There's no version row anywhere
   today.)
3. **Mirror redacted W/E lines to logcat in release?** *Recommend:* yes. Logcat is readable only via
   adb, and it gives live evidence when the phone is plugged in.
4. **Export contents.** *Recommend:* counts by default; node IDs only when the switch is on; titles
   and paths never, not even behind a switch.
5. **Tests that go red on real Room.** *Recommend:* a fixture-only cause gets fixed now; a real bug
   gets a findings row and `@Ignore("findings #N")` and is fixed in Phase 2. The suite stays green,
   and nothing is deleted without a row.

## 7. Verified vs assumed

| Claim | Status |
|---|---|
| `SmugLog.e` is debug-gated (SmugLog.kt:28-30); its doc says R8 is off, but `isMinifyEnabled = true` (build.gradle.kts:62) | verified |
| 93 `BuildConfig.DEBUG` sites in `app/src/main` today (findings #7 said 97) | verified (grep, 2026-09-30) |
| `onFinalFailure` has a default no-op and AppModule doesn't pass it (RetryingCallFactory.kt:39; AppModule.kt:168-197) | verified |
| Debug logger level BODY, redacts only `Authorization` (AppModule.kt:33-43); unlock sends `@Field("Password")` (SmugMugApi.kt:196-211) | verified |
| No `-keepattributes SourceFile,LineNumberTable` (proguard-rules.pro) | verified |
| `diag` package not kept by R8, so no Gson there (proguard-rules.pro:25-32) | verified |
| FileProvider authority `${applicationId}.fileprovider`, only `shared_images/` (AndroidManifest.xml; file_paths.xml) | verified |
| `files/` excluded from backup and transfer (data_extraction_rules.xml) | verified |
| `buildInMemoryGalleryCache` stop/next/catch at SmugMugRepository.kt:656, 684, 715; `catch (e: Exception)` also swallows cancellation | verified |
| Launch unlocks go through `unlockNode`/`unlockAlbum` → `*Result` (SmugViewModel.kt:377-410; SmugMugRepository.kt:1095-1099) | verified |
| No Hub version row exists; Hub shown without a site (SmugViewModel.kt:724) | verified |
| FakeCollectionDao usage counts (§2.8); ViewModel tests mock the repository | verified |
| kapt (not KSP) exports schemas 13–15; there's no `androidTest` directory | verified |
| Retrofit `newCall` runs on the coroutine's thread, so the ThreadLocal is visible | assumed (test 1a-4) |
| Adding a DAO leaves the schema identity hash unchanged | assumed (1b-1 checks `15.json`) |
| Robolectric can read unit-test assets for MigrationTestHelper | unverified (spike 1c-1) |
| AAB carries `mapping.txt` to Play; Play App Signing blocks sideloading over the Play build | assumed |
| OkHttp 4.12 returns 504 with no network or cache response for an `only-if-cached` miss | assumed (test 1a-5) |
| Which repository tests go red on real Room | assumed (1c-3 measures it) |
