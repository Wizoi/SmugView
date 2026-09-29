# SmugView whole-app review — 2026-09-29

Six read-only reviewers covered six domains: API/network, persistence/caching,
ViewModel/concurrency, Compose UI, casting/files/security, and tests/debuggability. This
document de-duplicates their findings, groups them by *root cause*, and orders the work.

**How to read confidence.** *confirmed* means reproduced or shown with real data (live API
payloads or a scratch SQLite run). *likely* means the code path was traced end to end but not
reproduced. *possible* means plausible and unverified. **✔** marks a finding the
parent session re-checked itself against the live API. Nothing was reproduced on a device: the
owner's phone runs a release build, which logs nothing (R-50).

Findings already in [findings.md](../findings.md) #1–#17 are referenced, not repeated.

---

## 1. Summary

Most of the hard bugs of the last few releases share **ten root causes**. Each causes bugs in
several domains at once, which is why fixing symptoms one at a time kept failing:

| # | Root cause | Worst consequence |
|---|---|---|
| T1 | **A node's parent has no single owner, and the app reads the wrong field for it.** SmugMug's `Uris.ParentNode` is the node's *own* `!parent` link, not the parent's ID. | Self-parented rows cause infinite loops, and folders vanish from their listings |
| T2 | **No site- or screen-scoped lifetime.** One global ViewModel holds every screen's state, and background jobs outlive a site switch. | Site A's data lands on site B, and one gallery's photos mix into another's |
| T3 | **"Password saved", "session unlocked" and "password invalid" are conflated.** | Saved passwords are deleted on a network error |
| T4 | **API parameters were never checked against what SmugMug accepts.** | Silently ignored params, and data missing after page 1 |
| T5 | **One global HTTP cache policy** that doesn't know about sessions, refreshes or file downloads. | Refresh shows nothing new; offline data is evicted |
| T6 | **Two offline-download systems, with no owner of the files.** | Photos lost across collections, and permanently blank rows |
| T7 | **Loading, empty, error and locked states are conflated in the UI.** | Misleading empty screens, a spinner that never ends, two crashes |
| T8 | **The tests agree with the bugs:** fakes and fixtures don't look like the real thing. | Past fixes "passed" and never worked in production |
| T9 | **Nothing is observable in a release build.** | Every device bug has to be inferred |
| T10 | **Privacy and security gaps** around passwords, transfer and links. | A password-gallery photo can be opened by anyone given a link |

T8 and T9 are why the others survived. T1 and T3 are why the "new gallery" dot kept breaking.

---

## 2. Findings by root cause

IDs are `R-nn`. The domain column gives the reviewer: API, DB (persistence), VM, UI, SEC, TST.

### T1 — Parent/tree integrity

| ID | Finding | Where | Conf. | Domain |
|---|---|---|---|---|
| R-01 | `Uris.ParentNode` = `/node/{SELF}!parent`. There are 4 repository parsers and 5 ViewModel parsers. They turn it into a self-parent (`X`) or a bogus `X!parent`. | SmugMugRepository.kt:1155, 1459-61, 1488, 1563; SmugViewModel.kt:1129, 1361, 1374, 1442, 1463 | confirmed (payload, found live by 2 reviewers independently) | API, DB, TST |
| R-02 | A self-parent row makes the `UNION ALL` CTEs (`getAllDescendants`, `getAlbumsInScope`, `searchNodesInScope`) recurse forever. It is reachable via "Mark as Viewed" and search-in-scope on a search-result folder. | CollectionDao.kt ~50, 77, 86-96 | confirmed (scratch SQLite: 100k-row cap with UNION ALL, 2 rows with UNION); hang likely | DB, TST |
| R-03 | `getUnlockedPassword` / `resolvePasswordRootNodeId` walk parents with no cycle guard. The walk spins forever, or never climbs, so the unlock targets the wrong node. | SmugViewModel.kt:1354-1426; SmugMugRepository.kt:1459-64 | likely | DB, API |
| R-04 | `resolveAndCacheAlbumLineage` REPLACEs a correctly parented ancestor row with `X!parent`, sortIndex 0 and no highlight. The folder vanishes from its parent's listing, and the root loses its offline header image. | SmugMugRepository.kt:1150-72, 1188-1217 | likely | DB |
| R-05 | `user!albums` has no `ParentNode` at all, so `cached_albums.parentNodeId` is always null. The Retro v6 "evict the stale parent listing" fix has never run in production. Its test passes only on an invented payload shape. | SmugMugRepository.kt:679, 700-705; tests :831, :929, :978, :1119 | confirmed ✔ | API, DB, TST |
| R-06 | A cache hit is `isNotEmpty()`, so one stray row under a parent is treated as a complete listing. | SmugMugRepository.kt:191 | confirmed (code) | DB |
| R-07 | `forceRefresh` REPLACEs, but never deletes children that were removed or moved. They come back on the next cache hit, and tapping one gives a 404. | SmugMugRepository.kt:290 | likely | DB |
| R-08 | `mergeAlbumsIntoIndex` writes the node `DateModified` into `cached_albums.dateModified` (otherwise LastUpdated). That column's MAX is the sync's stop marker. | SmugMugRepository.kt:794 | likely | DB |
| R-09 | Looking up a gallery by `getNodeById(albumKey)` always misses (AlbumKey ≠ NodeID), which falls back to a full-table scan across sites. | SmugMugRepository.kt:1185 | likely | DB |

