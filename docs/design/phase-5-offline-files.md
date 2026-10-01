# Design: Phase 5: Offline files (T6)

Status: **SIGNED OFF 2026-10-01 (§8.1; Q3 has an override).** Was: DRAFT, needs owner sign-off (§8). Steps **5-0** (harness and evidence) and **5-1** (a pure
failure classifier) change no behaviour and no stored format, so they can start before sign-off.
Every later step depends on at least one answer in §8: 5-2 needs Q5 and Q6, 5-6 needs Q3 and Q7,
5-7 needs Q1, 5-8 needs Q6, and 5-9 needs Q2 and Q8.
Written 2026-10-01 by the planning agent. It is read-only, against `main` at `97a18f5` (Phase 4
done; 483 unit tests and 4 skipped per the 4-13 progress-log entry, not re-run here).
Scope: review §4 row "5 — Offline files" ([whole-app review](../review/2026-09-29-whole-app-review.md)):
T6 (R-38…R-41), plus where Phase 5 meets R-21, R-30, R-37, R-45 and R-49. One Room migration
(16 → 17). One new test-only dependency (`androidx.work:work-testing:2.9.0`). No new runtime
dependency, permission or notification. Template: [phase-4-api-contract.md](phase-4-api-contract.md).

Exit criterion (review §4): **worker tests for 429, offline, concurrent adds, and a file shared
across collections.** §7 adds cancel, process death and migration tests, and emulator checks.

## 1. What the code does today (`97a18f5`), and what the live API and CDN say

`Worker` = `data/worker/OfflineDownloadWorker.kt`, `CC` = `ui/viewmodel/CollectionsController.kt`,
`Dao` = `data/db/CollectionDao.kt`, `VM` = `ui/viewmodel/SmugViewModel.kt`, `Repo` =
`data/repository/SmugMugRepository.kt`. Every citation names the function; if the lines have moved,
search for it by name.

### 1.1 Premise checks (live, read-only, anonymous, 2026-10-01)

Every call was a `curl` with `APIKey=<KEY>`, where the key was read from `local.properties` inside the
shell and stripped from the output. No password was read or used. The photos are public ones from
`N74KSK` ("KR XC Three Course Challenge") and `FfHCms`, plus two public videos found by `image!search`.

| # | Question | Command shape | Result | Verdict |
|---|---|---|---|---|
| P1 | What the CDN sends for an original (`ArchivedUri`, `/D/…-D.jpg`) | `curl -D - <ArchivedUri of XVRvVTM>` | 200, `image/jpeg`, `Content-Length: 688974` (= the API's `ArchivedSize`), `ETag: "8c4fd434…"` (= the API's `ArchivedMD5`), `Last-Modified`, `Cache-Control: private, max-age=31536000, s-maxage=0`, `Content-Disposition: attachment`. No `Accept-Ranges` header. The body's MD5 matches `ArchivedMD5`. | confirmed |
| P2 | Ranges and conditional requests | `-H "Range: bytes=0-99"`; `-H "If-None-Match: <etag>"` | Range: **206**, `Content-Range: bytes 0-99/688974` (works although not advertised). `If-None-Match`: **200** (not honoured). | confirmed |
| P3 | What the app's X3 rewrite of a thumbnail returns | the `/Th/`→`/X3/` URL, with and without `-Th.`→`-X3.` | Both 200, about 428 KB (vs 689 KB for the original). `public, max-age=31536000, must-revalidate`, **no ETag**. | confirmed |
| P4 | How a missing file fails | the original's URL with one hash character changed; a made-up image key in the path | Changed hash: **200, same bytes** (the hash segment is not checked). Unknown image key: **404**, `text/html`, `no-store`. | confirmed |
| P5 | Size and checksum in the listing | `album/N74KSK!images?_filter=…,ArchivedUri,ArchivedSize,ArchivedMD5` | Present on every item. The app's `getAlbumImages` `_filter` asks for `ArchivedUri` and `OriginalSize`, **not** `ArchivedSize`/`ArchivedMD5`. | confirmed |
| P6 | What "the original" of a video is | `image!search?Scope=/api/v2/user/idzifamily&Type=Video` (Total **317**); `image/74dNvrt-0!sizedetails`; `-r 0-0` on both URLs | A video's `ArchivedUri` is a **JPEG still** (`…-D.jpg`, `image/jpeg`, 164,821 B = `OriginalSize`). The 4-minute video itself is `LargestVideo` = 1920 mp4, **218,077,085 B**. Sizes offered: `VideoSize1920` 218 MB, `VideoSize1280` 140 MB, `VideoSize960` (here the same file as 1280). The mp4 answers Range with 206; its ETag is the MD5; `public, max-age=31536000`. | confirmed |
| P7 | How big a gallery is | sum of `ArchivedSize` over `!images` | `N74KSK`: 61 photos, **103.5 MB** (largest 4.0 MB). `FfHCms`: 122 photos, **95.4 MB**. Public `user!albums` page 1: median **86** photos per gallery, max **759**. | confirmed |
| P8 | Re-resolving one photo's source | `image/XVRvVTM-0?_filter=ImageKey,ArchivedUri,ArchivedSize,ArchivedMD5,Format,IsVideo`; `image/ZZZZZZZ-0` | 200 with all six fields; an unknown key gives **404**. | confirmed |
| P9 | Emulator data | `adb -s emulator-5554 shell run-as … sqlite3 smugview_db` (debug build, read-only) | `user_version` 16; **0** collections, 0 bookmarks, 0 `collection_photos`; no `files/offline_photos/`. The exit checks have to create their own data. | confirmed |
| P10 | Not checkable from here | — | (E1) whether `ArchivedUri`/`ArchivedMD5` are present for a **password-gallery** photo listed with a session; (E2) the CDN's 408/429/5xx shape and any `Retry-After` (they can't be forced); (E3) whether a CDN URL ever expires (P4 suggests the path is not signed); (E4) whether the CDN ever sends 401/403/410; (E5) when WorkManager stops a worker at its 10-minute limit on API 34 (taken from the WorkManager docs). | §2.4: E1 is a 5-0 evidence task on the emulator; E2–E5 are designed around |

