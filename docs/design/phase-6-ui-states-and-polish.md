# Design: Phase 6: UI states and polish (T7, T10, casting)

Status: **SIGNED OFF 2026-10-01 (§9.1: all 16 as recommended).** Was: DRAFT, needs owner sign-off (§9). Steps **6-0** (harness and evidence), **6-1** (folder
tree walk survives a locked folder) and **6-2** (no avatar 404s) change no meaning and can start
before sign-off. Every later step depends on at least one answer in §9 (the step table names it).
Written 2026-10-01 by the planning agent, read-only, against `main` at `c3e1eff` (Phase 5 steps
5-0…5-10 committed; 5-11, the Phase 5 exit check, is running in parallel and owns the uncommitted
`OfflineDownloader`/`OfflineGalleryTest`/`FakeSmugMugServer` edits; suite 632 tests at 5-10 per the
progress log, **not re-run here**). Phase 6 starts after the 5-11 commit. While this was written,
5-11 was also editing `OfflineMessages.kt`, `OfflineReader.kt`, `OfflineRowState.kt` and
`AppModule.kt`; steps 6-3, 6-4 and 6-6 re-read those files at the 5-11 commit before changing them.
Scope: review §4 row "6 — UI states and polish"
([whole-app review](../review/2026-09-29-whole-app-review.md)): T7 (R-42…R-49), T10 (R-53, R-55,
R-25 = Phase 3 Q10), casting (R-56…R-59), R-61, plus everything earlier phases deferred to Phase 6
(listed in §1.4) and the **final, batched phone phase** *(owner, 2026-10-01)*.
No Room migration. No new runtime dependency or permission (one test-only dependency, §6).
Template: [phase-5-offline-files.md](phase-5-offline-files.md).

Exit criterion (review §4): **"Instrumented tests for the 7 journeys in the UI report."** The UI
report is not in the repo (phase 3 design §12 noted the same), so §8.1 defines the 7 journeys and
Q11 asks how they run. §8 adds the emulator checks and the phone checks.

## 1. What the code does today (`c3e1eff`), and what the live API says

`VM` = `ui/viewmodel/SmugViewModel.kt`, `Repo` = `data/repository/SmugMugRepository.kt`, `Grid` =
`ui/grid/PhotoGridScreen.kt`, `Viewer` = `ui/detail/PhotoDetailScreen.kt`, `Cast` =
`data/cast/CastManager.kt`. Every citation names the function; if lines moved, search by name.

### 1.1 Premise checks (live, read-only, anonymous, 2026-10-01)

Every call was a GET with `APIKey=<KEY>` read from `local.properties` inside a script and stripped
from all output; CDN and web URLs were fetched with no key. No password was read or used; no
`!unlock`, no write. `exiftool` read two downloaded public files, which were deleted afterwards.