**Real parent source (verified ✔):** an album's `Uris.Folder`
(`/folder/user/{nick}/{path}`) returns the folder's `NodeID`. Here that was `3BxbFF`, which matches the
`node/{album}!parent` endpoint. The folder's `UrlPath` is also the album's `UrlPath` prefix, so
most parents can be matched locally with no request.

### T2 — Lifetime and scoping

| ID | Finding | Where | Conf. | Domain |
|---|---|---|---|---|
| R-10 | Switching site doesn't cancel sync, subtree-index or hub jobs. Writers stamp the *current* `activeNickname`, so site A's folders and galleries are persisted as site B. The global `_albumsCache` is overwritten by whichever sync finishes last. | SmugViewModel.kt:755-60, 824-9, 1309-14; SmugMugRepository.kt:87-95, 776-803; SiteHubController.kt:72-219 | likely | VM, DB |
| R-11 | Opening gallery B while A is still streaming appends A's pages, tags, title, style and `webUri` into B. A's failure can wipe B's photos. | SmugViewModel.kt:1707-1843 | likely | VM |
| R-12 | A folder load that lands late overwrites the folder now on screen, or a new site's home. | SmugViewModel.kt:890-953 | likely | VM |
| R-13 | "Where am I" has three independent sources (`_browserState`, `currentFolderId`, `folderNavigationStack`). Opening a gallery from Search, Home or Collections rewrites the breadcrumb but not the listing. A folder shortcut does the reverse. Share, Refresh and Tags scope then act on the wrong folder. | SmugViewModel.kt:1586-93; CollectionsTabView.kt:287-90 | confirmed (code) | VM, UI |
| R-14 | System back is enabled on any tab whenever the stack is non-empty, so it does invisible work, and it can't close a collection. | BrowserScreen.kt:135-8 | confirmed (code) | UI |
| R-15 | Search "back-state" carries across a site switch, so it restores site A's folder on site B. | SmugViewModel.kt:954-1001 | likely | VM |
| R-16 | Double taps push duplicate folders or grids; there's no click guard and no `launchSingleTop`. | SmugViewModel.kt:1003-23; MainActivity.kt:92 | likely | VM |
| R-17 | Process death: there's no SavedStateHandle or rememberSaveable anywhere. Search and Tag viewers restore blank, and the folder stack is lost. | SmugViewModel; KeywordPhotoDetailScreen.kt:65-98 | confirmed (code) | VM, UI |
| R-18 | The gallery Share/QR can encode the *previous* gallery's link (`currentAlbumWebUri` isn't reset). | PhotoGridScreen.kt:739-46; SmugViewModel.kt:1565-72 | confirmed (code) | UI |
| R-19 | Returning to the Tags tab empties keyword results while the chips stay selected. | TagSearchController.kt:460 | likely | VM |
| R-20 | Unrelated jobs share one loading flag. The album-download status shows up in the gallery grid, and whichever job finishes first hides the other's spinner. | SmugViewModel.kt:1669-99; CollectionsController.kt:162-245 | confirmed (trace) | VM |