R-53 (the review's ✔) already showed that password-gallery CDN URLs open with **no session**. Once a
URL is known, downloading it never needs the password. Only *listing* a password gallery does.

### 1.2 Two systems write the same files

| | System A: the "Favorites" path | System B: the bookmark path |
|---|---|---|
| Trigger | `PhotoDetailComponents` double tap or Save icon, **only when a collection named "favorites" exists** → `CC.addPhotoToCollection` | `AddToCollectionsDialog` (Folders, grid, three photo viewers) → `CC.addBookmark`, **also on dialog open** for the last-used collections (R-49) |
| Row written | `collection_photos` (REPLACE, `localFilePath = null`, `isDownloaded = false`) | `collection_bookmarks` (type `Image`/`Album`/`Folder`); the image's source URL (`imageUrl`) is **not stored** |
| Download | `workManager.enqueue(OneTimeWorkRequest)`: not unique, no constraints. `Worker.doWork` reads **all** `isDownloaded = 0` rows | Image: `CC.downloadPhotoOffline` in `viewModelScope`, on a new `okhttp3.OkHttpClient()` per call. Album: `CC.downloadAlbumOffline` lists the gallery with `Repo.getAllAlbumImages` and downloads every `archivedUri` in a loop |
| File | `filesDir/offline_photos/{imageKey}.jpg` (`Worker.downloadImageToFile`) | the **same path**, the same name |
| DB after | `localFilePath` = absolute path, `isDownloaded = 1`. On failure (see R-40), `isDownloaded = 1, localFilePath = ""` | `Dao.updateDownloadStatusForAll(imageKey, …)`, which updates only **System A's** rows. Album: SharedPreferences `offline_album_{key} = true` |
| Delete | `CC.removePhotoFromCollection` deletes the row and **never the file**; a collection's CASCADE deletes rows and **never files** | `CC.removeBookmark` → `isBookmarkedAnywhere` (bookmarks only) → `deleteOfflinePhoto` / `deleteOfflineAlbum` (album: **lists the gallery over the network** to find the files) |

### 1.3 Who reads the files: almost nobody

- The **Collections tab** (`CollectionsTabView`) shows only bookmarks, and its image rows load
  `bookmark.thumbnailUrl` from the network through Coil. Tapping one opens the gallery online
  (`onImageClick` → `photo_detail/{albumKey}/{imageKey}`). **No file on disk is ever shown.**
- `VM.loadLocalCollection` (the `local_col_{id}` album key) maps `collection_photos` to a grid using
  `localFilePath ?: thumbnailUrl`. **No screen ever navigates to a `local_col_` key**: a grep of
  `app/src/main` finds the prefix only in `VM.selectAlbum`/`loadLocalCollection`. The history is
  `abe82cb`, then the 3-5 move; no caller in either.
- `VM.loadTargetImage` uses `getCollectionPhotoByKey(imageKey)` as the viewer's **placeholder**:
  `localFilePath ?: thumbnailUrl`. A failed row's `""` is not null, so the placeholder is blank. This
  is the one place where R-40's "blank forever" is reachable today: in the photo viewer, offline.
- `CastController.castCollection` casts `archivedUri`/X3 **URLs**, never the files.
- `DESIGN.md` §5 describes an "Offline Ready" cloud-check icon and a low-storage notification.
  Neither exists: `PhotoDetailScreen` imports `CloudDone`/`CloudQueue` and never uses them, and the
  worker's low-storage path is a silent `Result.failure()`.

**So today "downloaded" means bytes on disk that no screen shows.** Saving a gallery costs on
average about 100 MB (P7), and up to about 1 GB, that nobody can see. This is the main premise
behind Q1 and Q2.

### 1.4 The T6 findings against today's code

| R | Review claim | Today (`97a18f5`) | Status |
|---|---|---|---|
| R-38 | One file per `imageKey` shared by two systems; re-downloads truncate in place; a failure deletes a file another row relies on; removing a bookmark deletes files still in a collection | All confirmed in code: `Worker.downloadImageToFile` and `CC.downloadPhotoOffline`/`downloadAlbumOffline` each write `offline_photos/{key}.jpg` through `FileOutputStream` (truncate). The worker's `catch` does `file.delete()`. `CC.removeBookmark` checks only bookmarks (`isBookmarkedAnywhere`), so it deletes a file a Favorites row uses **and** marks that row pending, with no worker enqueued (N5). `deleteOfflineAlbum` deletes every photo of the gallery, even photos saved on their own. **New:** `addPhotoToCollection`'s REPLACE resets a downloaded row to `isDownloaded = 0`, so re-saving a photo re-downloads it over the shared file (N4) | **open** |
| R-39 | Non-unique worker, no network constraint; `CancellationException` swallowed | Confirmed: `CC.addPhotoToCollection` → `workManager.enqueue(...)`. `doWork`'s `catch (e: Exception)` catches cancellation, and the blocking `execute()` can't be cancelled. **New:** two adds start two workers, which read the same pending list and download the same file into the same path at the same time | **open** |
| R-40 | 408/429 permanent; offline marks `isDownloaded=true, path=""`; blank forever | Confirmed: `code in 400..499` → `PermanentDownloadException` → `path = ""`. Offline: Phase 0 gave the worker `forFileDownloads()` (no cache) but it **kept** the offline interceptor, so an offline GET gets OkHttp's synthetic 504 → `null` → `Result.retry()`, and when `runAttemptCount >= 3` the row becomes `path = ""`. `getPendingDownloads` reads only `isDownloaded = 0`, so such a row is **never retried**. A storage check failure is `Result.failure()` (never retried). "Blank forever" is reachable only in the viewer placeholder (§1.3) | **open** (Phase 0 changed the cache, not this) |
| R-41 | Files never deleted (collection delete, photo remove, site disconnect); offline album delete needs the network | Confirmed for collection delete and photo remove. Site disconnect deletes nothing, but collections are per site and come back when the site is re-selected (Q8 keeps that). Album delete: `deleteOfflineAlbum` → `getAllAlbumImages`. **4-8** added only the locked-gallery message to it; it still needs the network | **open**; 4-8 partly |
| R-37 | Originals in the shared API cache | Worker since Phase 0, Coil since 4-11. `CC`'s own `OkHttpClient()` never had a cache | **fixed** (Phase 0, 4-11) |
| R-30 | Details/EXIF fail offline | `-0` canonical key since 4-9 | **fixed** (4-9) |
| R-21 | Saved passwords deleted on transient errors | Fixed in Phase 0/3; background paths never delete (`UnlockManager`). Phase 5's worker must keep that rule | **fixed**; constraint here |
| R-45 | Viewer has no error state, spins offline | Unchanged (Phase 6). A saved photo shown from its file (Q2) avoids the spinner for saved photos only | open, Phase 6 |
| R-49 | "Save to Collection" writes on open | Unchanged. With System B it **starts a full gallery download** just by opening the dialog on a gallery. Q1 (a) takes the download out of that path; the dialog itself is Phase 6 | open, Phase 6; Q1 |

New findings (rows for `findings.md` are in §10):
- **N1** No screen shows a downloaded file (§1.3). The `local_col_` grid can't be reached.
- **N2** For a video, the app saves `ArchivedUri`, a JPEG still, as `{key}.jpg` (P6), and
  `loadLocalCollection` sets `videoUrl = localFilePath ?: archivedUri` on **every** photo.
- **N3** Album downloads write files that have no DB row (gallery photos are not `collection_photos`).
  Only a SharedPreferences flag records them, so they can only be found again by re-listing.
- **N4** `addPhotoToCollection` (REPLACE) resets a downloaded row and causes a re-download over the
  shared file.
- **N5** `removeBookmark` deletes a file a Favorites row still uses, and leaves that row pending with
  no worker.
- **N6** An Image bookmark doesn't store its source URL, so a retry needs `image/{key}-0` (P8).
- **N7** Gallery downloads run in `viewModelScope` (`CC.downloadAlbumOffline`): they stop silently
  when the ViewModel is cleared, and leave whatever was written.
- **N8** CDN facts P1–P6 (ETag = MD5, Range works, `If-None-Match` ignored, path hash not checked,
  video original = a still). They are not in `SMUGMUG.md`.
- **N9** `DESIGN.md` §5 describes an offline icon and a storage notification that don't exist.

## 2. Target design: one owner of offline files

### 2.1 Who owns what after Phase 5

| Concern | Only owner |
|---|---|
| Which photos must have a file on this phone ("is it referenced?") | **Derived, never counted by hand:** `collection_photos` ∪ Image bookmarks ∪ `offline_gallery_items` (§2.2). The reference count is a query, so it cannot drift |
| The file on disk, its name, its state, its bytes | `OfflineStore` (`data/offline/`, `@Singleton`), with one `offline_files` row per file |
| Downloading, retry and failure classification | `OfflineDownloader.runPass()`, a plain suspend function under one process-wide `Mutex` |
| When a pass runs (network, storage, backoff) | `OfflineScheduler` (unique WorkManager work) → `OfflineDownloadWorker` (glue only) |
| Listing a saved gallery (needs network and sometimes a session) | the pass, via `Repo.getAllAlbumImages` + `UnlockManager.ensureSession` (never deletes a password, R-21) |
| Deleting files | `OfflineStore.collectGarbage()` only. No screen, controller or migration deletes a file itself |
| The words the user sees for a state | `OfflineMessages` (§4), one function from (state, reason) to text |

### 2.2 Data model (migration 16 → 17, Q5)

```
offline_files                                  -- one row per file on disk (or wanted on disk)
  fileKey TEXT PK           -- "{imageKey}/orig"; orig = the bytes ArchivedUri points to (a video's still, Q7)
  imageKey TEXT NOT NULL    -- indexed; validated ^[A-Za-z0-9]{4,16}$ before it becomes a file name
  albumKey TEXT, nickname TEXT NOT NULL DEFAULT ''    -- for re-resolving and the session; diagnostics
  sourceUrl TEXT            -- NULL = resolve with image/{key}-0 (P8) first
  expectedBytes INTEGER, md5 TEXT                     -- ArchivedSize / ArchivedMD5 when known (P1, P5)
  title TEXT, thumbnailUrl TEXT, format TEXT, dateTaken TEXT   -- shown offline (Q2)
  state TEXT NOT NULL       -- PENDING | DOWNLOADING | DONE | FAILED
  failure TEXT              -- NULL | OFFLINE | BUSY | STORAGE_FULL | GONE | FORBIDDEN | LOCKED | DAMAGED | NO_SOURCE | UNEXPECTED
  retryable INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, nextAttemptAt INTEGER
  httpCode INTEGER          -- last HTTP code, for diagnostics and UNEXPECTED's text
  wifiOnly INTEGER NOT NULL DEFAULT 0                 -- Q3
  claim TEXT                -- this process's run id while DOWNLOADING
  relPath TEXT              -- set iff DONE, relative to filesDir
  bytes INTEGER, legacyPath TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL

offline_galleries                              -- a gallery kept offline in a collection (Q1)
  collectionId INTEGER NOT NULL → offline_collections(id) ON DELETE CASCADE
  albumKey TEXT NOT NULL, nickname TEXT NOT NULL DEFAULT '', title TEXT
  state TEXT NOT NULL       -- LIST_PENDING | LISTED | FAILED | LEGACY (migration only, resolved once by 5-8)
  failure TEXT, retryable INTEGER NOT NULL DEFAULT 0, listedAt INTEGER, listedIlu TEXT, photoCount INTEGER
  PRIMARY KEY (collectionId, albumKey); INDEX (collectionId)

offline_gallery_items                          -- what that gallery held when last listed
  collectionId INTEGER NOT NULL, albumKey TEXT NOT NULL, imageKey TEXT NOT NULL, sortIndex INTEGER NOT NULL
  PRIMARY KEY (collectionId, albumKey, imageKey); INDEX (imageKey)
  FOREIGN KEY (collectionId, albumKey) → offline_galleries ON DELETE CASCADE
```

**The reference count** for an image key is the number of rows in `collection_photos` with that key,
plus Image bookmarks with that `itemKey`, plus `offline_gallery_items` with that key. A
`@Query` `isReferenced(imageKey)` computes it, and `unreferencedFiles()` lists the files with none.
Deleting a collection removes all three by the existing and new CASCADEs. Removing an album bookmark
deletes its `offline_galleries` row (cascading its items) in the same `@Transaction` as the bookmark,
**with no network** (R-41). `Dao.removeBookmarkGlobally` (used by `handleAlbumLoadError`) gets the
same pairing. The legacy columns `collection_photos.localFilePath`/`isDownloaded` stay in the
schema and are **no longer read or written** (rebuilding the table to drop them is more risk for no
gain; Q5).

**Not a reference:** folder bookmarks, the HTTP cache, Coil's cache, `cached_*` tables. Cache clears
(`clearEntireCache`) and the cache-table migrations never touch the three offline tables.

### 2.3 Files on disk

- Final: `filesDir/offline/{imageKey}.orig.{ext}`. `ext` comes from `Content-Type` (`image/jpeg` →
  `jpg`, png, gif, heic, `video/mp4` → `mp4`) and falls back to the URL's suffix. Naming by key and
  variant, **not by content**: the X3 and the video have no checksum (P3), and a photo replaced on
  SmugMug keeps its key. When a gallery's listing reports a different `ArchivedMD5` for a DONE file,
  that row goes back to PENDING and the file is replaced on commit (§2.4).
- Temp: `filesDir/offline/.tmp/{imageKey}.orig.{claim}.part`, in the same directory tree, so the
  rename is atomic on one filesystem.
- `filesDir`, not `cacheDir`: the system must not evict these. `data_extraction_rules.xml` already
  excludes the `file` domain from cloud backup and device transfer (R-54, guarded by
  `ManifestGuardTest`). Nothing goes to external storage.
- An image key is checked against `^[A-Za-z0-9]{4,16}$` before it becomes a path (it comes from the
  server). One that fails is `FAILED(NO_SOURCE)`, permanent.

### 2.4 One pass (`OfflineDownloader.runPass(network: NetworkClass, budgetMs)`)

Under the process-wide `Mutex`, so two works never download at once. Then:
1. **Recover** (once per process, the first time): a `DOWNLOADING` row whose `claim` is not this
   process's run id → `PENDING`. If its final file exists and matches `expectedBytes` (and `md5` when
   known), it is adopted as `DONE` instead (the process died between rename and commit). Every
   `.part` not claimed by this process is deleted.