| # | Question | Command shape | Result | Verdict |
|---|---|---|---|---|
| L1 | Do the avatar URLs the app tries exist? | `GET https://secure.smugmug.com/users/{nick}-avatar.jpg` and `GET https://{nick}.smugmug.com/bioimage` for idzifamily, smugmugfilms, corvettemuseum, uphs, daemenuniversity | **404 on 5 of 5 sites, both URLs** (`text/html`). `user/{nick}?_expand=BioImage` carries a BioImage with `ThumbnailUrl` on 3 of 5 (idzifamily, corvettemuseum, daemenuniversity); that thumbnail answers 206. The app's step-1 URL `photos.smugmug.com/photos/i-{key}/0/M/i-{key}-M.jpg` also answers 206 for idzifamily | confirmed: steps 0 and 2 of `ProfileAvatar` are always 404 |
| L2 | What a locked folder's listing answers anonymously (FolderTreeSync abort, 4-13 (a)) | `GET node/sNWzhX`, `node/sNWzhX!children`, `node/6KHfpq!children` | `sNWzhX` = "2023 Homecoming", `Folder`, `SecurityType: Password`, `UrlPath /Memories/2023-Homecoming`, under the **public** folder `6KHfpq` "Memories". `sNWzhX!children` → **404 "Not Found"**. `6KHfpq!children` → 200 | confirmed: a locked folder answers **404**, not the 401/403 the walk treats as "skip" |
| L3 | What a gallery that does not exist answers | `GET album/ZZZZZZ`, `album/ZZZZZZ!images` | both **404** | confirmed: "gone" and "locked folder" share the code 404 |
| L4 | P16 (phase 4) again: password-folder photos returned anonymously | `GET user/idzifamily!recentimages?count=25`; `GET image/ttDKqjt-0`; the thumbnail; `user!urlpathlookup?urlpath=<the gallery path>`; `node/2sDN5x!children` | 1 of 25 recent images is under `/Family/Events/2025-to-Current/…` (key `ttDKqjt`). `image/ttDKqjt-0` → **200 with `ArchivedUri`** (the original), no `WebUri`, no album link; the thumbnail answers 206 with no session. The gallery itself is hidden: `urlpathlookup` returns no Album, `node/2sDN5x!children` 404 | confirmed: SmugMug serves this photo, original included, to anyone; the app shows it on Home (§3.9) |
| L5 | The site's bio image | `GET user/idzifamily!bioimage` | 200; `ThumbnailUrl` is a photo under `/Family/Holidays/2014-12-25-Christmas/` | observed; the owner chose it, recorded only |
| L6 | The web link of a photo (sharing, R-53) | `GET album/N74KSK!images?count=1&_filter=ImageKey,WebUri,ArchivedUri` | `WebUri = https://gallery.idzifamily.com/Kentridge-Cross-Country/2026-09-26--KR-XC-Three-Course-Challenge/i-XVRvVTM`, i.e. the gallery `WebUri` + `/i-{ImageKey}`. `image/{k}-0` has **no** `WebUri` (also for `ttDKqjt`). The app's `getAlbumImages` `_filter` does not ask for `WebUri` (4-9) | confirmed (1 sample; the shape matches P4 of phase 4) |
| L7 | What a shared picture carries (R-53 "full EXIF (GPS)") | download the original, X3 and L of `XVRvVTM`; `exiftool -GPS:all -SerialNumber -LensSerialNumber -Artist -Make -Model`; `recentimages?count=100&_filter=Latitude,Longitude` | Original (688,974 B), **X3 (427,890 B) and L (77,161 B) all keep** `SerialNumber`, `LensSerialNumber`, `Artist` (the owner's name), Make/Model. No GPS block in this camera photo. **0 of 100** recent images have a non-zero API `Latitude` | confirmed: SmugMug's renditions do not strip camera data; GPS **not** shown on this account (unverified for phone photos, E2) |
| L8 | A gallery with its own password, anonymous | `GET album/zzwtrF` | 200, `SecurityType: Password` (unchanged since 4-8) | confirmed |
| L9 | Not checkable from here | — | (E1) what anonymous `album/{key}` and `!images` answer for a gallery **under** the Family folder (needs a Family AlbumKey; none is reachable anonymously, L4) — 404 or 200; (E2) whether the owner's phone photos carry GPS into the original; (E3) the cast devices on the owner's LAN; (E4) Compose tests under Robolectric 4.11.1 with this BOM | §6: E1 and E4 are 6-0 tasks on emulator-5554 / in the JVM; E2 and E3 are phone-phase checks |

### 1.2 The code, finding by finding

| R / item | Review claim | Today (`c3e1eff`) | Status |
|---|---|---|---|
| R-42, R-43 crashes | duplicate keys; `/` in a route | Fixed in Phase 0 (`CollectionRowKeys`, `Routes.castController`) | **fixed** |
| R-44 empty albumKey | Search/Tags saves get `albumKey = ""` | Still: `SearchPhotoDetailScreen`/`KeywordPhotoDetailScreen` take `uris.album`, which **no image payload carries** (4-9). Collections groups them under `""` and `onImageClick("", key)` → `navigate("photo_detail//key")`, which matches no route: Navigation throws `IllegalArgumentException` | **open**, likely a crash (code) |
| R-45 viewer has no error/password state | spins offline; dialog appears elsewhere | `Viewer`: `if (lazyPhotos.itemCount == 0) { spinner; return }` (134-144), no error branch. `PasswordPromptDialog` is hosted only in `BrowserScreen` and `Grid` | **open** |
| R-46 partial gallery | partial grid looks complete; retry blocked | Since 3-5 a page-2+ failure keeps the pages and sets `error`; `AlbumLoader.blockingError` hides it while photos exist (139), so **nothing on screen says the gallery is incomplete**. Retry is no longer blocked (re-selecting an errored album restarts it), but there is no button for it | **open** (half fixed by 3-5) |
| R-47 states conflated | "No photos match…", "Enter a word…", "Photos (0)", "No public galleries" offline, dead `photosError` | All still there: `Grid` 284-317 says "No photos match your current search or type filter." for a gallery that is **really empty**; `SearchTabView` 318-323 says "Enter a word or phrase to search" for **zero results**; `HomeTabView` 865 "No public galleries found." also when the request failed; `photosError` is always `null` (`SearchController` 249/315/343) | **open** |
| Red heading (4-13 (c)) | — | `Grid` 253-259: every failure, including "You're offline, and this hasn't been opened on this device yet.", sits under a red bold "Access Error / Failed to Load" | **open** |
| R-48 scroll | grid back to top; viewer jumps back | `Grid` 96-99 `LaunchedEffect(sortBy, filterType) { scrollToItem(0) }` runs on **every** return to the grid. `Viewer` 151-170 re-scrolls to the target whenever `itemCount` changes and the current page is not the target | **open** |
| R-49 polish | raw exception text; one-tap delete; Save writes on open; touch targets < 48 dp; three viewers; dead UI; per-item `collectAsState`; scroll offset read in composition; light status bar | Raw `localizedMessage` at 10 sites (VM 869, 937, 1264; `SearchController` 351; `TagSearchController` 491, 497; `SiteHubController` 311; `AlbumLoader` 191; `CollectionsTabView` 664; `Viewer` 793). `AddToCollectionsDialog` 47-68 **writes a bookmark to every last-used collection when the dialog opens**. Collections row buttons are `size(24.dp)`. Collection delete asks first when files go (5-9); unbookmarking an image with the only saved copy does not. `SiteExplorerScreen` is not in the NavHost, but its `ProfileAvatar` is used by 4 live screens (L1). Scroll offset is read in composition (`Grid` 173-180, `FoldersTabView` same pattern) | **open** |
| R-53 sharing | raw original link for password photos; full EXIF; QR of a local path | Photo share text and QR = `photo.webUri ?: photo.archivedUri` (`PhotoActionCapsule` 389-416, `sharePhotoLink` 633-640, `sharePhoto` 642-711). Gallery photos have **no** `webUri` (L6), so the link **is the CDN original**, which opens with no password (R-53 ✔). "Share via…" attaches the original's bytes with camera data (L7). Collections share builds `archivedUri = extraData ?: thumbnailUrl` (616-632). For a saved offline photo `archivedUri` is null, so the QR gets `""` and **spins forever** (`QrShareDialog` 88-90); a local path is no longer encoded | **open**; "local path" is stale (§12) |
| QR on main thread | — | `QrShareDialog` 53: `remember(url) { generateQrCodeBitmap(url) }` = 262,144 `setPixel` calls on Main | **open** |
| R-55 download | API 26-29 broken; overwrites; `.jpg` always; truncated files | `downloadPhotoToGallery` 744-797: `HttpURLConnection` to `getExternalStoragePublicDirectory(DOWNLOADS)/{name}.jpg`, no permission request (WRITE is declared `maxSdkVersion=28`, never requested), `FileOutputStream` overwrite, no temp file, raw `localizedMessage` toast | **open** |
| R-25 plaintext fallback (Phase 3 Q10) | — | `EncryptedPasswordStore.buildPrefs` 79-104 falls back to plain `smugview_passwords_fallback` on any Keystore failure and never moves them back | **open** |
| R-56 discovery never stops | SSDP every 8 s + MediaRouter active scan | `startDiscovery` 268-296; `stopDiscovery` is called only from the selector's `onDismiss`, **not** when a device is picked (`Grid` 713-734). The SSDP socket is closed only on the normal path (731); an exception after creating it leaks it | **open** |
| R-57 Stop during connect | reconnects | `connectToDevice` 310-384: `delay(1000)` then `CONNECTED` and `webCompanionServer.start` with no check that `disconnect()` ran meanwhile; Google: `selectRoute` then `onSessionStarted` sets `CONNECTED` whatever happened | **open** |
| R-58 web companion | bind failure hidden; `stop()` stalls Main; unbounded queue | `start` 37-46 logs and returns; the UI still shows `http://{ip}:8080` (`CastControllerScreen` 189, `Grid` 631-638). `stop` 71-85 `awaitTermination(1 s)`, called from `disconnect()` on the caller's thread. `Executors.newFixedThreadPool(4)` has an unbounded `LinkedBlockingQueue`, so the "pool saturated" catch (62-65) never fires | **open** |
| R-59 slideshow, ids, labels | unsynchronized; Google ids differ; unknown DIAL = "Amazon Fire TV" | `startSlideshowLoop` 569-582 reads `slideshowUrls[slideshowIndex]` on `Dispatchers.Default` while `stopSlideshow` 584-590 sets `emptyList()`/0 from the caller's thread (`IndexOutOfBounds` or `% 0`); the route list uses `route.id` (232-243), the session uses `castDevice.deviceId` (163-175); 699-717 labels every non-Roku, non-Google device "Amazon Fire TV" | **open** |
| R-61 details/EXIF cache | failures kept forever | `getImageExif`/`getImageSizeDetails`/`getImageDetails` (1828-1945) `getOrPut` a flow per key; a failure stays until the site changes | **open** |

### 1.3 New findings (rows for `findings.md` in §11)

- **N1 A photo-search failure is an uncaught exception.** `SearchController` 261-268 launches
  `performBackgroundSearchImages` in the site scope with `try/finally` and **no catch**; the
  repository rethrows (1160-1163). `SiteSession.scope` is `SupervisorJob + Main.immediate` with no
  `CoroutineExceptionHandler` (`SiteSession.kt` 24-26), so the exception reaches the thread's
  uncaught handler: on Android, a crash. Reached by a search while offline (search is never cached),
  on a 429 after retries, or a 5xx. *likely (code)*; 6-6's red test makes it confirmed or not.
- **N2 Launch with an uncached profile never stops spinning.** `loadUserProfile`/`selectSite` put a
  failure into `SplashUiState.Error(localizedMessage)` (869, 937), which only the dead
  `SiteExplorerScreen` renders; `retryActiveSite` has no caller; the Folders listing stays
  `BrowserUiState.Loading` (its initial value). Offline with no cached `user/{nick}`, or a 429/5xx at
  launch, gives an endless spinner. The Folders "Retry" is `currentFolderId?.let { … }`: a no-op
  when no folder was ever opened. *likely (code)*.
- **N3 The folder-tree walk stops at the first locked folder (4-13 (a)).** `syncFolderTree` 946-1002
  skips only 401/403; the real answer is 404 (L2), so any locked folder in BFS order ends the walk
  (`complete=false`, the `treeRepairDone` flag never set, so the forced walk repeats every launch).
  The fake's `cookieGate` answers 401 (phase 4 deviation 4-0), which is why the tests pass (T8).
- **N4 Three avatar 404s at every launch (4-13 (b), the phone report's three `secure.smugmug.com`
  404s).** `ProfileAvatar` tries `secure.smugmug.com/users/{nick}-avatar.jpg` first (L1: always 404),
  in `BrowserScreen` 270, `FoldersTabView` 303 and `HomeTabView` 735; its step 2
  `{nick}.smugmug.com/bioimage` is also always 404. Each composition starts again at step 0.
- **N5 `handleAlbumLoadError` deletes a bookmark on any 404, and since 5-6 that cascades to its
  kept-offline rows and their files.** VM 1177-1179 calls `removeBookmarkGlobally(albumKey)` on a
  404; the folder path does the same when the folder has no cached row (431). A 404 is also how a
  locked folder answers (L2), and E1 may show the same for galleries under one. One-way.
- **N6 The "viewed" mark is written in three places, and before the photos are shown.**
  `selectAlbum` 1444-1462 marks after the lit decision, which is taken **before** `getAlbum` raises
  the index ILU, so it writes the stale ILU; `loadAlbumPages` 1583-1586 marks again after `getAlbum`;
  `submitPassword` 1244-1250 marks before the gallery opens. A gallery whose prompt is dismissed, or
  whose load fails offline, is marked viewed and loses its dot unseen (the R-24 symptom, still live
  through `selectAlbum`). A complete gallery restored from `AlbumLoader`'s memory is not re-checked
  for "lit", so it can show old photos while its dot says something is new.
- **N7 The global API error toast fires for background work.** `AppModule.provideRetryingCallFactory`
  192-212 toasts every final non-404 API failure without `X-Ignore-Errors`; `user!albums` (the crawl),
  `image!search` and `user!recentimages` send no such header, so a background 429/5xx pops "Rate Limit
  Exceeded: …" or "SmugMug Internal Server Error: …" over whatever the user is doing.
- **N8 SmugMug serves photos of hidden password-folder galleries anonymously (L4), and the Home tab
  shows them.** Server-side; the app can only choose not to display them (Q6).
- **N9 `PasswordStore` redaction misses a changed password until restart (1a-3 known gap).**
  `DiagnosticsInitializer` 39 refreshes the redactor's secrets when `unlockedKeys` emits; saving a new
  password under an existing key leaves the key set equal, so the `StateFlow` does not emit.

### 1.4 Items earlier phases sent here

| From | Item | Where in this plan |
|---|---|---|
| Phase 0 progress | `handleAlbumLoadError` removes the bookmark on any 404 | 6-11, Q4 (N5) |
| Phase 1a/1b | changed-password redaction refresh | 6-10 (N9) |
| Phase 1c, 2-12 | 12 → 13 migration untested (schema 12 never exported) | 6-19, Q13 |
| Phase 2 (2-10 deviation, 2-12 gaps) | `getAlbum` raises ILU and `selectAlbum` marks viewed | 6-12, Q3 (N6) |
| Phase 2 design §3.x | other screens still show `localizedMessage` | 6-3…6-7 |
| Phase 3 Q10 | R-25 plaintext fallback | 6-10, Q5 |
| Phase 4 §12 | empty/locked/10k wording (R-47); password prompt outside Browser/Grid (Q5 b); R-61; P16 | 6-4, 6-6, 6-7, 6-17, 6-13 |
| Phase 4 §12 | Hub featured list order | deferred (§13): not a defect |
| Phase 4 4-9 | no image-to-album link on image payloads; share/open-in-album | 6-14, 6-16 |
| Phase 4 4-13 (a)(b)(c) | tree walk abort; avatar 404; red heading | 6-1, 6-2, 6-4 |
| Phase 5 §12 | Save dialog writes on open (R-49); a settings switch for the network rule; viewer error state (R-45); download (R-55); share local file vs link (R-53); confirm on other deletes | 6-16, Q15, 6-7, 6-15, 6-14, 6-16 |
| Phase 5 5-9 | new words flagged to the owner (gallery failure lines, "Saved Photos" heading, dialog buttons) | §5, Q1 (listed again for one review) |
| Phase 5 §7.3, Phases 1-4 | phone-only checks | 6-22 (§8.3) |

## 2. Who owns what after Phase 6

| Concern | Only owner |
|---|---|
| The words for a failure or a state ("why", "what now") | `UserMessages` (new, pure, `ui/text/`): `Problem(kind, subject, code)` → heading, body, actions. `OfflineMessages` (Phase 5) and `AlbumLockedException`'s three texts stay as they are and are reused, not copied |
| What a gallery screen shows | `GridView.of(albumState, filters)` (pure): `Loading`, `Failed(problem)`, `Empty`, `FilteredEmpty`, `Photos(banner)` |
| What the site screens show before the profile resolves | the VM's `siteProblem: StateFlow<Problem?>`; the Folders tab and Home render it with "Try again" → `retryActiveSite()` |
| "Is this gallery viewed?" | one call, in `loadAlbumPages`, after page 1 is on screen (Q3) |
| Removing a bookmark | the user only (Q4); no load path deletes one |
| What leaves the phone when sharing | `ShareContent` (new): a SmugMug **web page** link, or a picture with camera data removed (Q7) |
| The password prompt on screen | one `PasswordPromptHost(viewModel, onDismiss)` composable, used by Browser, Grid and Viewer |
| Cast discovery lifetime, connect generation, web companion port | `CastManager` (one discovery job with a stop rule, a generation counter, `webCompanionUrl: StateFlow<String?>`) |
| Uncaught failures in site work | `SiteSession`'s `CoroutineExceptionHandler`: logs `SmugLog.e` (redacted, counts in `report.txt`), never crashes; it is a net, every known path still catches its own failure |

## 3. Design

### 3.1 The words: `Problem` and `UserMessages` (6-3)

`Problem.from(error: Throwable?, subject: Subject)`, where `Subject` ∈ {Gallery, Folder, Photo, Site,
Search, Tags}. Kinds, in this order of tests:

| Kind | When | Retry? |
|---|---|---|
| `OfflineNothingSaved` | `isSyntheticCacheMiss` 504; `UnknownHostException`/`ConnectException`/`NoRouteToHost` with no cached copy | Try again |
| `Slow` | `SocketTimeoutException`, `InterruptedIOException` | Try again |
| `RateLimited` | 429 (after `RetryingCallFactory`'s tries) | Try again |
| `SmugMugTrouble(code)` | 500-599 except the synthetic 504 | Try again |
| `Gone` | 404 when the subject is not under a password root without a session (`UnlockManager.lockOf`, cache-only) | Go back; "Remove from collections" when bookmarked (Q4) |
| `Locked` | `AlbumLockedException` with no pending reason; a 404/401 under a locked root | Enter password |
| `LockedPending(Offline|Busy)` | `AlbumLockedException.pendingReason` | reuses the 4-8 texts unchanged |
| `Unexpected(code)` | anything else; names the code or the exception class, never its message | Try again |

`SmugMugErrorMapper.userMessage` keeps its signature for its existing callers and delegates to
`UserMessages` (its literal-text tests are updated in 6-3 because the text changes: Q1). A guard test
(`NoRawExceptionTextGuardTest`, pattern of `NoParentNodeParsersGuardTest`) fails when
`localizedMessage`/`.message` reaches a `Text(`/`Toast` in `ui/` outside `UserMessages`.

### 3.2 Gallery grid (6-4)

`AlbumState` gains `problem: Problem?` and `expected: Int?` (the album's `ImageCount` from `getAlbum`);
`error: String?` stays (the 4-8 lock texts and `LockedAlbumTest` read it). `GridView.of`:

| State | Shows |
|---|---|
| no photos, `loading` | spinner + status line (unchanged) |
| no photos, `problem` | neutral heading (white, not `colorScheme.error`) + body + actions from `UserMessages`; `Locked` re-opens the prompt with "Enter password" |
| no photos, `complete`, raw list empty | `EMPTY_GALLERY` |
| no photos after the type/tag filter, raw list not empty | `FILTER_EMPTY` + "Show all" (clears type and tags) |
| photos, `problem`, not `complete` | the grid + a bottom banner `PARTIAL` with "Load the rest" (= `selectAlbum(force = true)`); it shares the slot with the 5-9 offline notice (offline wins: it already says what is shown) |
| photos | grid (unchanged) |

The red "Access Error / Failed to Load" heading is deleted.

### 3.3 Site, Folders and Home (6-5)

- `loadUserProfile`/`selectSite` failure → `siteProblem = Problem.from(e, Site)`; the navigator
  publishes `BrowserUiState.Error(problem)` so the Folders tab shows it; its Retry calls
  `retryActiveSite()` when no folder is open (today a no-op). `SplashUiState.Error` keeps a
  `Problem` instead of text. Process death: `pendingRestore` survives a failed profile and is used by
  the retry (3-9 behaviour kept).
- Folders `BrowserUiState.Error` takes a `Problem` (folder subject); text from `UserMessages`.
- Home: `SiteHubController` records `albumsProblem`; the featured row shows `HOME_OFFLINE` or the
  short cause, and `HOME_EMPTY` only when the request **succeeded** with zero galleries.

### 3.4 Search and Tags, the crash net, and the toast (6-6)

- `backgroundSearchJob` catches (rethrowing `CancellationException`) and sets
  `photosProblem`; `SearchUiState.Success.photosError: String?` becomes `photosProblem: Problem?` and is
  finally rendered (it was dead). Zero results with nothing loading → `SEARCH_NO_MATCH`. The
  "Photos (n)" tab shows "Photos (…)" while photos load (as Galleries/Folders already do).
- `SearchUiState.Error` and both `TagSearchController` failures take a `Problem`.
- `SiteSession` gets a `CoroutineExceptionHandler` (log, never rethrow, counted in `report.txt` as
  `uncaught_site_errors`).
- Q2 (a): the global API toast in `AppModule` is removed; screens show their own state (6-3…6-7
  put one on every screen first). The `onFinalResponse` hook stays for telemetry only.

### 3.5 Photo viewer and scroll positions (6-7)

- The viewer renders `GridView`-equivalent states for its album when it has no photo to show:
  offline and not saved → `OfflineNothingSaved` (photo subject) with Back and Try again; `Gone`;
  `Locked` → the prompt **in the viewer** (`PasswordPromptHost`), dismiss = Back.
- R-48 grid: the "scroll to top" runs only when sort or filter **changed** since the last time it ran
  (`rememberSaveable` of the last applied pair), not on every return.
- R-48 viewer: scroll to the target only until the user has swiped once or the target was reached;
  `hasScrolledToTarget` and the current image key are `rememberSaveable` (rotation).

### 3.6 Folder-tree walk (6-1)

`syncFolderTree`: 401/403/**404** → skipped (`skippedLocked` when the row's access or the lineage says
Password, else `skippedMissing`), the walk continues, `complete` stays true. 429 or offline
(`isOffline`) → abort as today (do not hammer). 5xx → that folder is skipped, `complete=false` (the
flag is not set; the next launch retries), the walk continues. Notes:
`forced=… complete=… listed=… skippedLocked=… skippedMissing=… skippedFailed=…`.

### 3.7 Avatar (6-2)

`UserData` gets `bioImageThumbnailUrl` from the `BioImage` expansion the profile call already
requests (`SmugMugApi.getUserProfile` `_expand=BioImage`). `ProfileAvatar` shows that URL resized to
`/S/` (pure `avatarModel(user)`), else the first letter. No request to `secure.smugmug.com` or
`{nick}.smugmug.com/bioimage`. `ProfileAvatar`/`ProfilePreviewCard` move to
`ui/component/ProfileAvatar.kt`; whether `SiteExplorerScreen.kt` is then deleted is Q14.

### 3.8 "Viewed" and bookmarks that are gone (6-11, 6-12)

- **Q3 (a):** `markNodeAsViewed` is called once, in `loadAlbumPages`, after page 1 is published
  (photos, or a confirmed empty gallery), with the NodeID from `getAlbum` (or the index row), after
  `getAlbum` raised the ILU. Removed from `selectAlbum` and `submitPassword`. Not marked: a prompt, a
  failure, the offline saved-photos view (5-9), an LRU restore. An LRU restore of a gallery that is
  now lit restarts from the network (4-6 rule), and is then marked.
- **Q4 (a):** no load path removes a bookmark. `Gone` in the grid offers "Remove from collections"
  when the gallery is bookmarked (calls the existing `removeBookmarkGlobally`, with the Phase 5
  confirmation when saved files would go). The folder path (VM 431) drops its removal.

### 3.9 Privacy: Home's recent photos (6-13, Q6 (a))

A recent image whose thumbnail path falls under a cached folder that is password-protected
(`getNearestCachedAncestorNode(thumbnailUrl)` + `UnlockManager.lockOf`, cache-only) and has **no
session** is left out of Home's row. With a session it shows. Unknown (no cached ancestor) shows, as
today. The SmugMug-side exposure (L4) is reported to the owner in §11; the app cannot close it.

### 3.10 Sharing (6-14, Q7 (a))

- Link = the SmugMug **web page**: gallery `WebUri`, folder `WebUri`, photo `WebUri`.
  `getAlbumImages` `_filter` adds `WebUri` (a field, not a param; `ApiContractTest` unaffected;
  `ApiFieldsTest` fixture gains it). A photo without one (search, tags, recent, saved) gets
  `{gallery WebUri}/i-{ImageKey}` from the index (4-10 lookup); none → the link action is disabled
  with `SHARE_NO_LINK`. `ArchivedUri` and any `photos.smugmug.com` URL are never shared as text or QR.
- "Share picture": the saved file when there is one, else the X3 rendition, copied to
  `cache/shared_images/`, then **APP1–APP15 and COM segments removed** (pure Kotlin JPEG segment
  rewrite) and only `Orientation` written back (androidx `ExifInterface`, already on the classpath
  through Coil; declared explicitly). A non-JPEG saved file → the X3 is used. Temp file then rename;
  files older than 1 day in `shared_images/` are deleted at the next share.
- QR: built on `Dispatchers.Default` (`produceState`); a blank or non-`https` URL shows
  `SHARE_NO_LINK` instead of a spinner.

### 3.11 Download photo (6-15, Q8 (a))

API 29+: `MediaStore.Images` insert into `Pictures/SmugView` with `IS_PENDING=1`, stream, then
`IS_PENDING=0`; on failure the pending row is deleted (no truncated file). The display name is the
API `FileName` (MediaStore de-duplicates). Extension and MIME from the response `Content-Type`.
API 26-28: ask for `WRITE_EXTERNAL_STORAGE` (already declared, `maxSdkVersion=28`), write to
`Pictures/SmugView/` via temp-then-rename, then `MediaScannerConnection.scanFile`. Source: the saved
file when there is one (works offline), else `ArchivedUri` through the `@Named("images")` factory
(cache-less, retries, telemetry), else `OfflineNothingSaved`. Bytes are the original (camera data
kept: it is the user's own copy, Q8).

### 3.12 Casting (6-8, 6-9)

- Discovery stops when a device is picked, when the selector closes (`DisposableEffect`), and after
  60 s of scanning. SSDP socket closed in `finally`.
- `connectGeneration: AtomicLong`, bumped by `disconnect()`; the connect coroutine checks it before
  `CONNECTED`, before `webCompanionServer.start`, and before `triggerPendingCasts`. A Google session
  that starts after Stop is ended at once.
- `WebCompanionServer.start` returns `Bound(port)` or `Failed(reason)`, trying 8080…8089;
  `CastManager.webCompanionUrl: StateFlow<String?>` drives both UIs (no URL shown unless bound);
  `stop()` runs on `ioDispatcher`; the pool is `ThreadPoolExecutor(4, 4, ArrayBlockingQueue(16),
  AbortPolicy)`, so the existing drop path works.
- Slideshow state is confined to one `limitedParallelism(1)` dispatcher; an empty list stops the
  loop.
- Google devices: the session's device is matched to its route by `route.extras`'s cast device id
  (pure `routeIdFor`); an unknown DIAL device is labelled `CAST_DIAL_DEVICE`, "Amazon" only when the
  manufacturer says so.

### 3.13 Small items (6-10, 6-16, 6-17)

- **Password store (6-10):** `PasswordStore` gains `revision: StateFlow<Long>` (bumped on every save
  and remove); `DiagnosticsInitializer` collects it (N9). R-25 per Q5.
- **Save dialog (6-16, Q9 (a)):** opening writes nothing; last-used collections are pre-ticked;
  "Save" writes the ticked set and removes unticked ones that were bookmarked; "Cancel" writes
  nothing. **R-44:** the album key is resolved at save time from the photo's path via the index;
  Collections never navigates with a blank key: a blank key opens the photo by image key through the
  same resolution, else shows `Gone`. **Confirm (Q10 (a)):** removing an image bookmark, a saved
  photo or turning off Keep offline asks first when saved copies nothing else uses would be deleted
  (`DELETE_CONFIRM` pattern); other removals stay one tap.
- **Polish (6-17):** R-61 (a failure is dropped from the per-key cache so the next open retries; the
  three maps are capped at 200 keys, LRU); touch targets ≥ 48 dp (`minimumInteractiveComponentSize`)
  on Collections rows and the grid top bar; the header parallax reads the scroll offset inside
  `graphicsLayer {}` instead of composition; light-theme status bar checked on the emulator and fixed
  only if the icons are unreadable.

## 4. Failure design: what the user sees

"Today" is traced from code (*likely*) unless marked. Texts are §5 keys.

| Situation | Today | After Phase 6 |
|---|---|---|
| Offline, gallery never opened | red "Access Error / Failed to Load" + offline text (emulator 4-13) | `OFFLINE` heading (neutral) + `OFFLINE_BODY(gallery)`, Try again, Go back |
| Offline, kept gallery | 5-9 offline notice + saved photos | unchanged |
| Offline launch, profile not cached | endless spinner (N2) | Folders and Home: `OFFLINE` + `OFFLINE_BODY(site)`, Try again |
| 429 at launch or in a gallery | toast "Rate Limit Exceeded: Too many requests…" + endless spinner or the red heading | `RATE_LIMITED` heading + body, Try again; no toast (Q2) |
| 503 on page 3 of a 759-photo gallery | partial grid, nothing says so | grid + `PARTIAL(shown, expected, "SmugMug is having trouble")`, Load the rest |
| Gallery really empty | "No Photos Found / No photos match your current search or type filter." | `EMPTY_GALLERY` |
| Type filter "Videos" on a gallery without videos | same text + "Reset Type Filter" | `FILTER_EMPTY` + Show all |
| Locked gallery, prompt dismissed | goes back (unchanged) | unchanged; not marked viewed (Q3) |
| Locked gallery opened from Search into the viewer | spinner; dialog appears later on another screen (R-45) | dialog in the viewer; dismiss = Back |
| Saved password offline / SmugMug busy (4-8) | 4-8 texts under the red heading | 4-8 texts under `OFFLINE` / `SMUGMUG_TROUBLE` headings |
| Bookmarked gallery deleted on SmugMug (404) | bookmark and its kept-offline files deleted silently (N5) | `GONE` + "Remove from collections" (Q4) |
| Folder under a locked root answers 404 during the tree walk | walk aborts, retried every launch (N3) | skipped, walk completes |
| Search offline | probable crash (N1) | Galleries/Folders from the index; photos: `SEARCH_PHOTOS_FAILED(offline)` |
| Search, no results | "Enter a word or phrase to search" | `SEARCH_NO_MATCH` |
| Background crawl 429 | toast over the current screen (N7) | nothing on screen; `report.txt` records it |
| Process death in the viewer | restored by 3-9 rules | unchanged; `hasScrolledToTarget` and the page key survive rotation too |
| Race: a background crawl relists the folder on screen | 2-14 refresh in place | unchanged; errors from a background refresh never replace a listing that is shown (the navigator's `background` flag keeps the listing) |
| Share a password-gallery photo | raw CDN original link: opens for anyone (R-53) | the web page link (asks for the password on the web) or the picture without camera data |
| Cast: Stop during the 1 s connect | reconnects; port 8080 server starts (R-57) | stays disconnected; no server |
| Port 8080 taken | dead URL shown (R-58) | next free port shown, or `CAST_COMPANION_FAILED` |
| Keystore broken (R-25, Q5 (a)) | passwords written in plain text | password works this session; `PASSWORD_NOT_KEPT` once; nothing plain is written |
| Download on API 28, permission denied | silent failure / raw toast | `DOWNLOAD_PERMISSION` |

Intermediate states: every spinner has a status line or a heading; a screen never shows "0" counts
while loading; a partial list always says it is partial.

## 5. User-visible changes and every new string

Every string names the cause and the way out (owner feedback on 4-8). All live in `UserMessages`
(or `OfflineMessages`/`AlbumLockedException`, unchanged), each with a unit test. **Q1 asks the owner
to approve or edit this table.**

| Key | Text | Where |
|---|---|---|
| `OFFLINE` (heading) | "You're offline" | any subject |
| `OFFLINE_BODY` | "This {gallery\|folder\|photo} hasn't been opened on this phone yet, so there's nothing saved to show. Connect to the internet and tap Try again." | grid, folder, viewer |
| `OFFLINE_BODY(site)` | "{site} hasn't been opened on this phone recently, so there's nothing saved to show. Connect to the internet and tap Try again." | Folders, Home at launch |
| `SLOW` | "No answer from SmugMug" / "SmugMug didn't answer in time. The connection may be weak. Tap Try again." | any |
| `RATE_LIMITED` | "SmugMug asked the app to slow down" / "Too many requests in a short time. Wait a minute, then tap Try again." | any |
| `SMUGMUG_TROUBLE` | "SmugMug is having trouble" / "SmugMug answered with error {code}. This is on SmugMug's side. Wait a few minutes, then tap Try again." | any |
| `GONE` | "Not found on SmugMug" / "This {gallery\|folder\|photo} may have been deleted, moved, or made private. Go back and refresh the folder." | grid, folder, viewer |
| `LOCKED` | "Password needed" / "This {gallery\|folder} needs its password." | grid, viewer (behind the prompt) |
| `UNEXPECTED` | "Couldn't load this {subject}" / "SmugMug answered with error {code}. Tap Try again. If it keeps happening, the diagnostics report has the details." | any |
| `PARTIAL` | "Showing {shown} of {expected} photos. {short cause}." | grid banner |
| short causes | "You went offline." / "SmugMug didn't answer." / "SmugMug asked the app to slow down." / "SmugMug is having trouble." / "Error {code}." | banners, Home, Search, Tags |
| `EMPTY_GALLERY` | "This gallery has no photos yet." | grid |
| `FILTER_EMPTY` | "No photos match the filters you chose." | grid |
| `HOME_OFFLINE` | "You're offline. Galleries show here when you're back online." | Home |
| `HOME_FAILED` | "Couldn't load the galleries. {short cause}" | Home |
| `HOME_EMPTY` | "This site has no public galleries." (replaces "No public galleries found.", now only when true) | Home |
| `SEARCH_NO_MATCH` | "Nothing on {site} matches “{query}”." | Search |
| `SEARCH_PHOTOS_FAILED` | "Couldn't search photos. {short cause} Galleries and folders below come from this phone." | Search |
| `TAGS_FAILED` | "Couldn't load the tags. {short cause}" | Tags (replaces "Failed to fetch top keywords: …", "Scan failed: …") |
| `PASSWORD_CHECK_FAILED` | "Couldn't check the password. {short cause}" (replaces "Validation failed: {exception}") | prompt |
| `PASSWORD_NOT_KEPT` | "This phone's secure storage isn't working, so this password will be asked for again next time SmugView starts." | prompt, once (Q5 (a)) |
| `REMOVE_FROM_COLLECTIONS` | button "Remove from collections" | `GONE` with a bookmark (Q4) |
| Buttons | "Try again", "Go back", "Enter password", "Show all", "Load the rest" | — |
| `SHARE_CAPTION` | "Opens the SmugMug page. Password galleries ask for their password there." | share dialog |
| `SHARE_LINK` / `SHARE_PICTURE` | buttons "Share link" / "Share picture" (replace "Share via…") | share dialog |
| `SHARE_NO_LINK` | "No web link for this photo yet. Open its gallery once while online." | share dialog |
| `SHARE_PREPARING` | "Preparing the picture…" (replaces "Preparing image for sharing...") | toast |
| `SHARE_STRIPPED` | "Camera details and location are removed from shared pictures." | share dialog, small print |
| `SHARE_FAILED` | "Couldn't prepare the picture. {short cause}" | toast |
| `DOWNLOAD_SAVED` | "Saved to Pictures/SmugView" (replaces "Saved to Downloads: {file}") | toast |
| `DOWNLOAD_FAILED` | "Couldn't save the photo. {short cause}" (replaces "Failed to download image", "Error: …", "Download failed: …") | toast |
| `DOWNLOAD_OFFLINE` | "You're offline, and this photo isn't saved on this phone." | toast |
| `DOWNLOAD_PERMISSION` | "To save photos on this Android version, SmugView needs permission to use storage. You can allow it in Settings." | API 26-28 |
| `DOWNLOAD_NO_SPACE` | "Not enough space on this phone to save this photo." | toast |
| `SAVE_DIALOG` buttons | "Save", "Cancel" | Save dialog (Q9) |
| `REMOVE_CONFIRM` | "Remove “{title}” from {collection}? The copy saved on this phone ({size}) will be deleted. It stays on SmugMug." + "Remove"/"Cancel" | Q10 |
| `CAST_DIAL_DEVICE` | "TV or streaming device" (replaces "Amazon Fire TV" for unknown DIAL devices) | cast list |
| `CAST_COMPANION` | "On the Echo Show, open the Silk browser and go to {url}" (the URL is the bound one) | Echo dialog, cast screen |
| `CAST_COMPANION_FAILED` | "Couldn't start the screen link on this phone: ports 8080 to 8089 are in use. Close other apps that share your screen, then try again." | same |
| `CAST_CONNECT_FAILED` | "Couldn't connect to {name}. Check that it's on and on the same Wi-Fi, then try again." | cast |

**Removed:** "Access Error / Failed to Load", "No Photos Found", "No photos match your current search
or type filter.", "Reset Type Filter", "Enter a word or phrase to search" (as a zero-results text; it
stays as the idle hint "Enter search terms to find photos"), "Loading photo details, please wait...",
"Fetching high-resolution image details...", every `localizedMessage` text, and the global API toasts
("Rate Limit Exceeded: …", "SmugMug Internal Server Error: …", etc., Q2). **Kept unchanged:** the 4-8
lock texts, `OfflineMessages`, "Incorrect password", "Saved password is no longer valid. Please
re-enter.", "Saved to {collection}!", "Added to Favorites!", "Link copied".
**Phase 5 words flagged in 5-9 and not yet reviewed** (listed for the same review): the gallery
failure lines of `OfflineMessages.galleryFailed`, "Keep offline", "1 photo", the "Saved Photos"
heading, and the Delete/Cancel buttons of the collection delete dialog.

## 6. Test harness (step 6-0)

- **Compose under Robolectric (E4).** `testImplementation(platform(compose-bom 2024.04.00))` and
  `testImplementation("androidx.compose.ui:ui-test-junit4")` (test-only; `ui-test-manifest` is already
  `debugImplementation`). `ScreenRig` = `ScenarioRig` + `createComposeRule()`, rendering a screen with
  the rig's VM passed explicitly (every screen takes `viewModel` as a parameter, so `hiltViewModel()`
  is never called), plus a tiny `NavHost` for grid ↔ viewer. Coil gets a test `ImageLoader` whose
  factory answers every image with a 1×1 PNG and records the URLs (the avatar test reads it).
  `@Config(sdk=[33])`, as the migration tests. A sanity test renders the Folders tab of fixture F
  and finds "Family". If Robolectric cannot run Compose with this BOM, the fallback is pure state
  functions (`GridView.of`, …) tested in the JVM and the screen checks moved to emulator
  `androidTest` (record it; Q11 then decides).
- **Real failure shapes in the fake.** `FakeSmugMugServer.lockedChildrenCode` (default 401 for the
  Phase 3 tests); 6-0 runs the full suite once with it at **404** (the real answer, L2) and records
  which tests change. If none, 6-1 flips the default (T8). New fixture: the public folder `6KHfpq`
  "Memories" with a Password child `sNWzhX` and a public sibling after it (L2 topology); a recent-images
  answer with one `/Family/Events/…` thumbnail and no album link (L4 shape); `WebUri` on
  `album!images` items when `_filter` asks for it (L6 shape).
- **`FakeCastIo`** for `CastManager`: SSDP scan, Roku/DIAL probes and the server bind as seams, under
  the existing test dispatcher constructor.
- **Uncaught exceptions** are captured with `Thread.setDefaultUncaughtExceptionHandler` around a test
  (restored after), so N1 can be red.
- **JPEG fixture**: a small JPEG with an APP1 carrying `Orientation=6`, `SerialNumber`,
  `LensSerialNumber`, `Artist`, GPS, and an XMP APP1 (built in the test with `ExifInterface`).
- **Evidence tasks** (recorded in the progress log): (E1) on emulator-5554 (debug build, its existing
  data, read-only `run-as … sqlite3`), take one AlbumKey of a gallery under Family from
  `cached_albums`, then `curl` **anonymously** `album/{key}` and `album/{key}!images` (no password, no
  cookie): record 404 vs 200/`ResponseLevel`. That decides whether a 404 can mean "locked" for a
  gallery (it changes nothing in Q4 (a), which never deletes). (E4) above. Find the largest public
  gallery (more than 500 photos, P7 max 759) for the R-48 viewer check.

## 7. Steps (Sonnet, one commit each; full suite + `BUILD SUCCESSFUL` after each)

Red evidence goes in the progress log: run the new test against a stub of the new API that keeps
today's behaviour, or against `HEAD` in a `git worktree` with only the test and harness copied in.
Never `git stash`. Red tests are never committed or `@Ignore`d *(owner, Phase 1)*. Every step is
`git revert`-able (no migration). Batches of three for Sonnet are in the last column.

| Step | Files | Work | Failing-first test (real-shaped data; how it is red) | Risk | Batch |
|---|---|---|---|---|---|
| **6-0** | test only: `ScreenRig`, test `ImageLoader`, `FakeSmugMugServer` (+`lockedChildrenCode`, Memories/`sNWzhX`, recent `/Family/` image, `WebUri`), `FakeCastIo`, JPEG fixture builder; `build.gradle.kts` (test deps) | §6 harness; E1, E4 | Sanity (green on old code): Folders tab of F shows "Family"; the fake answers `node/sNWzhX!children` 404 with `lockedChildrenCode=404`. The 404-default experiment and E1 recorded | Low; Robolectric Compose may need `@GraphicsMode` or a newer Robolectric: record, do not upgrade without asking | 1 |
| **6-1** | `Repo.syncFolderTree`; fake default per 6-0 | §3.6 (N3) | `FolderTreeSyncTest.lockedFolderAnswering404_isSkipped_andTheWalkCompletes`: root → Memories → {`sNWzhX` Password 404, public sibling}. Red on old: `complete expected:<true> but was:<false>`, sibling never listed. Plus 503 on one folder → sibling listed, `complete=false`, flag unset; offline → abort (pin, green on both) | Low | 1 |
| **6-2** | `ResponseModels.UserData` (+`bioImageThumbnailUrl`), `Repo.getUserProfile`, new `ui/component/ProfileAvatar.kt` (moved), callers' imports | §3.7 (N4) | `ProfileAvatarTest` (ScreenRig + recording `ImageLoader`): rendering the Folders header for idzifamily requests no URL on `secure.smugmug.com` or `*.smugmug.com/bioimage`, and requests the BioImage thumbnail. Red on old: first request is `https://secure.smugmug.com/users/idzifamily-avatar.jpg` | Low | 1 |
| **6-3** | new `ui/text/UserMessages.kt` (`Problem`, `Subject`, texts), `SmugMugErrorMapper.userMessage` delegates; `SmugMugErrorMapperTest` text updates; `NoRawExceptionTextGuardTest` (allow-list of today's 10 sites, shrinking to 0 by 6-7). **Needs Q1** | §3.1, §5 | `UserMessagesTest` (table-driven): synthetic 504, `UnknownHostException`, `SocketTimeoutException`, 429, 500, 503, 404 public vs 404 under a locked root, `AlbumLockedException` ×3, unknown 418, `IllegalStateException`: each gives the kind, a heading, a body naming the cause and an action; no text contains "Exception", "HTTP", or a raw message. Red against a stub that returns `localizedMessage` (e.g. `SocketTimeoutException expected Slow but was "timeout"`) | None (no screen yet) | 2 |
| **6-4** | `AlbumLoader`/`AlbumState` (+`problem`, `expected`), VM `loadAlbumPages`/`failFirstPage`, new `GridView`, `Grid` state branch and banner | §3.2 (R-46, R-47, red heading) | `GridViewTest` (pure) + `GridStatesScreenTest` (ScreenRig): offline uncached FfHCms → "You're offline", no "Access Error"; really empty gallery → `EMPTY_GALLERY` (red: "No photos match your current search or type filter."); videos filter on a photo-only gallery → `FILTER_EMPTY`; 503 on page 2 of a 2-page gallery → photos + `PARTIAL` "Showing 100 of 150" with "Load the rest" that completes it (red: no banner). The 5-9 offline notice tests stay green unchanged | Medium: the grid's `when` is shared by staggered and fixed layouts | 2 |
| **6-5** | VM `loadUserProfile`/`selectSite`/`retryActiveSite`, `siteProblem`, `SplashUiState.Error(problem)`, navigator `Error(problem)`, `FoldersTabView` Retry, `SiteHubController` `albumsProblem`, `HomeTabView` | §3.3 (N2, R-47 Home) | `LaunchStatesScenarioTest`: `user/idzifamily` offline with nothing cached → `browserState` is `Error` with `OFFLINE_BODY(site)` within 2 s and Retry (network back) reaches `Success(root)` (red: stays `Loading`, `timed out waiting for Error`); 503 on the profile → `SMUGMUG_TROUBLE`; Home offline → `HOME_OFFLINE` (red: "No public galleries found."); `ProcessDeathScenarioTest` stays green | Medium: the launch path; 3-9 restore must keep working | 2 |
| **6-6** | `SearchController`, `SearchUiState` (`photosProblem`), `SearchTabView`, `TagSearchController`, `SiteSession` (handler), `AppModule` (toast removed). **Needs Q2** | §3.4 (N1, N7, R-47 Search) | `SearchFailureScenarioTest`: `image!search` answers 503 ×6 (and separately offline) → no uncaught exception (captured handler empty; **red: one `HttpException`/`IOException` captured**, i.e. N1 confirmed), `photosProblem` set, galleries from the index still shown; zero results → `SEARCH_NO_MATCH` (red: "Enter a word or phrase to search"); `AppModuleToastTest`: a final 429 on `user!albums` posts no toast (red: one toast) | Medium | 3 |
| **6-7** | `Viewer` (states, `PasswordPromptHost`), `BrowserScreen`/`Grid` use the host, `Grid` scroll rule, `Viewer` target rule | §3.5 (R-45, R-48) | `ViewerStatesScreenTest`: viewer of an unsaved photo in an uncached gallery offline → `OFFLINE` + Go back (red: spinner only after 5 s); viewer of a locked gallery with no saved password → dialog present in the viewer (red: absent). `ScrollRetentionScreenTest`: grid scrolled to item 40 → open photo → Back → first visible ≥ 40 (red: 0); viewer on #5 → swipe to #6 → page 2 streams in → still #6 (red: #5) | Medium: Compose navigation in Robolectric | 3 |
| **6-8** | `Cast` (discovery stop rule, socket `finally`, `connectGeneration`), selector sheet `DisposableEffect`, `Grid`/`Viewer` pick handlers | §3.12 (R-56, R-57) | `CastManagerTest` +4 (virtual time, `FakeCastIo`): pick a device → no SSDP scan after `advanceTimeBy(30 s)` (red: 3 scans); 60 s cap; Roku connect, `disconnect()` at 500 ms → after 2 s `activeDevice == null`, server never bound (red: `CONNECTED`, bound); Google: session started after Stop → `endCurrentSession` called (seam) | Low-medium | 3 |
| **6-9** | `WebCompanionServer` (`Bound`/`Failed`, ports, bounded pool, stop off Main), `Cast` (`webCompanionUrl`, slideshow dispatcher, `routeIdFor`, labels), `CastControllerScreen`, `Grid` Echo dialog | §3.12 (R-58, R-59) | `WebCompanionServerTest` (real loopback sockets): 8080 taken → `Bound(8081)` (red: silent failure, `isRunning` false but URL shown); 20 slow clients → none queued past 16, no thread growth; `CastManagerTest`: `stopSlideshow` racing the loop 1,000 times → no captured exception (red: `IndexOutOfBounds`/`ArithmeticException` at least once; if it does not reproduce in 1,000 runs, record "not reproduced" and keep the test as a pin); `routeIdFor` and the DIAL label (pure) | Low | 4 |
| **6-10** | `PasswordStore` (+`revision`), `EncryptedPasswordStore` (prefs factory seam, R-25 per Q5), `FakePasswordStore`, `DiagnosticsInitializer`. **Needs Q5** (one-way for the fallback file) | §3.13 (N9, R-25) | `DiagnosticsInitializerTest` +1: save A="oldsecret1", then A="newsecret2", log "x newsecret2" → redacted (red: printed). `PasswordStoreTest` (Robolectric, factory throws): a save writes nothing to any `SharedPreferences` file and `getPassword` works this session (red: fallback file holds it); fallback entries + working factory → moved, fallback file empty | **One-way** (Q5): deletes the plaintext file after moving its entries | 4 |
| **6-11** | VM `handleAlbumLoadError`, `onLoadFailure` (431), `GridView` `Gone` action. **Needs Q4** | §3.8 (N5) | `GoneBookmarkScenarioTest`: FfHCms bookmarked and kept offline, `album/FfHCms` and `!images` answer 404 → bookmark row, `offline_galleries` row and its files remain; `GONE` shows "Remove from collections", which removes them (red: rows gone right after the load). Same for a folder bookmark with no cached row | Low | 4 |
| **6-12** | VM `selectAlbum`, `loadAlbumPages`, `submitPassword`, LRU-restore lit check. **Needs Q3** | §3.8 (N6) | `ViewedMarkScenarioTest` (fixture F, ILU now−2 d): open the locked School gallery, dismiss the prompt → no viewed row, dot still lit (red: row written); open offline, uncached → no row (red: written); open online → one row equal to `album/{key}`'s ILU even when the index ILU is older; LRU restore after the ILU rose → a network request and the new photo | Medium: the dot (Phase 2) — `CollectionDaoTest` dot tests stay green unchanged | 5 |
| **6-13** | `SiteHubController` (filter), uses `getNearestCachedAncestorNode` + `UnlockManager.lockOf`. **Needs Q6** | §3.9 (N8) | `HomeRecentPrivacyTest`: recent images = [public `XVRvVTM`, `/Family/Events/…` `ttDKqjt`], Family cached as Password, no session → Home shows 1 (red: 2); with a Session → 2 | Low | 5 |
| **6-14** | new `share/ShareContent.kt` (link rule, JPEG strip), `PhotoActionCapsule`, `QrShareDialog` (off Main, no blank spinner), `CollectionsTabView` share, `Grid`/`FoldersTabView` share, `Api.getAlbumImages` `_filter` +`WebUri`, `ApiFieldsTest` fixture, `file_paths.xml` unchanged. **Needs Q7** | §3.10 (R-53, QR) | `ShareContentTest`: gallery photo → its `WebUri`; search photo → `{gallery WebUri}/i-{key}` from the index; no link → null; never a `photos.smugmug.com` URL (red: old rule returns the `ArchivedUri`). `JpegStripTest`: fixture → no APP1 Exif tags but `Orientation=6`, no XMP, pixels' bytes (SOS onward) identical (red: old share passes the bytes through). `QrShareScreenTest`: blank URL → `SHARE_NO_LINK`, no progress indicator | Medium: JPEG segment edge cases (APP1 > 64 KB, multiple APP1) | 5 |
| **6-15** | new `download/PhotoDownloader.kt` (MediaStore / legacy path, temp, pending row), `Viewer`/`CollectionsTabView` callers, permission launcher (26-28). **Needs Q8** | §3.11 (R-55) | `PhotoDownloaderTest` (Robolectric `@Config(sdk=[33])` and `[28]`, `FakeCdn`): 33 → one `MediaStore` row in `Pictures/SmugView`, `IS_PENDING=0`, MIME from Content-Type (a PNG source → `.png`) (red: a `.jpg` file under Downloads); a body cut mid-way → no row, no file (red: truncated file); same name twice → two rows; 28 without permission → `DOWNLOAD_PERMISSION`; saved file + offline → saved bytes, no CDN request | Medium: MediaStore under Robolectric (shadow support varies: if the 29+ path cannot run, test the decision logic purely and leave the write to the emulator check) | 6 |
| **6-16** | `AddToCollectionsDialog` (explicit Save), `Search`/`KeywordPhotoDetailScreen` (album key resolution), `CollectionsTabView` (blank key route, confirms), `Routes.photoDetail` guard. **Needs Q9, Q10** | §3.13 (R-49 Save, R-44, confirm) | `SaveDialogScreenTest`: open + Cancel → 0 new bookmarks (red: 1 per last-used collection); `SearchSaveAlbumKeyTest`: saving a search photo of FfHCms stores `albumKey=FfHCms` (red: `""`); `RoutesAndKeysTest` +1: blank album key → no `photo_detail//` route; `RemoveConfirmScreenTest`: unbookmark an image whose only saved copy is this one → confirm shown (red: removed at once) | Low | 6 |
| **6-17** | VM detail caches (R-61), Collections/Grid touch targets, header parallax read, theme check | §3.13 polish | `ImageDetailsCacheTest`: EXIF fails then succeeds → second open shows it (red: failure kept); 201st key evicts the first. `TouchTargetScreenTest`: every Collections row action ≥ 48 dp (red: 24 dp) | Low | 6 |
| **6-18** | journey tests (§8.1). **Needs Q11** | Exit criterion | J1…J7 as specified in §8.1 (they are acceptance tests over fixed code; each must have failed on `c3e1eff` for at least one assertion, recorded) | Medium: test runtime (keep the suite under +2 min) | 7 |
| **6-19** | `MigrationTest` 12 → 17 (only if Q13 (b)) | 12→13 coverage | reconstructed `12.json` from `cda2e7e` in a worktree; a v12 DB with real-shaped rows migrates and opens | Low | 7 |
| **6-20** | docs: `findings.md` rows (§11), `SMUGMUG.md` (L1-L4, L6, L7), `DESIGN.md` states section, `PRIVACY_POLICY.md` (sharing strips camera data; downloads go to Pictures), `CLAUDE.md` +2 facts (**needs Q12**) | Docs | none | None | 7 |
| **6-21** | — | Emulator exit check §8.2 | — | — | 8 |
| **6-22** | — | **Final phone phase** §8.3 (all phases) | — | — | 8 |

**Order.** 6-0 → 6-1 → 6-2 can start now. 6-3 before every screen step (6-4…6-7). 6-6 after 6-4
and 6-5 (the toast goes only once every screen has its own state). 6-8 → 6-9. 6-10…6-17 are
independent of each other after 6-3. 6-18 after everything it exercises. Expected suite: 632 (+5-11)
→ about **+95**: 6-0 +2, 6-1 +3, 6-2 +2, 6-3 +16, 6-4 +8, 6-5 +5, 6-6 +5, 6-7 +5, 6-8 +5, 6-9 +6,
6-10 +4, 6-11 +3, 6-12 +5, 6-13 +2, 6-14 +10, 6-15 +6, 6-16 +5, 6-17 +4, 6-18 +7, 6-19 +1.

## 8. Exit check

### 8.1 The 7 journeys (exit criterion; defined here because the UI report is not in the repo)

Each runs on fixture F plus the 6-0 additions (password folder `2sDN5x` → sub-folder `P4BKB` →
gallery `LCdk7F`/`FfHCms`, AlbumKey ≠ NodeID, dates relative to now), as Robolectric Compose tests
in the unit suite (Q11 (a)); J1, J3 and J6 also run as `androidTest` smoke on emulator-5554 against
the live API, read-only, with no password.

| J | Journey | Asserts |
|---|---|---|
| J1 | Launch with a saved site → Folders → Family (prompt, password) → School → gallery → photo → Back ×3 | dot lit then cleared only after page 1 (Q3); grid position kept on Back; viewer stays on the swiped photo |
| J2 | Offline launch, profile cached → open a gallery never opened → reconnect → Try again | `OFFLINE` heading (no red), then photos; no toast |
| J3 | Search "kentridge" → photo → Jump to gallery; search "zzqx" | results; `SEARCH_NO_MATCH`; search offline: no crash, `SEARCH_PHOTOS_FAILED` |
| J4 | Tags: pick a keyword → keyword viewer → Back | tags kept selected; viewer → its gallery |
| J5 | Save a photo (dialog: open + Cancel, then Save) → Collections row Saved → offline open → delete the collection | Cancel wrote nothing; confirm shown; file gone |
| J6 | Share a gallery, a public photo, a photo of a password gallery | text is a web page URL; no `photos.smugmug.com`; picture has no camera tags |
| J7 | Cast: pick a fake Roku → Stop within 1 s; pick again → slideshow → Stop | stays disconnected; discovery stopped after the pick; no captured exception |

**Instrumented runs use only the emulator:** `ANDROID_SERIAL=emulator-5554` on every
`connectedDebugAndroidTest`, after `adb devices` shows no other device; if the phone
(`67200DLKY00226`) is listed, stop and ask the owner. Never `connectedAndroidTest` without the serial.

### 8.2 Emulator (emulator-5554, AVD API 34; debug build of the 6-20 commit; nobody types or prints a password)

Evidence: screenshots, `report.txt`, `diag-0.log`, read-only `run-as … sqlite3`.
1. Airplane mode: a never-opened gallery shows `OFFLINE` with a neutral heading and Try again;
   back online, Try again loads it.
2. Launch offline with the HTTP cache cleared (`run-as … rm -r cache/http_cache`): Folders and Home
   show `OFFLINE_BODY(site)`; network on, Try again opens the root.
3. `report.txt`: no `secure.smugmug.com` and no `{nick}.smugmug.com/bioimage` lines;
   `FolderTreeSync complete=true skippedLocked≥1` (`sNWzhX`); `uncaught_site_errors` = 0.
4. Search "zzqx" → `SEARCH_NO_MATCH`; airplane mode + search → no crash, the photos line, galleries
   from the index.
5. Grid of the largest public gallery (E4 task, > 500 photos): scroll, open a photo, Back → same
   place; in the viewer, swipe while page 2 streams → no jump; rotate → same photo.
6. Share: gallery and photo links are web pages (copy, read the clipboard via the dialog's text);
   "Share picture" → `adb pull` of `cache/shared_images/*` → `exiftool` shows no `SerialNumber`,
   `LensSerialNumber`, `Artist`, GPS; `Orientation` kept.
7. Download a public photo → `adb shell ls /sdcard/Pictures/SmugView` shows it with its real name and
   extension; download twice → two files.
8. Save dialog: open and Cancel → bookmark count unchanged (`sqlite3`).
9. "Elliot Grad Photos" (`zzwtrF`, own password) from Search into the viewer → the prompt shows in
   the viewer; dismiss → back, no viewed row.
10. Light theme (`adb shell cmd uimode night no`): status-bar icons readable on every screen.
11. Cast without devices: the selector shows none; `diag-0.log` shows SSDP stops 60 s after opening
    the selector and at once on dismiss. Port 8080 occupied (`adb shell toybox nc -l -p 8080 &`):
    nothing to cast to on the emulator, so the bind fallback is unit-only here.
12. J1, J3, J6 `androidTest` smoke green with `ANDROID_SERIAL=emulator-5554`.

Not on the emulator: real cast devices (E3), API 26-28 download (only the API 34 AVD exists; see Q8),
GPS in phone photos (E2).

### 8.3 Final phone phase (6-22): every phone-only check from Phases 1-6, in one sitting

Owner decisions that bind this step: a **locally signed test build installed directly**, never a
Play track *(owner, 2026-10-01)*; the uninstall of the Play build **wipes saved passwords, the cache
and saved files** *(accepted)*. Each item below needs the owner's go in the moment (install,
uninstall). Read-only `adb` only, against serial `67200DLKY00226`, and only in this step.

| # | Check | From |
|---|---|---|
| P1 | **Before anything:** pull `report.txt` from the installed v0.8.0 (Play) build: doctor counts, last sync runs, HTTP stats, and (Phase 5 §7.3) the counts of `collection_photos`, bookmarks by type, broken legacy rows if readable; record them | Phase 5 §7.3, Phase 1b-6 |
| P2 | Install path (Q16): uninstall + local install (wipes), or same-key over-install (keeps data; shows the 5-8 legacy repair on real data) | Phase 4, Phase 5 §7.3 |
| P3 | Release build smoke (R8): galleries load, a search image opens, `report.txt` is written; release logcat has SmugView lines | Phase 1a-3/1a-5/1a-7, findings #7, AGENTS R8 rule |
| P4 | `report.txt` after the first sync: no `Password=` in http lines; doctor `self_parent`, `bang_parent`, `orphan_rows`, `dup_index_nodeid`, `index_nodeid_is_albumkey` all 0; `cross_site_rows=0`; no `secure.smugmug.com`/`bioimage` 404; `FolderTreeSync complete=true` | Phase 2 exit, Phase 4, Phase 6 (6-1, 6-2) |
| P5 | The owner types the Family password once: Home → Family → School lit; opening a School gallery clears only its own chain; a dismissed prompt clears nothing (Q3) | Phase 2 exit, Phase 6 |
| P6 | Zoom on a real photo: X3 before the original; deep zoom reaches the original | Phase 4 (4-9) |
| P7 | Airplane mode: an opened gallery shows; a never-opened one shows `OFFLINE` (neutral heading); a saved photo opens full size with no CDN request; a kept gallery opens with its saved photos | Phases 4, 5 (§7.3), 6 |
| P8 | Offline files: `offline_by_state` has no PENDING/OFFLINE while online; `offline/` size equals `offline_done_bytes`; adopted vs re-downloaded counts (only if P2 kept data) | Phase 5 §7.3 |
| P9 | Cast to the owner's real devices (Chromecast / Roku / Echo Show, whichever exist): connect, slideshow, Stop during connect stays stopped, Echo link URL works | Phase 6 (E3, R-56…R-59) |
| P10 | Share a photo to a real app (e.g. to the owner's own email): the link opens the SmugMug page (a Family photo asks for the password in a private browser window); the shared picture has no camera serials or GPS (`exiftool` on the received copy, on the PC) | Phase 6 (R-53, E2) |
| P11 | Download a photo: it appears in Google Photos under Pictures/SmugView | Phase 6 (R-55) |
| P12 | Light and dark system theme: status bar readable | Phase 6 (R-49) |

## 9. Needs owner sign-off (recommended answer first)

| Q | Choice | Recommended | Consequence of each option |
|---|---|---|---|
| Q1 | The words (§5), including the Phase 5 words flagged in 5-9 | **(a) Approve §5 as written** (edit any line you like; edits are free until 6-3 lands) | (a) every state names its cause and way out. (b) keep today's texts where §5 replaces them: the red heading and raw exception text stay |
| Q2 | The global error toast for API failures | **(a) Remove it.** Every screen shows its own state (6-3…6-7); background work (crawl, tree walk, search pages) never interrupts; failures stay in `report.txt` | (a) no "Rate Limit Exceeded" over unrelated screens (N7). (b) keep it only for requests a screen is waiting on, with §5 texts: two places say the same thing. (c) today |
| Q3 | What "viewed" means (dot) | **(a) A gallery is viewed when its photos were shown from SmugMug** (page 1 on screen, or a confirmed empty gallery), marked once with the ILU of that open. Not on a dismissed prompt, a failure, the offline saved-photos view, or a restore from memory | (a) a dot clears only when you saw the gallery; Phase 2's single owner of "new" keeps one writer of "viewed". (b) today: marked on tap (and twice), so a locked or failed open clears the dot unseen (N6) |
| Q4 | A bookmarked gallery or folder that answers 404 | **(a) Never remove it automatically.** The screen says `GONE` and offers "Remove from collections" (with the Phase 5 confirmation when saved photos would go) | (a) nothing the user saved disappears because of a 404, which is also how a locked folder answers (L2, E1). (b) remove automatically, but only after `album/{key}` itself is 404 and no password root is locked above it: still one-way, and deletes kept-offline photos. (c) today: removed on any 404, with its saved photos (N5) |
| Q5 | R-25: saved passwords when the phone's secure storage fails (Phase 3 Q10) | **(a) Never write a password in plain text.** If the Keystore can't be used, a typed password works until the app closes and the prompt says so once (`PASSWORD_NOT_KEPT`). Passwords already in the old plain file are moved into the secure store the next time it opens, then the plain file is deleted | (a) closes R-25; a broken Keystore means retyping passwords after each restart (rare). One-way: the plain file is deleted after the move. (b) keep the plain fallback, but move entries back when the Keystore works: plain text remains on broken phones. (c) today |
| Q6 | Home's recent photos from password folders (L4, N8) | **(a) Hide a recent photo whose gallery is under a password folder this phone has no session for**; show it once unlocked. Separately, you may want to check the gallery settings on SmugMug: SmugMug itself serves these photos (and their originals) to anyone with the key | (a) the app stops displaying what the folder password is meant to hide. (b) show them (today): anyone opening your site in the app sees them |
| Q7 | What sharing sends (R-53, T10) | **(a) Links are always the SmugMug web page** (gallery, folder or photo page; password galleries ask for the password there). **"Share picture" sends the large rendition (or the saved copy) with camera details and location removed.** Never the original's CDN link | (a) a shared link can't bypass a gallery password; shared pictures don't carry serial numbers or the owner's name (L7). (b) link only, no picture. (c) today: the CDN original link, which opens without the password, and the original with all camera data |
| Q8 | Where "Download photo" saves, and what | **(a) Pictures/SmugView through the system photo store** (visible in Google Photos), the **original** with its camera details (your own copy); from the saved copy when there is one (works offline); Android 8-9 asks for the storage permission. An API 28 emulator image for checking that path is optional (creating it is local and reversible; say yes or no) | (a) works on every supported Android version, never overwrites, never leaves a broken file. (b) Downloads as today, fixed the same way. (c) strip camera details from downloads too |
| Q9 | The "Save to collection" dialog writing on open (R-49) | **(a) Opening writes nothing**; last-used collections start ticked; "Save" writes, "Cancel" doesn't | (a) no accidental bookmarks (and no accidental downloads of saved photos, Phase 5). (b) today: ticked collections are written the moment the dialog opens |
| Q10 | Confirm on delete (R-49) | **(a) Ask only when a copy saved on this phone would be deleted** (unbookmarking an image, removing a saved photo, turning off Keep offline), as Phase 5 already does for collections; other removals stay one tap | (a) consistent with Phase 5 Q8. (b) ask for every removal: more taps. (c) today: only collection delete asks |
| Q11 | How the 7 journeys run (the review asked for instrumented tests) | **(a) All 7 as Robolectric Compose tests in the unit suite (fake server, deterministic), plus J1/J3/J6 as instrumented smoke on emulator-5554 against the live API, read-only, no password** | (a) runs on every build; the emulator smoke checks the real stack. (b) all 7 instrumented on the emulator: needs Hilt test wiring and a fake server on the device, slower, flakier. (c) Robolectric only |
| Q12 | `CLAUDE.md` additions in 6-20 | **Yes**: "A locked folder's `!children` answers **404** anonymously (not 401); a missing gallery is also 404" and "SmugMug renditions (X3, L) keep the camera EXIF; strip before sharing" | `CLAUDE.md` is the rules file. No: the facts go to `SMUGMUG.md` only |
| Q13 | The 12 → 13 migration test (schema 12 never exported; v12 shipped only on the internal track on 2026-07-15 and was replaced two days later) | **(a) Skip; record "left as is"** | (a) no work for a path no installed copy can take any more (the phone's DB is ≥ 16). (b) rebuild `12.json` from `cda2e7e` in a worktree and add a 12 → 17 test (step 6-19) |
| Q14 | Delete the dead `SiteExplorerScreen.kt` after 6-2 moves `ProfileAvatar` out (R-49 dead UI) | **Yes**, asked again in the moment (AGENTS: deleting a file the agent did not create) | Yes: ~300 dead lines go. No: it stays, unreferenced |
| Q15 | A global settings switch for the offline network rule (deferred by Phase 5) | **(a) No global switch**; the per-gallery "Use mobile data too" (your Phase 5 Q3 override) stays the only control | (a) no settings screen to build. (b) a Settings screen with one switch: a new screen for one option |
| Q16 | Phone install path in 6-22 | **(a) Uninstall the Play build and install the local test build** (already accepted: wipes passwords, cache and saved files); P1 records the baseline first | (a) the decided path. (b) same-key over-install (needs the Play signing key locally): keeps data and shows the 5-8 legacy repair on real rows |

**Decided here** (no sign-off: same meaning as today, or a fix to match what the code already claims):
- One `Problem`/`UserMessages` owner of failure words; the red heading goes (its words are Q1).
- The tree walk skips locked (404) folders and continues (N3); 429/offline still abort.
- Avatar: BioImage thumbnail, else the first letter; no requests to URLs that are 404 on 5 of 5 sites.
- `SiteSession` gets a logging `CoroutineExceptionHandler` (a net, not a fix: N1 is fixed in place).
- Launch failure renders a state with Try again (N2).
- QR generated off the main thread; a blank link shows text, never a spinner.
- Casting: discovery stop rule (pick, dismiss, 60 s), connect generation, port fallback 8080…8089,
  bounded pool, slideshow confinement, route-id mapping, honest DIAL label.
- R-61: failures are not cached; per-key maps capped at 200.
- R-44: never navigate with a blank album key.
- The password prompt has one host composable used by three screens.
- The fake answers a locked folder with 404 (the real code) if 6-0 shows the suite tolerates it.

### 9.1 Owner sign-off (2026-10-01)

The owner answered "All as recommended" to Q1-Q16: every recommended option (a) is approved as written
(Q13 = skip the 12 to 13 migration test; Q15 = no global network switch; Q14 = delete `SiteExplorerScreen.kt`
but ask again in the moment, per AGENTS). Steps 6-0 to 6-22 may now proceed in order, in batches of about
three, one commit each. Q3 (what "viewed" means), Q5 (no plaintext passwords) and Q7 (what sharing sends)
change meaning and are recorded here as explicitly approved. Phase 5 follow-ups the owner decided the same
day: Favorites photos stay in the "Saved Photos" section; a never-kept gallery with nothing saved shows the
offline message when opened offline; no manual per-gallery "Try again" (it retries by itself). Added to
Phase 6 scope: after a full disk, downloads resume only on app restart or after the ~1 h retry delay, and a
stale "needs N MB" line stays while failed rows wait (found in 5-11; not fixed).

## 10. Verified vs assumed

| Claim | Status |
|---|---|
| L1-L8 | verified live (anonymous) 2026-10-01 (§1.1) |
| N1 search failure crashes the app | *likely* (code: no catch, no handler, Android's default uncaught handler); 6-6's red test confirms in the JVM; not run on a device |
| N2 endless spinner at an offline launch with no cached profile | *likely* (code); 6-5's red test |
| N3 the walk aborts on a locked folder | *confirmed* (emulator 4-13 report line + L2) |
| N4 the three launch 404s are `ProfileAvatar` step 0 | *likely*: three live call sites match the phone's three `secure.smugmug.com` 404s; confirmed on the emulator in 8.2 #3 |
| N5 a 404 deletes the bookmark and its kept-offline files | *confirmed (code)*; 5-6 deviation says `removeBookmarkGlobally` drops kept-offline rows |
| E1: anonymous `album/{key}` for a gallery under Family | **unverified**: 6-0 on the emulator; Q4 (a) is safe either way |
| E2: GPS in the owner's phone photos | **unverified**; P10 on the phone. The strip removes it either way |
| E4: Compose under Robolectric 4.11.1 + BOM 2024.04.00 | **assumed**; 6-0 spike, with a stated fallback |
| R-44 navigation with an empty segment throws | *likely* (Navigation 2.7 route matching needs a non-empty segment); 6-16's test pins the guard |
| R-59 slideshow race reproduces | *possible*; 6-9 records "not reproduced" if 1,000 races pass |
| androidx `ExifInterface` is on the classpath through Coil | *assumed* from Coil 2.5's dependencies; 6-14 declares it explicitly (same artifact) |
| MediaStore insert works under Robolectric | *assumed*; 6-15 has a stated fallback |
| Suite size 632 | from the 5-10 progress log; **not re-run** by this planner |

## 11. `findings.md` rows

| R / N | Planned | Note |
|---|---|---|
| R-44 empty albumKey, blank-segment route | 6-16 | likely crash |
| R-45 viewer error/password state | 6-7 | |
| R-46 partial gallery not shown as partial | 6-4 | retry no longer blocked since 3-5 |
| R-47 conflated states | 6-4, 6-5, 6-6 | |
| R-48 scroll retention | 6-7 | |
| R-49 polish (raw text, Save on open, confirm, 48 dp, dead UI, scroll offset, status bar) | 6-3…6-7, 6-16, 6-17; Q9, Q10, Q14 | "one viewer" deferred (§13) |
| R-53 sharing (link, EXIF) | 6-14, Q7 | "QR of a local path" stale (§12) |
| R-55 download | 6-15, Q8 | |
| R-25 plaintext fallback | 6-10, Q5 | Phase 3 Q10 |
| R-56, R-57 | 6-8 | |
| R-58, R-59 | 6-9 | |
| R-61 | 6-17 | |
| N1 search failure → uncaught exception | 6-6 | new |
| N2 launch with uncached profile spins forever | 6-5 | new |
| N3 tree walk aborts on a locked folder (404, not 401) | 6-1 | 4-13 (a); fake answered 401 (T8) |
| N4 avatar URLs 404 on every site, 3 per launch | 6-2 | 4-13 (b); phone report |
| N5 404 deletes bookmarks and kept-offline files | 6-11, Q4 | Phase 0 leftover, raised by 5-6 |
| N6 viewed mark in three places, before photos show | 6-12, Q3 | Phase 2 gap |
| N7 global toast for background requests | 6-6, Q2 | |
| N8 SmugMug serves hidden password-folder photos anonymously; Home shows them | 6-13, Q6; owner informed | P16 of phase 4, L4 |
| N9 changed password not redacted until restart | 6-10 | 1a-3 gap |
| Red heading over the offline text | 6-4 | 4-13 (c) |
| SMUGMUG.md facts: avatar URLs 404; locked `!children` and missing albums are 404; renditions keep EXIF; `image/{k}-0` serves hidden-gallery originals | 6-20 | L1-L4, L7 |

## 12. What in the review (and earlier notes) is wrong or stale at `c3e1eff`

- **R-53 "the QR code can encode a local file path"**: stale. Today a saved photo has no
  `archivedUri`, so the QR gets `""` and shows a spinner forever; no path is encoded. The rest of
  R-53 holds: the share text and QR are the CDN original for every gallery photo (L6: the app never
  asks for `WebUri`).
- **R-53 "shares full EXIF (GPS)"**: half right. The original and the X3/L renditions carry camera
  and lens serial numbers and the owner's name (L7); GPS was not present in any sampled photo (0 of
  100 by API, 1 file by `exiftool`); phone photos unchecked (E2).
- **R-46 "retry is blocked"**: stale since 3-5 (an errored album restarts on re-select); what remains
  is that nothing on screen says the gallery is partial.
- **R-49 "SiteExplorerScreen isn't in the NavHost"**: true, but its `ProfileAvatar` is live on four
  screens and is the source of the three launch 404s (N4).
- **4-13 (b) "the `-avatar.jpg` fallback in `SiteExplorerScreen.kt`"**: it is `ProfileAvatar`'s first
  choice, not a fallback, and the `{nick}.smugmug.com/bioimage` step is also always 404 (L1).
- **R-56 "may leak a multicast socket per cycle"**: only on exception paths; the normal path closes it.
- **`syncFolderTree`'s doc comment** ("a 401/403 means the folder is locked"): the real answer is 404
  (L2); the fake's 401 hid it (T8, phase 4 deviation 4-0).
- **R-42, R-43, R-17, R-18, R-20**: fixed in Phases 0 and 3; listed in the review's Phase 6 row only
  through T7.
- **The review's exit criterion cites "the 7 journeys in the UI report"**: that report was never
  committed; §8.1 defines them.
- **`SmugMugErrorMapper.shouldShowToast`'s comment** says a 404 is "the EXPECTED signal for a locked
  folder": true for folders (L2), and also the answer for a deleted gallery (L3), which is why a 404
  alone must never delete anything (Q4).

## 13. Dependencies, and what is deferred

Must not regress (tests stay green **unchanged** unless a step says otherwise): `AlbumLoader` (token,
LRU, partial keep; 6-4 adds fields, 6-12 adds the lit re-check on restore), `BrowserNavigator` (one
job, token-checked publish; 6-5 adds an `Error(problem)` publish), `UnlockManager` (single flight,
background never deletes, `sessionEpoch`), the dot query (Phase 2; 6-12 changes only who calls
`markNodeAsViewed`), `CachePolicy` and the synthetic-504 rule, `Pager`, `OfflineStore`/`OfflineReader`
(5-9's offline notice and saved-photo viewer), the 4-8 lock texts, `ManifestGuardTest`,
`SchemaGuardTest`. Scenario suites to watch: `LockedAlbumTest`, `OfflineReaderTest`,
`ProcessDeathScenarioTest`, `PasswordPromptScenarioTest`, `OpenBWhileAStreamsTest`,
`BrowserNavigationScenarioTest`, `CachePolicyScenarioTest`, `HubTotalsTest`, `CastManagerTest`.

Deferred, with the reason:
- **One photo viewer (merge the three).** The shared parts (action capsule, EXIF sheet, zoom) are
  already one component; 6-7 adds one state host. Merging the three screens is a large refactor with
  no user-visible change: not scheduled. (Tell me if you want it as a Phase 7.)
- **Hub featured list order** (phase 4 §12): not a defect.
- **R-60, R-62** (filtering on Main, hardcoded dispatchers): performance, not in the Phase 6 row.
- **R-65, R-66, R-68** (test hygiene): not in the Phase 6 row; R-66's copied functions are untouched
  by Phase 6.
- **A Settings screen** (Q15 (a)).
- **Anything that changes what SmugMug serves** (N8): outside the app.

## 14. Recorded deviations

*(Filled in during implementation: one line per deviation, with the step, what changed from this
design, and why.)*

| Step | Deviation | Why |
|---|---|---|
| 6-0 | `FakeCastIo` is **not** built in 6-0; it moves to 6-8. | It is a seam *in production code* (`CastManager`'s SSDP scan, Roku/DIAL probes and server bind), not a test double alone, and 6-0 is test-only. 6-8 changes `CastManager` anyway and needs the seam for its red tests. |
| 6-0 | The tiny `NavHost` (grid <-> viewer) of the `ScreenRig` is **not** built in 6-0; it is added by 6-7, its first user. | No 6-0..6-6 test needs navigation; adding it unused would be untested code. |
| 6-0 | `ScreenRig` takes the `ComposeContentTestRule` as a constructor parameter instead of owning `createComposeRule()`, and registers `ComponentActivity` in the Robolectric `PackageManager` shadow. | A JUnit rule must be a `@get:Rule` member of the test class. `@Config(manifest = Config.NONE)` (as every other Robolectric test here) has no manifest, so the activity the rule launches is registered by hand. |
| 6-0 | The recording Coil loader is an `Interceptor` that records `request.data` and answers a 1x1 bitmap, not a `Fetcher` factory that returns a 1x1 PNG. | Same effect for the avatar test (no network, URLs recorded, every request seen before any mapper), fewer moving parts. |
| 6-0 | JPEG fixture: test dependency `androidx.exifinterface:exifinterface:1.3.6` (the version Coil already puts on the runtime classpath) and the fixture's tags are `Orientation=6`, `BodySerialNumber`, `CameraOwnerName`, `Artist`, GPS, plus the hand-spliced XMP APP1; **no `LensSerialNumber`**. | The framework `ExifInterface` cannot write `BodySerialNumber` at all, and the androidx one (1.3.6) silently drops `LensSerialNumber` (read back `null`). `CameraOwnerName` is another identifying Exif IFD tag, so the strip test (6-14) covers the same segment. |
| 6-0 | `lockedChildrenCode` stays **401** in 6-0; 6-1 flips the default to 404 and changes the two literal `assertEquals(401, ...)` it breaks. | The 404 experiment is not "none": two tests change (see the 6-0 row in `findings.md`). Neither depends on app behaviour; both assert the fake's own status. |
| 6-1 | Two extra pins (offline and 429 on the walk abort at once, green before and after) and the 404-experiment result: three tests changed (the cookie-jar case in `LoopbackSmugMugTest`, the restart case in `PasswordGalleryCacheTest`, the fake-default case in `FakeRealismTest`), not two. | The 6-0 row said two; the third was the fake's own default assertion. |
| 6-2 | `ProfileAvatar` takes `bioImageThumbnailUrl: String?` (not a `UserData`) and the pure function is `avatarModel(bioImageThumbnailUrl: String?): String?`, not `avatarModel(user)`. Its callers have only a nickname and name when the profile has not loaded. | Same behaviour, simpler call sites. A BioImage that fails to load also falls back to the letter (never another host). |
| 6-2 | `ScreenRig` puts `Dispatchers.Main` back on the Robolectric main looper after `ScenarioRig` points it at a plain thread, and its `waitUntil` idles that looper and advances the Compose clock each turn (own poll loop, with a message on timeout) instead of `compose.waitUntil`. | Coil's `AsyncImagePainter` reads Compose snapshot state from `Dispatchers.Main` and crashed (`Reading a state that was created after the snapshot was taken`) on the rig's plain "main" thread, so no image request ever reached the recorder. |
| 6-2 | `ProfilePreviewCard`'s album tile with no cover is left empty; it used to ask the dead `secure.smugmug.com/users/{nick}-avatar.jpg`. | Design 3.7 drops the dead URLs; the tile has no letter to show. |
| 6-3 | `Problem.from` takes `underLockedRoot: Boolean` instead of calling `UnlockManager.lockOf` itself; `userMessage` gains a `subject` parameter (default `Gallery`). | `from` stays pure and unit-testable; `lockOf` is a suspend repository call, so the caller (6-4, 6-5) asks it and passes the answer. |
| 6-3 | An exception that is not an HTTP error is named by its class **without the "Exception" suffix** in `UNEXPECTED` ("error IllegalState"), a null error by "unknown". Any other `IOException` is `OfflineNothingSaved` (as `isOffline` already says). 401 outside a locked root is `Unexpected(401)`. | The test rule says no text contains "Exception", yet 3.1 says "names the code or the exception class". A stripped class name satisfies both. |
| 6-3 | `Subject.Tags` has the noun "tag list" ("Couldn't load this tag list"); for the Search and Tags subjects the offline body is the short cause ("You went offline."), because "This search hasn't been opened on this phone yet" is nonsense. `PARTIAL` with no known total reads "Showing {shown} photos. {cause}". | Section 5 has no wording for these; the smallest sentence made of section 5 parts. **Listed for the owner** (Q1). |
| 6-3 | `userMessage` returns `heading. body` as one line (the 4-8 texts alone, since they already say it). | Its callers hold a single string; 6-4 and 6-5 move the screens to `Problem` and use the parts. |