### T3 — Passwords and sessions

| ID | Finding | Where | Conf. | Domain |
|---|---|---|---|---|
| R-21 | **A saved password is deleted on any failure** (offline, the synthetic 504, 429, 5xx). The user is told it's "no longer valid". | SmugViewModel.kt:909-23, 1156-69; SmugMugRepository.kt:1524-33 | confirmed (code; found by 3 reviewers independently) | VM, UI, DB |
| R-22 | `unlockAllSavedPasswords` at launch skips almost every entry, because `isNodeUnlocked` means "has a saved password", not "has a session". | SmugViewModel.kt:151-67, 363 | likely | VM |
| R-23 | The `Password=` query parameter is ignored by every GET, yet it's sent in about 15 endpoints. It leaks into URLs and logs, and it also disables caching for those responses. | SmugMugApi.kt; docs/SMUGMUG.md:36, 95, 144 | likely (checked on 7 endpoints) | API |
| R-24 | Unlocking from Search or Home prompts for the password root, then doesn't open the gallery. It still marks the gallery viewed, clearing its dot unseen. | BrowserScreen.kt:368-78; SmugViewModel.kt:1122-47 | likely | UI |
| R-25 | The password store silently falls back to plaintext on any Keystore failure, and those passwords are never migrated back. | PasswordStore.kt:91-118 | confirmed (code) | SEC |
| R-26 | `submitPassword` copies the password under every descendant key, so there's no single owner, and removal happens in four places under different rules. | SmugViewModel.kt:1239-54, 1403-20 | confirmed (code) | VM, DB |

### T4 — API contract

| ID | Finding | Where | Conf. | Domain |
|---|---|---|---|---|
| R-27 | `NextPage` drops `_expand`, so pages 2+ have no expansions: galleries 101+ have no thumbnail, and folders with more than 100 children lose covers. | SmugMugRepository.kt:253, 583, 686; PhotoPagingSource.kt:38 | confirmed (live) | API |
| R-28 | `user!topkeywords` ignores `NodeID`; the real param is `NodeURI`. A scoped tag scan shows site-wide keywords. | SmugMugApi.kt:253 | confirmed (live: 165 vs 77) | API |
| R-29 | `_filteruri` strips `ImageSizeDetails`, so the zoom's middle tier downloads the full original. `DateTime` and `WebUri` are never present on image payloads. | SmugMugApi.kt:83, 184 | confirmed (live) | API |
| R-30 | `image/{key}` 301-redirects to `{key}-0` with `no-store`, so photo details and EXIF always fail offline. | SmugMugApi.kt:177, 188 | likely | API |
| R-31 | Search beyond 10,000 results gets a 500 with an empty body. It is retried 5×, then toasted. | RetryingCallFactory.kt:45 | confirmed (response) | API |
| R-32 | A locked album's `!images` returns 200 with no `AlbumImage` key. Gson yields `emptyList()`, so the `== null` "locked" check is dead: Cast and download get 0 photos with no prompt. | SmugMugRepository.kt:393, 555 | likely | API |
| R-33 | Photo search falls back to a **hardcoded idzifamily root** when the scope fails to resolve, on any site. | SmugMugRepository.kt:831 | likely | API, DB |
| R-34 | The Hub's "total photos" sums only the first 100 galleries. | SiteHubController.kt:111, 204 | confirmed | API |

### T5 — HTTP cache policy

| ID | Finding | Where | Conf. | Domain |
|---|---|---|---|---|
| R-35 | `forceRefresh` never reaches the network within 5 minutes, because the cache rewrites everything to `max-age=300`. Refresh shows nothing new. | AppModule.kt:73-95 | likely | API |
| R-36 | The cache key ignores the session cookie: the anonymous `user!albums` (119) is served after an unlock (2,633). This directly affects the dot redesign. | AppModule.kt | likely | API |
| R-37 | Full-size originals (`max-age=31536000`) go into the shared 50 MB cache. About 28 downloads evict every cached API response that offline browsing relies on. | AppModule.kt:118-127 | confirmed (headers) | DB, SEC |

### T6 — Offline files