2. **Collect garbage**: files with no reference and not `DOWNLOADING`: delete the row in a
   transaction, then the file. Then the **orphan sweep**: a file in `offline/` with no `DONE` row is
   deleted (this covers a crash between the row delete and the file delete).
3. **List galleries** (5-7): `LIST_PENDING` rows, and `LISTED` rows whose index
   `cached_albums.imagesLastUpdated` is newer than `listedIlu` (at most 3 per pass). Each one gets
   `ensureSession` (rules below), then `getAllAlbumImages`. In one transaction: the items are
   replaced, and file rows are `INSERT OR IGNORE`d with source, size, MD5 and metadata. A DONE row
   whose MD5 changed goes back to PENDING. `AlbumLockedException` → `FAILED(LOCKED)`, retryable when
   `pendingReason != null`, otherwise permanent until the gallery is opened and unlocked (the
   `sessionEpoch` bump re-queues it).
4. **Download**: pick one row at a time: `PENDING`, or `FAILED` with `retryable = 1` and
   `nextAttemptAt <= now`; `wifiOnly = 0` unless this pass runs on unmetered (Q3); oldest first. For
   each:
   - **Storage**: `usable = filesDir.usableSpace`. When `usable − (expectedBytes ?: 8 MB) < floor`
     (Q4), the row becomes `FAILED(STORAGE_FULL)`, retryable, and **the pass stops** (every later file
     would fail the same way).
   - **Claim**: `state = DOWNLOADING, claim = runId` in a transaction, only if still referenced.
   - **Source**: when `sourceUrl` is null, resolve it with `image/{key}-0` (P8). A 404 is
     `GONE`; a 401/403 or a locked gallery goes through `ensureSession(albumKey)` once, then
     `LOCKED`.
   - **Fetch** through the `@Named("images")` `Call.Factory` (cache-less, `RetryingCallFactory`,
     telemetry: 4-11). That gives a few in-call retries of 429/5xx with `Retry-After`, and the CDN
     host shows up in `report.txt`. The call is awaited with `suspendCancellableCoroutine` +
     `call.cancel()`, and the body is copied in 64 KB chunks with `ensureActive()` between them.
   - **Verify**: the byte count equals `Content-Length`, and `expectedBytes`/`md5` when known (P1:
     ETag = MD5 = `ArchivedMD5`). A mismatch → `DAMAGED`. It is retried once, then permanent.
   - **Commit**: `fd.sync()`, then in one transaction: if still referenced, `rename(part, final)` and
     `DONE, relPath, bytes`. Otherwise delete the `.part` and the row.
5. **Budget**: no new file starts after `budgetMs` (8 min; WorkManager stops a worker at 10, E5). The
   pass returns `More`, and the scheduler appends another run.

**Classification** (`DownloadFailure.classify`, step 5-1, pure):