| ID | Finding | Where | Conf. | Domain |
|---|---|---|---|---|
| R-38 | There's one file per `imageKey` shared by two download systems. Re-downloads truncate in place, a failure deletes a file another row relies on, and removing a bookmark deletes files still in a collection. | OfflineDownloadWorker.kt:295-312; CollectionsController.kt:97-108, 185-270 | likely | DB, SEC |
| R-39 | Every add enqueues a non-unique worker with no network constraint. The worker swallows `CancellationException`, so it keeps downloading after being stopped. | CollectionsController.kt:149-50; OfflineDownloadWorker.kt:43-94 | confirmed (code) | SEC, DB |
| R-40 | 408/429 are treated as permanent. Offline, the synthetic 504 marks rows `isDownloaded=true, path=""`, and those render blank forever. | OfflineDownloadWorker.kt:108-119, 209-285 | likely | DB, SEC |
| R-41 | Offline files are never deleted (collection delete, photo remove, site disconnect), and offline album deletion needs the network. | CollectionsController.kt:54-58 | confirmed (code) | DB, SEC |

### T7 — UI states and crashes

| ID | Finding | Where | Conf. | Domain |
|---|---|---|---|---|
| R-42 | **Crash:** a collection holding a gallery plus a photo from that gallery hits a duplicate LazyColumn key. | CollectionsTabView.kt:337, 398 | confirmed (code) | UI |
| R-43 | **Crash:** casting from a gallery or collection whose name contains `/` (the route isn't `Uri.encode`d). | MainActivity.kt:105, 181, 216 | confirmed (code) | UI |
| R-44 | Photos saved from Search or Tags get an empty albumKey, so tapping them in Collections dead-ends on route `photo_detail//…`. | SearchPhotoDetailScreen.kt:247-51; KeywordPhotoDetailScreen.kt:239-43 | likely | UI |
| R-45 | The photo viewer has no error or password state: it spins forever offline, and the password dialog appears later on another screen. | PhotoDetailScreen.kt:134-44 | confirmed (no error branch) | UI |
| R-46 | A gallery that fails partway shows a partial grid as if complete, and retry is blocked. | SmugViewModel.kt:1560-4, 1824-6 | likely | VM, UI |
| R-47 | Loading, empty, error and locked are conflated: "No photos match…", "Enter a word…", "Photos (0)" while loading, "No public galleries" offline, and a dead `photosError`. | PhotoGridScreen.kt:282-304; SearchTabView.kt:318-371; SiteHubController.kt:212-7 | confirmed (code) | UI |
| R-48 | Returning from a photo scrolls the grid to the top. The viewer jumps back to the original photo as pages stream in and on rotation. | PhotoGridScreen.kt:96-9; PhotoDetailScreen.kt:151-70 | confirmed (code) | UI |
| R-49 | Also in the UI: raw exception text shown to users; one-tap collection delete with no confirmation; "Save to Collection" writes on open; touch targets under 48 dp; the three photo viewers have drifted apart; dead UI (SiteExplorerScreen isn't in the NavHost, and others); per-item `collectAsState`; scroll offset read in composition; the light system theme gives invisible status-bar icons (likely). | see UI report items 14, 17, 21-24, structure | mixed | UI |

### T9 — Observability

| ID | Finding | Where | Conf. | Domain |
|---|---|---|---|---|
| R-50 | Release builds have no diagnostic channel. The dot and sync paths are gated or swallowed, about 52 of 129 `catch` blocks log nothing, and the `onFinalFailure` hook isn't wired, so network failures are never recorded. (Refines findings #7.) | SmugLog.kt; AppModule.kt; RetryingCallFactory.kt | confirmed (code) | TST |
| R-51 | No crash channel: no uncaught handler, no `ApplicationExitInfo`, no `SourceFile,LineNumberTable`, and `mapping.txt` isn't archived. | proguard-rules.pro | confirmed | TST |
| R-52 | Debug HTTP logging prints `APIKey`, `Password=`, the unlock form body and session cookies. The comment claims otherwise. | AppModule.kt:33-43 | confirmed (debug only) | API, SEC, TST |

### T10 — Privacy and security

| ID | Finding | Where | Conf. | Domain |
|---|---|---|---|---|
| R-53 | **Password-gallery CDN links work without the password or session** ✔. Collections share sends the raw original link, so anyone who receives it can open a password-protected photo. It also shares full EXIF (GPS). The QR code can encode a local file path. | CollectionsTabView.kt:505-20; PhotoDetailScreen.kt:642-711; SmugViewModel.kt:1601-7 | confirmed ✔ (206 without cookie) | SEC, UI |
| R-54 | At targetSdk ≥ 31, `allowBackup=false` doesn't stop device-to-device transfer. There's no `dataExtractionRules`, so the DB, private originals and the password file move to a new phone, and there the password store falls back to plaintext (R-25). | AndroidManifest.xml:11-24 | likely (documented platform behaviour) | SEC |
| R-55 | "Download photo" fails on API 26–29: the storage permission is never requested, and it writes a direct path under scoped storage. On 30+ it silently overwrites same-named files, saves as `.jpg` regardless of type, and leaves truncated files on failure. | PhotoDetailScreen.kt:744-97 | confirmed (code); API-level effect not reproduced | SEC |

### Casting (SEC)

| ID | Finding | Where | Conf. |
|---|---|---|---|
| R-56 | Cast discovery (an SSDP scan every 8 s, plus the MediaRouter active scan) is never stopped after picking a device. It runs for the whole process life, and may leak a multicast socket per cycle. | CastManager.kt:268-296, 592-740 | confirmed (code) |
| R-57 | Stop Casting during the connect window is undone: the device reconnects and the port-8080 server restarts. | CastManager.kt:310-413 | likely |
| R-58 | The web companion server hides bind failures (the UI shows a dead URL), `stop()` can stall the main thread, and its queue is unbounded. | WebCompanionServer.kt:33-84 | confirmed/likely |
| R-59 | Unsynchronized slideshow state can throw on stop or replace, which crashes the app (narrow window). Google devices are listed and activated under different IDs, and any unknown DIAL device is labelled "Amazon Fire TV". | CastManager.kt:569-590, 234, 699-716 | possible / confirmed |

### Concurrency and performance (VM)

| ID | Finding | Where | Conf. |
|---|---|---|---|
| R-60 | Keyword and album filtering run on Main over up to ~10k photos, compiling a `Regex` per photo per emission. | TagSearchController.kt:115-149; SmugViewModel.kt:1515-46 | likely |
| R-61 | The image-details/EXIF cache keeps failures forever, uses the last-opened gallery's password, and is never evicted. | SmugViewModel.kt:1876-2064 | likely |
| R-62 | Hardcoded dispatchers in about 10 places bypass the injected one. Tag-loader cursors are written from IO and read on Main with no synchronization. | see VM report "minor" | confirmed (trace) |

### T8 — Tests that agree with the bugs

| ID | Finding | Where | Conf. |
|---|---|---|---|
| R-63 | `FakeCollectionDao` diverges from Room in about 12 methods. It seeds the dot from folders (the reverted #2), treats an unparseable date as recent, uses exact matching where SQL has a suffix `LIKE`, `searchNodesInScope` can't return folders, `insertSearchResults` appends, and important methods are no-ops. | SmugMugRepositoryTest.kt:34-230 | confirmed (code vs SQL) |
| R-64 | Fixtures use a `ParentNode` shape SmugMug never sends, including **my two Stage-1 tests**. One fixture is generated by the model under test (`Gson().toJson(model)`). | SmugMugRepositoryTest.kt:831, 929, 978, 1119, 1256 | confirmed ✔ |
| R-65 | The API snapshots (172) are gitignored and untracked, with absolute `c:/…` paths. Filenames ignore `_filter`, `_filteruri` and `Order`, so fields the app now depends on are never parsed from real data. One test passes by returning early; two assert nothing. The suite writes into `.gemini/antigravity-ide/`. | SmugMugApiTest.kt | confirmed |
| R-66 | `AlbumPathResolverTest` tests pasted *copies* of ViewModel functions, not the functions themselves. | AlbumPathResolverTest.kt:14-118 | confirmed |
| R-67 | No migration tests, although schemas 13–15 are exported. No instrumented or Compose tests at all. No worker, password-store or web-server tests. | — | confirmed |
| R-68 | The Stage-1 unlock test models the session as a global boolean, not a cookie, so a wrong implementation would pass. | SmugMugRepositoryTest.kt:~993 | likely |
| R-69 | `echo $?` printed 0 on a Gradle run that failed (a concurrent-build collision). Checking the exit code alone isn't enough; also look for `BUILD SUCCESSFUL`. | — | possible |

---

## 3. What this changes about the dot design

[new-gallery-indicator.md](../design/new-gallery-indicator.md) was approved, but it rests on
two premises this review disproves. It needs a revision **before any Stage 1 code**:

1. **§3.2 and §3.4 assume `cached_albums.parentNodeId` comes from `ParentNode`.** It doesn't
   (R-05). Parent source (✔ verified): `Uris.Folder`/`UrlPath` → folder NodeID, matched locally
   by `UrlPath`, with a fetch of `folder/…` for unknown folders.
2. **§3.5 (return to deleting a folder's listing) would trigger R-01/R-04**: it deletes folder
   rows whose children stay cached, and the resulting "parent missing → fetch" path writes
   self-parented rows. T1 must be fixed first.

It also must account for R-36 (the cache serving the anonymous listing after an unlock) and
R-22 (the launch unlock that doesn't unlock). My two Stage-1 tests are rewritten on real shapes
(R-64, R-68).

---

## 4. Proposed order of work

Each phase has a checkable exit criterion. Every fix gets a test that fails on the old code
first, built on a real payload shape. Nothing is published until phases 0–3 are done
*(owner, 2026-09-29: no publish until fixed)*.

| Phase | Contents | Exit criterion |
|---|---|---|
| **0 — Stop the damage** (small, independent) | R-42, R-43 (crashes); R-21 (keep passwords on transient errors: delete only on an explicit 401 from the right root); R-02, R-03 (`UNION` plus cycle guards); R-54 (`dataExtractionRules`); R-37 (a cache-less client for files) | Each has a failing-first test; a self-parent row can no longer hang any query or walk |
| **1 — See what's happening** | The observability of R-50/51/52: a release-safe ring log + `Redactor`, HTTP telemetry with an `actionId`, the sync report, a cache **doctor** runnable in release, a hidden Diagnostics screen with export, and line-number mapping. Replace the fake DAO with Room-in-Robolectric (R-63), and add the migration-test harness (R-67). | On the owner's phone, Diagnostics exports a report; the doctor lists self-parent, orphan and `X!parent` counts from real data |
| **2 — Tree integrity, then the dot** | T1: one owner for the parent (a full listing, or `Folder`/`UrlPath`; never `ParentNode`), lineage stops overwriting, `forceRefresh` removes deleted children, the stop marker gets one writer. Then the revised dot design, with session unlock (R-22, R-36). Fix #5 and #6. | On the device: the doctor reports 0 self-parents; Home → Family → School are lit; opening a gallery clears only its own chain |
| **3 — Scoping** | T2: a `SiteSession` scope cancelled on switch; writers take an explicit nickname; one `BrowserState` with one load job and one navigate entry point; per-destination album state; SavedStateHandle for navigation. T3: one `UnlockManager` (saved / session / invalid). | Scenario tests: switch site mid-sync; open B while A streams; back while a child loads; double tap; process death |
| **4 — API contract and cache** | T4 and T5: one pagination helper that re-applies `_expand`; correct param names (`Order`, `NodeURI`); drop `Password=` from GETs; request `{key}-0`; per-request cache policy (`no-cache` on refresh, bypass after unlock); stop at 10k. Add a test per endpoint that the params sent are echoed back. | The param-echo test is green for every endpoint; thumbnails appear on page 2+ |
| **5 — Offline files** | T6: one owner of the files (reference-counted), temp-then-rename, a unique worker with constraints, retryable 408/429, a failure state rather than `isDownloaded=true`, and album download moved into the worker. | Worker tests: 429, offline, concurrent, shared file across collections |
| **6 — UI states and polish** | T7: explicit Loading/Empty/Error/Locked per screen; partial-load error; scroll retention; one photo viewer; plain-language errors; confirm on delete; QR off the main thread; T10 sharing (web URL only, EXIF). Casting fixes R-56–59. Compose/instrumented tests for the top journeys. | Instrumented tests for the 7 journeys in the UI report |

Phases 0 and 1 go first because every later fix needs evidence from the owner's device, and
today there's no way to get it.