| Outcome | Reason | Retryable | Next attempt |
|---|---|---|---|
| `IOException` (no route, DNS, timeout, reset mid-body); OkHttp's synthetic 504 (`isSyntheticCacheMiss`) | `OFFLINE` | yes; `attempts` not increased | when the network constraint is met |
| 408, 429, 5xx (after `RetryingCallFactory`'s own tries) | `BUSY` | yes | `max(Retry-After, backoff(attempts))`, backoff 1 min, 5, 30, 2 h, then 6 h cap |
| `ENOSPC` while writing, or the floor check | `STORAGE_FULL` | yes | storage-not-low constraint, plus 1 h |
| 404, 410 | `GONE` | **no** | — |
| 401, 403 | `FORBIDDEN` after one re-resolve of the source | no (E4: never seen) | — |
| Locked gallery while listing or re-resolving | `LOCKED` | only if the unlock was Transient | on the next `sessionEpoch` or backoff |
| Size or MD5 mismatch | `DAMAGED` | once | backoff |
| Other 4xx; an image key that fails the pattern; no source after a resolve | `UNEXPECTED` (keeps `httpCode`) / `NO_SOURCE` | no | — |
| `CancellationException` | — (not a failure) | — | row → `PENDING`, `.part` deleted (in `NonCancellable`), **rethrown** |

A retryable failure never becomes permanent by count. Today's "after 3 attempts, mark it done with an
empty path" (R-40) is gone. A permanent failure stays visible with its reason until the user
removes the photo or taps "Try again" (Q2 (a)).

### 2.5 Scheduling (`OfflineScheduler`)

- `kick()` after every add, and at app start when anything is not DONE:
  `enqueueUniqueWork("offline-files", APPEND_OR_REPLACE, req(any network))` and, when any `wifiOnly`
  row is pending, `"offline-files-wifi"` with `NetworkType.UNMETERED` (Q3). `APPEND_OR_REPLACE`
  closes the race where a running pass has just found nothing when an add arrives: the appended
  run picks it up.
- Constraints: `NetworkType.CONNECTED` (or `UNMETERED`) and `setRequiresStorageNotLow(true)`.
- Retry: the pass never returns `Result.retry()`, because a stuck file must not hold back new adds
  appended behind it. When it leaves retryable rows, it enqueues `"offline-files-retry"`
  (`REPLACE`, `initialDelay` = the earliest `nextAttemptAt` − now, same constraints). `Result.failure()`
  is never returned (today's storage path can be stuck forever).
- No foreground service and no notification (no new permission, no Play declaration). Large
  galleries are done in 8-minute passes chained by `More`. Decided here; it changes nothing the user
  sees today (§9).
- The worker is not tied to a `SiteSession`: a site switch doesn't stop it (Phase 3 Q1: downloads
  survive a switch), and it reads nothing that is site-scoped except through explicit keys.

### 2.6 Writers after Phase 5 (all in `CollectionsController`, through `OfflineStore`)

| Action | DB, in one transaction | Then |
|---|---|---|
| Save photo to a collection (Favorites path) | `collection_photos` upsert that **never touches a file row's state** (fixes N4); `offline_files` `INSERT OR IGNORE` with `sourceUrl = archivedUri`, metadata | `kick()` |
| Bookmark an image | bookmark row; `offline_files` `INSERT OR IGNORE` (source = the dialog's `imageUrl`, which is now kept, N6) | `kick()` |
| Bookmark a gallery | bookmark row; per Q1 (a), **no download**. A separate "Keep offline" switch on that row inserts `offline_galleries(LIST_PENDING, wifiOnly per Q3)` | `kick()` when switched on |
| Remove a photo, unbookmark an image | delete the row | `kick()` (the pass collects garbage) |
| Unbookmark a gallery, turn off "Keep offline" | delete the row(s); the CASCADE removes the items. **No network** | `kick()` |
| Delete a collection | delete the collection; the CASCADEs remove every reference | `kick()` |
| Site disconnect or switch | nothing (Q8 (a)) | — |

These are deleted: `CC.downloadPhotoOffline` (both overloads), `deleteOfflinePhoto` (both),
`downloadAlbumOffline`, `deleteOfflineAlbum`, `isAlbumDownloaded`, the `offline_album_*` prefs, the
VM delegates with no remaining caller, `Dao.updateDownloadStatus*` and `getPendingDownloads`.

## 3. Failure design: what the user sees

"Today" is traced from code (*likely*) unless marked. The texts are those of §4.

| Situation | Today | After Phase 5 | User sees after |
|---|---|---|---|
| Save a photo while offline | Worker (no constraint) runs, gets the synthetic 504, retries three times, then `path=""` for good | Row PENDING; work waits for `CONNECTED` | "Waiting for a connection to save this photo." then the saved badge |
| CDN 429 on photo 40 of 600 | 429 counts as a 4xx → `path=""` (A); B: the photo is silently skipped and the toast says "downloaded (599 photos)" | `RetryingCallFactory` retries, then `FAILED(BUSY)` with `nextAttemptAt` from `Retry-After`; the rest continue; a retry work is scheduled | "SmugMug is busy. This photo will be saved automatically in a few minutes." |
| 503 for an hour | same as 429 | backoff up to 6 h, never permanent | the same busy text |
| Photo deleted on SmugMug (404) | `path=""`, blank placeholder | `FAILED(GONE)`, permanent | "This photo was removed from SmugMug, so it can't be saved. Remove it from this collection." + Remove |
| Locked gallery kept offline, saved password, offline | 4-8 message (B) | `FAILED(LOCKED)`, retryable on the session epoch | 4-8's existing texts (`AlbumLockedException.MESSAGE`/`OFFLINE_MESSAGE`/`BUSY_MESSAGE`) |
| Storage below the floor | `Result.failure()`; rows stay pending forever with nothing shown | `FAILED(STORAGE_FULL)`, pass stops, waits for storage-not-low | "Not enough space on this phone to save more photos (needs about 340 MB, 120 MB free). Free up space and saving continues by itself." |
| Process killed mid-file | `.jpg` half written; A: the worker reruns over it; B: lost, toast never shown | `.part` deleted on recovery; the row goes back to PENDING (or is adopted if renamed) | nothing; the count continues |
| App closed during a gallery save | B dies with the ViewModel (N7) | the worker continues; WorkManager runs it | progress keeps going |
| Same photo saved to two collections at once | two workers, one file written twice concurrently | `INSERT OR IGNORE` (one row) and the mutex (one download) | both collections show it saved |
| Photo in a kept gallery **and** saved on its own; gallery unbookmarked | B deletes every photo of the gallery, including the saved one | the reference from the photo remains, so the file stays | the photo stays saved |
| Remove from Favorites while it downloads | row deleted, worker writes the file anyway | commit sees no reference → discard `.part` | nothing left on disk |
| Delete a collection | rows CASCADE, files stay forever (R-41) | references go; the pass deletes unshared files | Q8: confirmation first |
| Unbookmark a gallery offline | lists the gallery → IOException or locked → nothing deleted | references deleted locally, files collected | gallery row gone, space freed |
| Site switch during a save | B keeps going (VM scope) | the worker keeps going | Collections of the other site are unaffected |
| Background crawl prunes a gallery from the index | — | nothing: the offline tables don't reference the index | the kept copy stays until the user removes it |
| Video in a saved gallery | still frame saved as `.jpg` (N2) | Q7 (a): the still, labelled | "Videos play online only. The saved copy is a still picture." |
| Existing install: rows `isDownloaded=1, path=""` | blank placeholder, never retried | Q6 (a): re-queued; a valid old file is adopted | the saved state, after downloading |

**Intermediate states.** Each collection row shows a state (§4). A kept gallery shows
"Saving… 120 of 600" while its rows are PENDING or DOWNLOADING; when some failed, the largest group's
reason (for example "3 photos can't be saved: removed from SmugMug"). A badge reads Room through a
`Flow` per collection, never per item (R-49's per-item `collectAsState`).

## 4. User-visible changes and every new string

Each string names the cause and the way out (owner feedback on 4-8). They live in
`OfflineMessages.kt` as constants with a unit test per state (step 5-1), so the wording can be changed
in one place.

| Key | Text | When |
|---|---|---|
| `SAVED` | "Saved on this phone" (badge content description) | DONE |
| `SAVING` | "Saving to this phone…" / gallery: "Saving to this phone… {done} of {total}" | PENDING or DOWNLOADING with no failure |
| `WAITING_NETWORK` | "Waiting for a connection to save this photo." | failure OFFLINE |
| `WAITING_WIFI` | "Will save on Wi-Fi. {done} of {total} saved so far." | `wifiOnly` rows pending (Q3) |
| `BUSY` | "SmugMug is busy. This photo will be saved automatically in a few minutes." | BUSY |
| `STORAGE` | "Not enough space on this phone to save more photos (needs about {need}, {free} free). Free up space and saving continues by itself." | STORAGE_FULL |
| `GONE` | "This photo was removed from SmugMug, so it can't be saved. Remove it from this collection." | GONE |
| `FORBIDDEN` | "SmugMug won't let this app download this photo. Open it once online, then try again." | FORBIDDEN |
| `DAMAGED` | "The download arrived damaged. Try again, or remove it from this collection." | DAMAGED (permanent) |
| `UNEXPECTED` | "SmugMug answered this download with error {code}. Try again later, or remove it from this collection." | UNEXPECTED |
| `VIDEO_STILL` | "Videos play online only. The saved copy is a still picture." | a saved video (Q7 (a)) |
| `KEEP_OFFLINE` | switch label "Keep offline · {size}" (size from the listing, or "size unknown") | gallery row (Q1 (a)) |
| `DELETE_CONFIRM` | "Delete “{name}”? {n} photos saved on this phone ({size}) will be removed. They stay on SmugMug." | delete collection, when it has files (Q8) |
| `OFFLINE_GALLERY` | "You're offline. Showing the {n} photos saved on this phone." | saved gallery opened offline (Q2 (a)) |
| Buttons | "Try again", "Remove" | FAILED rows |

These texts and toasts go away: "Album downloaded offline (N photos)", "No photos to download",
"Album download failed: …", "Offline files deleted", "Download failed: HTTP …", "Empty response
body". The 4-8 lock texts are reused unchanged. "Saved to {collection}!"/"Added to Favorites!" stay.

## 5. Test harness (step 5-0)

- **`FakeCdn`** (test): a loopback `ServerSocket` with a `Dns` that sends `photos.smugmug.com` to
  127.0.0.1. This is the `CoilClientHasNoHttpCacheTest`/`LoopbackSmugMug` pattern, so the
  **production** images `Call.Factory` (`buildImageCallFactory` over `buildSmugMugClient(...).forFileDownloads()`)
  sits in front of it, including the offline rule's synthetic 504. Knobs: per-path status sequence
  (429 with `Retry-After`, 408, 503, 404, 403), a body size and its MD5, a truncated body (close after
  N bytes), a `hold(path)` gate for cancellation and racing, a request log, and an `online` flag.
- **`FakeSmugMugServer` realism** (P1, P5, P6, P8): `album!images` items gain `ArchivedUri` in the
  real shape (`https://photos.smugmug.com/{path}/i-{key}/0/{hash}/D/{name}-D.jpg`), and `ArchivedSize`
  and `ArchivedMD5` **only when they are in `_filter`** (as live). A video's `ArchivedUri` is a
  `-D.jpg` still (P6). `image/{key}-0` answers those fields, and 404 for an unknown key.
  `ApiFieldsTest`'s fixtures get the two fields (5-7 adds them to `_filter`).
- **Real-shaped offline fixture** (`OfflineFixture`): collection 1 "Favorites" and 2 "Trip" (site
  `idzifamily`). Photo `XVRvVTM` is in both (the shared file). `n83tQ3s` is a broken legacy row
  (`isDownloaded=1, localFilePath=''`) and `Hk42gZp` a pending one. There is an Image bookmark on
  `XVRvVTM` in "Trip", a gallery bookmark `FfHCms` (NodeID `LCdk7F`, AlbumKey ≠ NodeID) in "Trip",
  and a password gallery under `2sDN5x` → `P4BKB` → gallery, so a kept gallery needs a session. Byte
  sizes come from P1/P7. Dates are relative to now.
- **WorkManager**: `testImplementation("androidx.work:work-testing:2.9.0")`;
  `WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration{SynchronousExecutor,
  a WorkerFactory that builds OfflineDownloadWorker with test deps})`, `TestDriver.setAllConstraintsMet`,
  and `TestListenableWorkerBuilder` for single runs. Everything runs under Robolectric
  `@Config(sdk=[33])`, like the migration tests.
- **Free space** is a seam: `OfflineStore(freeBytes: () -> Long = { filesDir.usableSpace })`.
- **Evidence tasks**: (E1) on emulator-5554 (debug build, the saved passwords already there; the
  debug HTTP log is redacted, R-52; nobody types or prints a password), open one gallery under
  Family. Record whether its `album!images` items carry `ArchivedUri`, and whether the request with
  `ArchivedSize,ArchivedMD5` added echoes them (a one-off `curl` is not allowed, since it would need
  the password). (E5) is not run; the pass is written so that a stop at any point is safe.

## 6. Steps (Sonnet, one commit each; full suite + `BUILD SUCCESSFUL` after each)

Each test is committed **green with its fix**. Red evidence goes in the progress log. Get it by
running the test against a stub of the new API that keeps today's behaviour, or, for the
"old code" checks, by running the scenario against `HEAD` in a `git worktree`. Never `git stash`.
Red tests are never committed or `@Ignore`d *(owner, Phase 1)*. Rollback is `git revert` for every
step except 5-2: after 5-2, a downgrade below v17 is not possible without a destructive migration,
and none is configured.

| Step | Files | Work | Failing-first test (real-shaped data; how it is red) | Risk |
|---|---|---|---|---|
| **5-0** | test: `FakeCdn`, `OfflineFixture`, `FakeSmugMugServer` (+`ArchivedUri/Size/MD5`, `image/{key}-0`); `build.gradle.kts` (`work-testing`); `FakeRealismTest` +3. No main change | §5 harness; E1 recorded in the progress log | Sanity, green on old code: the fake's `ArchivedSize`/`ArchivedMD5` appear only when filtered; a video's `ArchivedUri` ends `-D.jpg`; through the production images factory with `online=false`, a CDN GET is one synthetic 504 with no network request; the `FakeCdn` MD5 matches the bytes. **Characterization runs, not committed** (worktree at `HEAD`, recorded): the old `OfflineDownloadWorker` via `TestListenableWorkerBuilder` with `FakeCdn` answering 429 → the row is `isDownloaded=1, localFilePath=''` after one run (R-40); offline → after 3 runs the same (R-40); two `addPhotoToCollection` → two workers and 2 CDN requests for one key (R-39) | Low. Loopback + Robolectric flake as in 2-9/4-0: rerun once, record |
| **5-1** | new `data/offline/DownloadFailure.kt` (`classify(code, headers, exception, wroteBytes)`), `OfflineMessages.kt` | §2.4 classification table, §4 texts. Pure, no caller yet | `DownloadFailureTest` (table-driven, ~16 cases: 408/429 with and without `Retry-After` (seconds and HTTP-date), 500/503, synthetic 504 vs a real 504, `UnknownHostException`, `SocketTimeoutException`, reset mid-body, 404, 410, 401, 403, 400, ENOSPC, size mismatch) red against a stub that maps `400..499` to permanent like today (e.g. `429 expected BUSY retryable but was UNEXPECTED permanent`). `OfflineMessagesTest`: every reason has a text naming a cause and a way out (non-empty, not "try again" alone) | None (no caller) |
| **5-2** | `Entities.kt` (`OfflineFile`, `OfflineGallery`, `OfflineGalleryItem`), new `OfflineDao`, `AppDatabase` (v17, `MIGRATION_16_17`), `AppModule.addMigrations`, `app/schemas/.../17.json`, `MigrationTest` (+3). **Needs Q5, Q6** | Migration only, isolated (§6.1). No runtime reader or writer yet | `migrate16To17_backfillsFilesAndGalleries_keepsUserData` on `OfflineFixture` at v16: one `offline_files` row for `XVRvVTM` (two `collection_photos` rows and an Image bookmark: one file), `n83tQ3s` PENDING with `legacyPath = 'offline_photos/n83tQ3s.jpg'`, `Hk42gZp` PENDING, an `offline_galleries` row `('Trip','FfHCms', LEGACY)`, and the user-table dump before and after is equal. `migrate13To17_keepsUserData`, `migratedV16Database_opensWithRoomAtV17_andDaoWorks`. Red, as in 2-5: comment out the backfill `INSERT`s → `expected:<3> but was:<0>`; comment out a `CREATE TABLE` → `Migration didn't properly handle`. `SchemaGuardTest` unchanged and green (it enforces 17.json and the registration) | **Medium, one-way.** The first build after the bump can miss `17.json` in the merged assets (2-5): run twice. No device install between 5-2 and 5-6 on data that matters: until 5-6 the old writers keep writing only the legacy columns |
| **5-3** | new `data/offline/OfflineStore.kt` (layout, key check, `request`, `isReferenced`, `collectGarbage`, `sweepOrphans`, `recover`, `beginWrite`/`commit`/`abandon`), `OfflineDao` queries | §2.2-§2.3 and steps 1-2 and the commit of §2.4. No caller yet | `OfflineStoreTest` (in-memory Room, temp `filesDir`), red against a stub that only inserts rows: shared key in two collections, then remove from one → file stays; remove from both → `collectGarbage` deletes row and file (red: file still there); `request` on a DONE row keeps DONE (red: reset to PENDING, N4); commit after the reference is gone → no final file and no row; `recover` with a foreign claim and a `.part` → PENDING and no `.part`; a renamed-but-uncommitted file with the right size → adopted DONE; an orphan file → swept; `../x` as an image key → `NO_SOURCE`, no file outside `offline/` | Medium: file and DB ordering. Every order is pinned by a test |
| **5-4** | new `data/offline/OfflineDownloader.kt` (`runPass`), `Repo.resolveImageSource(imageKey)` (`image/{key}-0`, fields of P8) | §2.4 steps 4-5 for photos (galleries in 5-7) | `OfflineDownloaderTest` with `FakeCdn` + the production images factory, red against a stub pass that downloads like the old worker: **429** with `Retry-After: 120` → `FAILED(BUSY)`, `nextAttemptAt ≥ now+120 s`, the next photo still DONE (red: `UNEXPECTED` permanent); **offline** → `FAILED(OFFLINE)`, `attempts` 0, after `online=true` → DONE, never `DONE` with an empty path; truncated body → `.part` gone, OFFLINE, then DONE; MD5 mismatch → DAMAGED and retried once; 404 → GONE; 403 → one `image/{key}-0` re-resolve, then FORBIDDEN; `sourceUrl = null` → resolved, then DONE; **storage**: `freeBytes` below floor → `STORAGE_FULL` and no further request; **cancel**: `hold` mid-body, cancel the pass job → `CancellationException` reaches the caller, row PENDING, no `.part` (red: the old `catch (Exception)` swallows it); **concurrent**: two `runPass` at once on one key → 1 CDN request (red: 2); budget → returns `More` | Medium: blocking I/O off the main thread (`Dispatchers.IO` injected) |
| **5-5** | `OfflineDownloadWorker` (rewritten as glue: `runPass`, then `OfflineScheduler.after(result)`), new `OfflineScheduler`, `SmugViewApp` (kick at start when anything is not DONE), `DiagLog` line `offline pass#n` | §2.5 | `OfflineWorkerTest` (work-testing): two `kick()`s while the first runs → the work names are unique, never two running passes, and the appended run downloads the late add (red: plain `enqueue` → 2 running); work spec has `CONNECTED` + storage-not-low (red: no constraints); a retryable leftover → `offline-files-retry` enqueued with the right delay, result `success` (red: `retry()`); a worker stopped by `TestDriver`/cancel → row PENDING, no `.part`; nothing pending → `success` with no request | Low-medium: Hilt worker factory in tests (build the worker by hand with a `WorkerFactory`) |
| **5-6** | `CollectionsController` (writers of §2.6, kick), `CollectionDao` (`removeBookmark`/`removeBookmarkGlobally`/`deleteCollection` as `@Transaction` with their offline rows), VM delegates trimmed, `AddToCollectionsDialog`/viewers keep their calls. Old download/delete code and the old worker body deleted. **Needs Q3, Q7** | R-38, R-39, R-41 (photos, bookmarks, collections) | `OfflineCollectionsScenarioTest` (`ScenarioRig` + `FakeCdn` + test WorkManager): **shared file across collections**: `XVRvVTM` to Favorites and to Trip (bookmark), one CDN request, one file; unbookmark in Trip → file kept (red on old code: `removeBookmark` deletes it, N5); remove from Favorites → file deleted after the pass; re-save a DONE photo → no new request (red: REPLACE resets, N4); delete "Trip" → its unshared files gone (red: files stay, R-41); **concurrent adds** of the same key from two coroutines → one row, one request. `LockedAlbumTest`'s `downloadAlbumOffline` case moves to 5-7 | Medium: removes about 200 lines of `CC`. `AddToCollectionsDialog`'s add-on-open (R-49) now only queues small single photos |
| **5-7** | `OfflineDownloader` (gallery listing, §2.4 step 3), `CollectionsController` ("Keep offline" toggle), `CollectionsTabView` (the switch), `Api.getAlbumImages` `_filter` + `ArchivedSize,ArchivedMD5`, `ApiFieldsTest` fixtures, `LockedAlbumTest`. **Needs Q1, Q3** | Gallery download moved into the worker (review row). R-41 offline delete | `OfflineGalleryTest`: keep `FfHCms` → listing via `Pager`, 150 file rows with size and MD5, then DONE (wifiOnly per Q3: no download on a metered pass); unbookmark while offline (`online=false`) → rows and items gone, files collected, **no network request** (red on old code: `deleteOfflineAlbum` throws or finds nothing offline); a gallery photo also saved alone survives unbookmarking the gallery (red: old deletes it); a password gallery with a saved password → `ensureSession` once, then listed; with `unlockCode=503` → `FAILED(LOCKED)` retryable and the password **kept** (R-21 rule); listing MD5 changed for a DONE photo → re-downloaded once; ILU newer than `listedIlu` → re-listed, a removed photo's reference dropped | Medium: a 600-photo gallery in the fake must finish in a test (budget 0 delay) |
| **5-8** | `OfflineStore.repairLegacy()` (once, flag `offlineLegacyRepaired` in `SyncStateStore`), `CollectionsController` (prefs `offline_album_*` read once, then removed). **Needs Q6, Q1** | Existing installs | `LegacyRepairTest` from a v16 DB migrated to v17 with real files in a temp `filesDir/offline_photos/`: a valid legacy file is adopted (moved to `offline/`, DONE, **no CDN request**); `n83tQ3s` (no file) → downloaded; a legacy file with no reference → deleted with the directory after every row is settled; `LEGACY` gallery with prefs flag `true` → `LIST_PENDING` and its files adopted by key at listing; flag absent → row deleted, bookmark kept as a shortcut. Red: against a stub `repairLegacy` that does nothing (`expected DONE but was PENDING`, legacy dir still present) | **One-way**: deletes unadopted legacy files (Q6) |
| **5-9** | Readers: `OfflineStore.localFile(imageKey)` as a `Flow`; `VM` sets a non-serialized `localUri` on photos that have a DONE file; `PhotoDetailComponents` shows `localUri` first (no network for a saved photo); `CollectionsTabView` badge, gallery summary, Try again/Remove, `DELETE_CONFIRM`; the gallery grid falls back to `offline_gallery_items` + file metadata when page 1 fails offline (`OFFLINE_GALLERY`); `loadLocalCollection`/`local_col_` deleted (dead, N1); `videoUrl` no longer set on stills (N2). **Needs Q2, Q8** | What the files are for | `OfflineReaderTest` (ScenarioRig, `online=false`): a saved photo opens with `localUri` = its file and no CDN request (red: `null`, the viewer would go to the network); a kept gallery opened offline lists its saved photos with `OFFLINE_GALLERY` (red: the 4-8 offline error); a FAILED row's state text = `OfflineMessages` for its reason (red: none); `ZoomFallbackUrlTest` +1: a local file wins over X3. `DELETE_CONFIRM` counts and sizes from the DAO | Medium: UI. Compose is not unit-tested here; covered by emulator checks §7.2 |
| **5-10** | `CacheDoctor`/`DoctorDao` (+`offline_by_state`, `offline_unreferenced`, `offline_done_bytes`; counts only), `report.txt` section; docs: `SMUGMUG.md` (CDN facts P1-P6), `DESIGN.md` §5 (rewrite to what exists), `PRIVACY_POLICY.md` checked (it already says saved photos stay on the device), `findings.md` rows §10 | Observability and docs | `CacheDoctorTest` +1: fixture counts by state exactly (red against a doctor without the checks). Docs: none | None |
| **5-11** | — | Exit check §7 | — | — |

**Order.** 5-0 → 5-1 can start now. **5-2 is the one-way step** and waits for Q5/Q6. 5-3 → 5-4 →
5-5 → 5-6 → 5-7 → 5-8 → 5-9 in order (each uses the previous one). 5-10 can follow 5-5 at any time.
Expected suite: 483 → about **545-555**: 5-0 +3, 5-1 +17, 5-2 +3, 5-3 +9, 5-4 +13, 5-5 +5, 5-6 +6,
5-7 +7, 5-8 +5, 5-9 +5, 5-10 +1. The exact count is recorded per step.

### 6.1 The migration step (5-2) in full

```sql
CREATE TABLE offline_files (... §2.2 ..., PRIMARY KEY(fileKey));
CREATE INDEX index_offline_files_imageKey ON offline_files(imageKey);
CREATE INDEX index_offline_files_state ON offline_files(state);
CREATE TABLE offline_galleries (... FOREIGN KEY(collectionId) REFERENCES offline_collections(id) ON DELETE CASCADE);
CREATE INDEX index_offline_galleries_collectionId ON offline_galleries(collectionId);
CREATE TABLE offline_gallery_items (... FOREIGN KEY(collectionId, albumKey) REFERENCES offline_galleries(collectionId, albumKey) ON DELETE CASCADE);
CREATE INDEX index_offline_gallery_items_imageKey ON offline_gallery_items(imageKey);
-- Photos of System A: one file row per key, whatever the number of collections.
INSERT OR IGNORE INTO offline_files (fileKey, imageKey, albumKey, nickname, sourceUrl, title, thumbnailUrl,
       dateTaken, state, retryable, attempts, wifiOnly, legacyPath, createdAt, updatedAt)
  SELECT cp.imageKey || '/orig', cp.imageKey, MAX(cp.albumKey), MAX(c.siteNickname),
         MAX(NULLIF(cp.archivedUri, '')), MAX(cp.title), MAX(cp.thumbnailUrl), MAX(cp.dateTaken),
         'PENDING', 0, 0, 0, 'offline_photos/' || cp.imageKey || '.jpg', :now, :now
  FROM collection_photos cp JOIN offline_collections c ON c.id = cp.collectionId GROUP BY cp.imageKey;
-- Image bookmarks of System B (source unknown, N6: resolved by image/{key}-0).
INSERT OR IGNORE INTO offline_files (...) SELECT b.itemKey || '/orig', b.itemKey, MAX(b.albumKey), MAX(c.siteNickname),
         NULL, MAX(b.title), MAX(b.thumbnailUrl), NULL, 'PENDING', 0, 0, 0, 'offline_photos/' || b.itemKey || '.jpg', :now, :now
  FROM collection_bookmarks b JOIN offline_collections c ON c.id = b.collectionId WHERE b.type = 'Image' GROUP BY b.itemKey;
-- Gallery bookmarks: decided once in Kotlin (5-8), because the "finished" flag lives in SharedPreferences.
INSERT OR IGNORE INTO offline_galleries (collectionId, albumKey, nickname, title, state, retryable)
  SELECT b.collectionId, b.itemKey, c.siteNickname, b.title, 'LEGACY', 0
  FROM collection_bookmarks b JOIN offline_collections c ON c.id = b.collectionId WHERE b.type = 'Album';
```
`:now` is a literal the migration computes. `isDownloaded`/`localFilePath` are **not read**: the old
code always wrote `offline_photos/{key}.jpg`, so whether a file exists is decided by looking at the
file in 5-8, not by a flag that R-40 made wrong. The image key is checked against the pattern
(§2.3) before it is joined into a path, in 5-8, which is the first code that touches the disk. The
migration writes no file and deletes nothing.

## 7. Exit check (step 5-11)

### 7.1 Unit (named)

`DownloadFailureTest`; `OfflineStoreTest`; `OfflineDownloaderTest` cases **429**, **offline**,
**cancel**, **concurrent**, **storage**; `OfflineWorkerTest` (unique + constraints); 
`OfflineCollectionsScenarioTest` cases **shared file across collections** and **concurrent adds**;
`OfflineGalleryTest` (offline unbookmark); `LegacyRepairTest`; **process death**:
`OfflineStoreTest.recover_*` plus `OfflineWorkerTest.passAfterNewProcess_resumes` (a new
`OfflineStore` over the same DB and `filesDir` with a new run id: no `.part` left, the rest
downloaded, no file downloaded twice); **migration**: `MigrationTest.migrate16To17_*`,
`migrate13To17_keepsUserData`, `migratedV16Database_opensWithRoomAtV17_andDaoWorks`; `SchemaGuardTest`.
The full suite is green twice in a row with `--rerun-tasks` (the R-69 rule: `BUILD SUCCESSFUL` is
checked, not just the exit code).

### 7.2 Emulator (emulator-5554 only; debug build of the 5-10 commit; idzifamily; nobody types or prints a password)

Evidence comes from `run-as … ls -la files/offline files/offline/.tmp`, `sqlite3 … "SELECT state, failure, COUNT(*) FROM offline_files GROUP BY 1,2"`,
`diag-0.log` `offline pass#` lines, and screenshots.
1. **Upgrade with a broken legacy row.** Install the `97a18f5` debug build. Create "Favorites" and
   "Trip", double-tap 3 photos, and bookmark one gallery of about 60 photos (`N74KSK`). Then
   `sqlite3 UPDATE collection_photos SET isDownloaded=1, localFilePath='' WHERE imageKey=<one>`,
   delete that one file, and install the Phase 5 build over it. Expect: `user_version` 17; valid
   files adopted (no CDN request for them in `diag-0.log`); the broken one downloaded; gallery per
   Q1/Q6; `offline_photos/` gone after the pass.
2. **Shared file across collections**: save one photo to both collections; `ls` shows one file.
   Remove it from one: the file stays. Remove it from the other: the file is gone after the next pass.
3. **Offline**: airplane mode on, save a photo. The row reads `WAITING_NETWORK` and the DB says
   PENDING (no DONE with an empty path). Airplane off: DONE within about a minute, no app restart.
4. **Wi-Fi rule** (Q3): `svc wifi disable` with mobile data on, switch a gallery to "Keep offline". It
   reads `WAITING_WIFI` and no `photos.smugmug.com` gallery requests appear; `svc wifi enable`, and it
   proceeds.
5. **Process death mid-gallery**: during a gallery save, `am kill`, then relaunch. No `.part` in
   `.tmp` after the first pass; the count continues; the files' `COUNT(*)` equals the number of rows,
   with no duplicates.
6. **Offline viewing** (Q2): airplane mode, open a saved photo. It shows at full size, with no
   `photos.smugmug.com` request in the debug OkHttp log. Open the kept gallery: `OFFLINE_GALLERY`
   and its photos.
7. **Delete a collection** (Q8): the confirmation shows the right count and size; its unshared files
   are gone, and the shared one stays.
8. **Storage** (optional, emulator only): fill the data partition with
   `run-as … dd if=/dev/zero of=files/fill …` to just above the floor, then save a gallery. Expect
   `STORAGE` text, and the pass stops (no further requests). Delete the fill: saving continues. Delete
   `files/fill` afterwards whatever happens.
9. **Diagnostics**: `report.txt` shows the offline doctor counts; `offline pass#` lines carry no URL
   path beyond the telemetry's `<path:N segs>` form, no key and no password.
Not on the emulator (unit only): 429/408/5xx from the real CDN (cannot be forced, P10 E2), the
10-minute stop (E5), and concurrent adds (timing).

### 7.3 Phone checks (last phase; batched with the other phases; a local test install, never a Play track)

- Before installing: pull `report.txt` and record the real counts of `collection_photos`, bookmarks
  by type, and broken legacy rows. The v0.8.0 doctor has no offline counts, so this is a one-off
  read-only `adb` query if the build is debuggable, or else "unknown, repaired by Q6 policy".
- After installing the Phase 5+ build: `offline_by_state` shows no PENDING with OFFLINE when the phone
  is online; adopted vs re-downloaded counts; `offline/` size equals `offline_done_bytes`.
- Offline viewing of a saved photo and a kept gallery on the phone (airplane mode).
- The Phase 4 install note still applies: replacing the Play build wipes the saved passwords, and
  the offline files go with it, so the phone starts from an empty offline state. The legacy repair
  (5-8) can only be seen on the phone if the build is installed **over** v0.8.0 (same signing key),
  which is the owner's call in that phase.

## 8. Needs owner sign-off (recommended answer first)

| Q | Choice | Recommended | Consequence of each option |
|---|---|---|---|
| Q1 | What saving a **gallery** to a collection does | **(a) Bookmarking a gallery saves a shortcut only. A "Keep offline · {size}" switch on the gallery's row in the collection saves its photos** (in the worker, wifi rule per Q3). Existing gallery bookmarks whose old download finished (`offline_album_{key}` = true) start with the switch **on** (their files are adopted, nothing is re-downloaded); the others start **off** | (a) opening the Save dialog (which auto-adds, R-49) never starts a 100 MB-1 GB download; the size is visible before choosing. (b) every saved gallery downloads all its photos, as today, but in the worker with progress and the Wi-Fi rule: simplest change of meaning, the most data and storage. (c) galleries are never saved offline, only single photos: existing gallery files are deleted by 5-8 |
| Q2 | What saved copies are **used** for (N1: today nothing shows them) | **(a) The photo viewer shows a saved photo from its file; Collections rows show saved / saving / failed with the reason and Try again / Remove; a kept gallery opens offline from its saved photos.** The dead `local_col_` grid is deleted | (a) "saved" means "works with no network", and every state is visible. (b) the viewer only, with no row states: failures stay invisible, as today. (c) keep the files without showing them (today's behaviour): Phase 5 only makes them correct, and they stay of no use to the user |
| Q3 | Which network saving may use | **(a) Single photos: any network. Galleries: Wi-Fi (unmetered) only**, with "Will save on Wi-Fi" shown | (a) a single photo saves at once as today; large galleries never use mobile data. (b) any network for everything (today): a gallery can use 1 GB of mobile data. (c) Wi-Fi only for everything, a new default the user never chose: a photo saved on mobile data waits. A settings switch is not in this phase (Phase 6) |
| Q4 | Storage limit | **(a) No app cap. Stop saving when free space would fall below 1 GB** (and when Android reports storage low), and say how much is needed and free | (a) the phone is never filled by the app; the user frees space and saving resumes. (b) a fixed app cap (e.g. 5 GB), with an extra "cap reached" state and text: predictable but arbitrary. (c) today's 50 MB floor: the app can fill the phone to within 50 MB |
| Q5 | Room migration 16 → 17 (one-way) | **(a) Add `offline_files`, `offline_galleries` and `offline_gallery_items` (§2.2, §6.1), backfilled from today's rows. Keep `collection_photos.localFilePath`/`isDownloaded` in the schema, unused** | (a) additive; the user's collections, bookmarks and photos are untouched; downgrade impossible (as for any version bump). (b) the same, and rebuild `collection_photos` to drop the two columns: a table copy of user data for no user-visible gain. (c) no migration: keep `isDownloaded`/`localFilePath` and patch the two systems; reference counting across collections and offline gallery delete cannot be done reliably |
| Q6 | Existing installs: broken rows and old files | **(a) Silently repair**: an existing `offline_photos/{key}.jpg` that is non-empty (and matches size/MD5 once known) is adopted; every photo without one, including the `isDownloaded=1, path=""` rows, is downloaded again under Q3's rule; old files that nothing references are **deleted** once all rows are settled | (a) the user ends with every saved photo actually saved, without asking; it uses data for photos they already chose to save. (b) adopt valid files; mark the rest FAILED with "Try again" (no automatic download); keep unreferenced old files until the user deletes the collection. (c) wipe all offline state and files; the user re-saves |
| Q7 | Which bytes are saved | **(a) The original (`ArchivedUri`) for photos; for a video, its still picture with "Videos play online only"** (P6: a 4-minute video is 140-218 MB) | (a) today's photo meaning, honest about videos. (b) photos as the X3 rendition (about 60% of the bytes, P3; no checksum): less storage, and "save to device" from a saved copy would not be the original. (c) also the 1280 video on Wi-Fi: real offline video at 140 MB per 4 minutes |
| Q8 | Deleting | **(a) Deleting a collection, removing a photo, unbookmarking or turning off "Keep offline" deletes the saved copies nothing else uses. Deleting a collection that has saved photos asks first** (`DELETE_CONFIRM`). Disconnecting or switching sites deletes nothing (collections belong to the site and return with it) | (a) space comes back; one new confirmation (R-49's broader "confirm on delete" stays Phase 6). (b) the same with no confirmation: one tap removes up to 1 GB of saved photos (they stay on SmugMug). (c) also delete on site disconnect: reconnecting means re-downloading |

**Decided here** (no sign-off: same meaning as today, or a fix to match what the code already
claims):
- One owner (`OfflineStore`); the reference count is a query over the owner tables, not a counter.
- File names are key + variant, not content hashes (§2.3). Writes go to a temp file, then rename.
  Size and MD5 are checked when the API gives them.
- Unique work with `APPEND_OR_REPLACE`, a separate delayed retry work, and an 8-minute pass budget.
  No foreground service, no notification, no new permission.
- 408/429/5xx/offline are retryable forever with backoff; 404/410 are permanent; 401/403 get one
  re-resolve. A retryable failure never becomes "done".
- `CancellationException` always propagates; a cancelled file is left PENDING and its `.part` deleted.
- Downloads use the `@Named("images")` factory (cache-less, retries, telemetry). `CC`'s ad-hoc
  `OkHttpClient()`s are deleted.
- The worker never deletes a saved password and uses `UnlockManager.ensureSession` (R-21 rule).
- No Range resume in Phase 5. Photos are at most about 5 MB (P7); resuming only pays for videos, and
  Q7 (a) saves none.

### 8.1 Owner sign-off (2026-10-01)

All eight questions answered; Q1, Q2, Q4, Q5, Q6, Q7 and Q8 are **as recommended**. **Q3 is (a) with an
override:** the owner wants a way to override the Wi-Fi rule, so a kept gallery can be saved over any
network if the user chooses. Consequences for the steps (the implementer follows these over any earlier
text):

- The `offline_galleries.wifiOnly` column already planned in 2.2 is the user's setting, not a constant.
  A new kept gallery starts `wifiOnly = 1` (Wi-Fi only). The Keep-offline row has a second control, **"Use
  mobile data too"**, which sets `wifiOnly = 0` for that gallery; the scheduler (2.5) then runs that
  gallery's pass on any connected network. No global settings screen (that stays Phase 6).
- A gallery that is waiting for Wi-Fi shows "Waiting for Wi-Fi" with the size and a **"Use mobile data"**
  action beside it. Turning the switch back off restores `wifiOnly = 1`.
- Single photos keep "any network" (no override needed).
- New strings (to be listed in section 4 and flagged to the owner when they ship): the control label
  "Use mobile data too", the action "Use mobile data", and the waiting state "Waiting for Wi-Fi". They
  must say the size so the user knows what mobile data will be used (for example "Use mobile data
  (103 MB)").
- Needed in: 5-2 (the column and its default in the migration backfill: backfilled galleries are
  `wifiOnly = 1`), 5-5 (scheduler honours a per-gallery flag), 5-9 (the control and the state text).
  Add a worker test: a gallery with `wifiOnly = 0` downloads on a metered network; one with
  `wifiOnly = 1` waits.

## 9. Verified vs assumed

| Claim | Status |
|---|---|
| P1-P9 | verified live (anonymous) or on the emulator, 2026-10-01 (§1.1) |
| No screen reads a downloaded file; `local_col_` unreachable | verified by grep of `app/src/main` at `97a18f5` (*confirmed (code)*); not observed on a device |
| R-38/R-39/R-40/R-41 mechanisms | traced in code (*likely*); the 5-0 characterization runs make them *confirmed* on the old worker |
| `ArchivedUri`/`ArchivedMD5` present for a password gallery with a session | **unverified**: E1 (5-0, emulator) |
| CDN 408/429/5xx shape, `Retry-After`, 401/403/410, URL expiry | **unverifiable** here (P10); the classifier handles each, and E3 is mitigated by the re-resolve |
| WorkManager stops a worker after 10 minutes and reschedules it | from the WorkManager docs (*assumed*); the pass is safe to stop at any point either way |
| `File.renameTo` is atomic within `filesDir` on Android's ext4/f2fs | platform behaviour (*assumed*); recovery covers the crash windows on both sides |
| `APPEND_OR_REPLACE` runs an appended request after a running one that succeeds | WorkManager docs (*assumed*); pinned by `OfflineWorkerTest` |
| Room accepts a composite FK to a composite PK with CASCADE | Room docs (*assumed*); pinned by the 5-2 migration and Room-open tests |
| Suite size 483 | from the 4-13 progress log; **not re-run** by this planner |

## 10. findings.md rows

| R / N | Planned | Note |
|---|---|---|
| R-38 one file shared by two systems | 5-3, 5-6 (photos), 5-7 (galleries) | plus N4, N5 |
| R-39 non-unique worker, swallowed cancellation | 5-4, 5-5 | |
| R-40 408/429 permanent, offline → `path=""` | 5-1, 5-4; existing rows 5-8 (Q6) | "blank forever" only in the viewer placeholder today (§1.3) |
| R-41 files never deleted; offline gallery delete needs network | 5-6, 5-7; Q8 | site disconnect deliberately deletes nothing (Q8 (a)) |
| N1 no screen shows a downloaded file; `local_col_` grid unreachable | 5-9, Q2 | |
| N2 video "original" is a JPEG still; `videoUrl` set on every collection photo | 5-9, Q7 | live P6 |
| N3 album downloads leave files with no DB row | 5-7, 5-8 | |
| N4 re-saving a photo resets it and re-downloads over the shared file | 5-3, 5-6 | |
| N5 unbookmarking deletes a Favorites photo's file, row left pending with no worker | 5-6 | |
| N6 Image bookmarks don't keep their source URL | 5-4 (`image/{key}-0`), 5-6 | live P8 |
| N7 gallery downloads die with the ViewModel | 5-7 | |
| N8 CDN facts (ETag = MD5, Range 206, `If-None-Match` ignored, hash not checked) | 5-10 docs | live P1-P4 |
| N9 `DESIGN.md` claims an offline icon and a storage notification that don't exist | 5-10 docs | |

## 11. What in the review is wrong or stale at `97a18f5`

- **R-40 "render blank forever"**: no list renders these rows (the Collections tab shows only
  bookmarks); the blank appears in the photo viewer's placeholder, and in the unreachable
  `local_col_` grid. The worse fact is N1: correct downloads are not shown either.
- **R-37** is fully fixed (Phase 0 worker, 4-11 Coil); the controller's own clients never cached.
- **R-41 "site disconnect"**: files kept on disconnect match collections being kept per site; Q8
  keeps that on purpose.
- **Phase 5 row "album download moved into the worker"**: it is, but Q1 asks whether a gallery
  should be downloaded at all on bookmark, since R-49 makes that happen on dialog open.

## 12. Dependencies, and what is deferred

Must not regress (their tests stay green **unchanged** unless a step says otherwise): `UnlockManager`
(one flight per root; background never deletes; `sessionEpoch`), `AlbumLoader` (token, LRU, partial
keep: 5-9 only adds an offline fallback after page 1 fails), `Pager` (gallery listing in 5-7),
`CachePolicy` (the images factory keeps the offline rule; the synthetic 504 is classified
OFFLINE, not retried), `SiteSession` (the worker is outside it on purpose), the 4-8 lock messages,
`ManifestGuardTest` (backup exclusions), `SchemaGuardTest`. Scenario suites to watch:
`LockedAlbumTest` (its download case moves to 5-7), `ProcessDeathScenarioTest`, `SwitchSiteMidSyncTest`.

Deferred: **Phase 6**: the Save dialog writing on open (R-49), a settings switch for the network
rule, the viewer's error state for photos that are not saved (R-45), "Download photo" to the public
gallery (R-55), sharing a local file vs the web link (R-53, T10), confirm on other deletes.
**Not scheduled**: Range resume, offline video (Q7 (c)), saving folders offline.

## 13. Recorded deviations

*(Filled in during implementation: one line per deviation, with the step, what changed from this
design, and why.)*

| Step | Deviation | Why |
|---|---|---|
| 5-0 | The R-38/R-39/R-40 characterization runs on the old worker are **committed** as green, pinned-as-current-behaviour tests (`OldWorkerCharacterizationTest`, 7 cases), not left as uncommitted runs. | The suite has to stay green and the later steps need something to flip: 5-5/5-6 change these assertions to the fixed behaviour. |
| 5-0 | E1 (an emulator gallery under Family, with the old worker) is **deferred to the exit check (5-11)**. | It needs the family password on the emulator; nobody types or prints it, and the debug build holds none. The emulator has 0 collections (P9), so it also has no old-worker data to observe. |
| 5-3 | `OfflineStore` holds the file lock in each public call (a `Mutex`), `commit` is `NonCancellable`, and `fail(fileKey, failure, part)` also deletes the `.part`. `keepPartial` is not used: a `.part` is always deleted, never resumed (no Range, §12). The suite gained 16 tests, not ~9. | Design 2.4 only said "fd.sync, then one transaction"; the lock is what makes "GC never races a commit" true inside one process. More cases because each ordering rule got its own test. |
