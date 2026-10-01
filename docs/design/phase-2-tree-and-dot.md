# Design: Phase 2: tree integrity, then the dot

Status: **DRAFT, needs owner sign-off (§7) before step 2-5.** Steps 2-1 to 2-4 change no
meaning and can start before sign-off. Written 2026-09-30 by the planning agent.
Scope: review §4 Phase 2 ([whole-app review](../review/2026-09-29-whole-app-review.md)): T1
(R-01…R-09), R-22, R-36 (only for the crawl), R-35 (only for forced listings), findings #1, #4,
#5, #6, #14, #15, #16, #17 ([findings.md](../findings.md)), and the #13 housekeeping. This doc
supersedes [new-gallery-indicator.md](new-gallery-indicator.md) §3–§6; that doc's §1 (the expected
behaviour) still holds.

## 1. What the live API actually returned (2026-09-30, read-only, anonymous)

No password was used. Every row is a request I made today; shapes only, no secrets.

| # | Request | What came back | Used for |
|---|---|---|---|
| V1 | `user/idzifamily!albums?count=100&_filter=…,LastUpdated,ImagesLastUpdated,Uris&_filteruri=Folder,HighlightImage&_verbosity=1` | 119 albums, `Pages.Total=119`. 118 have `ImagesLastUpdated` (ILU), format `2026-09-28T17:30:30+00:00` (25 chars). The one without ILU or LU is the album-password gallery `zzwtrF/nvR4zT`. ILU ≥ LU in all 118. **100 of 100 have `Uris.Folder`.** No `ParentNode`. | crawl fields; "new" date |
| V2 | Same, `count=500` | `Pages.Count=100`: the page size is capped at 100, so 2,633 albums means 27 pages | cost |
| V3 | `Pages.NextPage` of V1 | Keeps `_filter`, `_filteruri`, `count`, `start`. **Drops `_expand` and `_verbosity`.** Without `_verbosity=1`, `Uris.*` become objects (`{"Uri":…,"Locator":…}`), not strings | build page URLs with `start=` and all params (R-27) |
| V4 | `Uris.Folder` vs `UrlPath` on all 119 | `Uris.Folder` = `/api/v2/folder/user/idzifamily` + the album's `UrlPath` minus its last segment, 0 mismatches. 5 root-level albums have folder path `""` (the site root). 14 distinct folders | local parent match |
| V5 | `node/4zqWw!children` (root, 20 children) | Folder nodes carry `UrlPath`, `WebUri`, `SecurityType`, **`EffectiveSecurityType`**, and `Uris.ParentNodes`. For all 15 folders, the path of `WebUri` (host `gallery.idzifamily.com`) equals `UrlPath`; 5 albums use `www.smugmug.com/gallery/n-…` short links, so they don't | folder path = `WebUri` path |
| V6 | `node/sXQz4G!parents` | 200. `Node` = **[self, parent, …, site root]**, self first: `sXQz4G` Album → `3BxbFF` Folder → `4zqWw` Folder, each with `SecurityType` | lineage and unlock root in 1 call |
| V7 | `node/P4BKB!parents` (School, anonymous) | 200: `P4BKB` (SecurityType **`None`**, EffectiveSecurityType `Password`) → `2sDN5x` (`Password`) → `4zqWw`. `node/P4BKB` and `node/2sDN5x` are readable anonymously; `!children` of both are **404** anonymously | unlock-root walk |
| V8 | `node/sXQz4G!parent` | 200, `Node.NodeID = 3BxbFF` (the parent) | confirms R-01 |
| V9 | `folder/user/idzifamily/Kentridge-Cross-Country` | 200, `NodeID 3BxbFF` | fallback only |
| V10 | 23 public galleries: node `DateModified` (DM) minus album ILU | median +27 days, max +534 days, **5 of 23 negative (DM earlier, by 22 s to 2.7 h)** | viewed-row compatibility (§7 Q2) |
| V11 | Response headers of `node/4zqWw` | `Cache-Control: private, no-store, no-cache, max-age=0`; `x-ratelimit-remaining: 99951` | the app's cache rewrite to `max-age=300` is what makes R-35/R-36 |
| V12 | Python `sqlite3` 3.45 | `datetime('2026-09-28T17:30:30+00:00')` parses; offsets normalise to UTC | SQL date compare |

**Two corrections to things we believed:**
- **`LCdk7F` / `FfHCms` is not under Family → School.** V6/`node/LCdk7F!parents` shows it is
  "KR XC at Auburn" under `3BxbFF` (public). The owner's fixture keeps those IDs as *shapes*
  (AlbumKey ≠ NodeID); the topology in tests is synthetic, and the doc says so.
- **SmugMug never sent `"Inherited"`.** Sub-folders of a password folder say `SecurityType: None`
  and `EffectiveSecurityType: Password` (V7). The app tests `== "Inherited"` in 11 places (7 files),
  so `resolvePasswordRootNodeId` stops at School and unlocks the wrong node (R-03, now explained).
  New findings row; Phase 2 fixes only the walk, the lock icons stay for Phase 3 (T3).

## 2. Who owns each datum

| Datum | Source endpoint | Table.column | Only writer(s) after Phase 2 |
|---|---|---|---|
| A node's parent | the `!children` listing of that parent | `cached_nodes.parentNodeId` | `getNodeChildren` (listing) via `replaceChildren`; `refreshSiteHeaderNode` writes NULL for the site root. **Nothing else may insert a row under a real parent.** Search rows use `'search_result'` only when no row exists. |
| A gallery's folder | `user!albums` `Uris.Folder` (path), matched to a folder row by path | `cached_albums.parentNodeId` | `IndexParentResolver` (local, §3.4) |
| "Photos changed at" | `user!albums` `ImagesLastUpdated`; `album/{key}` ILU on gallery open | `cached_albums.imagesLastUpdated` (new) | the crawl (`MAX(old, new)`), gallery open (same field, same meaning) |
| Album settings date | `user!albums` `LastUpdated` | `cached_albums.dateModified` | the crawl only. `mergeAlbumsIntoIndex` stops writing node DM into it (R-08) |
| Node DM | `!children` `DateModified` | `cached_nodes.dateModified` | listing only. **No longer read by the dot.** |
| "Viewed up to" | the gallery's ILU at the time of viewing | `viewed_gallery_updates` | `markNodeAsViewed` (gallery open, long-press) |
| Session (cookie) | `node/{id}!unlock`, `album/{key}!unlock` | in-memory cookie jar (AppModule) | `SessionUnlocker` at launch; the existing prompt paths |
| Password root of a node | `node/{id}!parents`: first entry with `SecurityType == "Password"` | not stored | `resolvePasswordRootNodeId` |
| "Is something new here?" | — | **one DAO query**, `getNodesWithActiveUpdates(nickname)` (§3.5) | — |

## 3. Design

### 3.1 T1: parents come from listings only (R-01, R-03, R-04, R-06, R-07)

1. **Delete every `Uris.ParentNode` parser** (repository 1228, 1532, 1561, 1641, 699; VM 1149,
   1384, 1397, 1465, 1486) and drop `ParentNode` from the three `_filteruri` defaults. A guard
   test greps `app/src/main` for `parentNode` usage outside `ResponseModels.kt`.
2. **`resolvePasswordRootNodeId(nodeId)`** → returns `RootResolution { Resolved(id) | NotProtected |
   Unknown }`. One `GET node/{id}!parents?_filter=NodeID,Type,SecurityType`; walk the list from
   index 0; first `SecurityType == "Password"` wins. Album-key input is mapped to its NodeID via the
   index row, else `album/{key}`. Any exception gives `Unknown`. `unlockInheritedPasswordRoot`
   on `Unknown` makes **no** unlock call and deletes nothing (R-21 stays safe offline).
3. **Lineage stops writing** (R-04): `resolveAndCacheAlbumLineage` builds the breadcrumb from
   `!parents` (excluding self and the site root) and returns in-memory `CachedNode`s. It never
   inserts or REPLACEs a row. `fetchNodeFromApi` returns a node and no longer inserts one. The
   VM's five lineage walks call a new `repository.lineageOf(nodeId)` (same `!parents` call).
   Search (`searchNodesRemote`) inserts only rows that don't exist yet, with `'search_result'`.
   With these, a listing is the only way a row lands under a real parent, so R-06's "one stray
   row looks like a full listing" can't be created any more; existing strays are removed by §3.6.
4. **`replaceChildren(parentId, rows)`** (`@Transaction`): delete that parent's children not in
   `rows`, then insert (R-07). Used by every `getNodeChildren` network success.
5. **Forced listings bypass the HTTP cache** (the R-35 part we need): `getNodeChildren` and
   `getUserAlbums` gain `@Header("Cache-Control") cacheControl: String? = null`; `forceRefresh`
   and the crawl send `no-cache`. Offline, `offlineFallbackInterceptor` overwrites it with
   `only-if-cached` (`.header` replaces), which gives the synthetic 504 of §3.7.

### 3.2 Session unlock at launch (R-22, findings #16)

`SessionUnlocker.unlockSavedRoots(nickname): UnlockSummary`, in the repository layer:
1. For each saved key, find its password root **offline**: walk cached parents (visited set, depth
   ≤ 32) to the nearest row with `access == "Password"`; an album key with `access == "Password"`
   is its own root. If nothing is cached, call `resolvePasswordRootNodeId` (1 request). Dedupe.
2. Unlock each distinct root once, sequentially, 400 ms apart: Folder → `node!unlock`, album →
   `album!unlock`. **No "already unlocked" skip:** having a saved password is not a session.
3. Outcomes go to the existing `LaunchUnlock` run (`attempted`, each `UnlockAttempt`), plus
   `notes = "roots=N ok=a rejected=b transient=c"`. **Launch never deletes a password**, even on 401:
   it can't ask the user. The next navigation into that folder hits the existing prompt path.
4. `unlockAllSavedPasswords()` is no longer called from the VM `init`; `SiteSync` (§3.7) calls the
   unlocker first, so the crawl and tree sync run with the cookie.

### 3.3 The crawl (findings #14, #15, #16; R-27, R-36)

`GalleryCrawl.run(nickname)` replaces the body of `buildInMemoryGalleryCache`:
- **Gate:** skip if a crawl for this nickname *fetched every page* less than 15 min ago
  (`sync_state` SharedPreferences: `lastFullCrawlAt.<nick>`). The persisted index is still
  published at once, as today.
- **Pages:** `start = 1, 101, …` built from the typed `getUserAlbums(start=…)` call, so `_expand`
  and `_verbosity=1` are on every page (V3). Stop only when `start > Pages.Total` or a page is
  empty. No `SortMethod`/`SortDirection`/`Order`. `Cache-Control: no-cache` (V11, R-36).
  `_filter` adds `ImagesLastUpdated`; `_filteruri=HighlightImage,Folder`.
- **All or nothing:** pages accumulate in memory (2,633 rows is small). Any failure (IO, synthetic
  504, 429 after retries, parse) aborts the run: nothing is written, the gate isn't stamped, and
  `stop = Error/Offline`. One `db.withTransaction` writes everything at the end.
- **Upsert:** for each album, `imagesLastUpdated = MAX(existing, crawled)` (ILU never moves back),
  `dateModified = LastUpdated`, `parentNodeId` kept (the resolver owns it).
- **Prune (meaning change, §7 Q4):** only when the run is *complete*: every page fetched **and**
  every root in the `UnlockSummary` returned Success. Then index rows of this nickname not seen are
  deleted, **unless** that is more than `max(20, 5%)` of the index; then the prune is skipped and
  reported (`pruneSkipped=N`), which guards against an expired session listing only 119.
- **After the write:** `IndexParentResolver` (§3.4), then relist the folders that hold *changed*
  galleries (new to the index, or ILU moved) and already have a cached listing, at most 30
  folders, with `forceRefresh`. This replaces `deleteNodesByParent` (it orphaned subtrees and
  never ran, R-05). Returns the relisted folder ids so the VM can reload the folder on screen.
- **Report:** existing fields, plus `notes = "complete=… pruned=… pruneSkipped=… parentsResolved=…
  unresolved=… relisted=…"`.

### 3.4 `IndexParentResolver` (R-05)

Local and cheap. Map `cached_nodes` folder rows of this nickname by path (the `WebUri` path,
V5; `""` for the site root row). For each index row, parent path = `Uris.Folder` minus
`/api/v2/folder/user/{nick}` (stored transiently during the crawl; for rows not in this crawl, the
`urlPath` minus the last segment, V4). Match → write `cached_albums.parentNodeId`. No match → leave
NULL and count `unresolved`. For unresolved **recent** galleries (ILU ≤ 30 days) only: relist the
nearest matched ancestor path (forced) and retry, depth ≤ 8, within the same 30-relist budget.
It runs after the crawl and again after the tree sync. It never calls `folder/…` (V9 stays a
manual fallback; the tree sync already lists every reachable folder).

### 3.5 The dot: one query owns it (findings #1, #4, #5, #15, #17)

```sql
WITH RECURSIVE active(nodeId, parentNodeId, d) AS (
  SELECT a.nodeId, COALESCE(a.parentNodeId, n.parentNodeId), 0
  FROM cached_albums a
  LEFT JOIN cached_nodes n ON n.nodeId = a.nodeId
  LEFT JOIN viewed_gallery_updates v ON v.nodeId = a.nodeId
  WHERE (a.nickname = :nickname OR a.nickname = '')
    AND a.imagesLastUpdated IS NOT NULL
    AND datetime(a.imagesLastUpdated) >= datetime('now', '-30 days')
    AND (v.lastViewedDateModified IS NULL
         OR datetime(a.imagesLastUpdated) > datetime(v.lastViewedDateModified))
  UNION
  SELECT p.nodeId, p.parentNodeId, a.d + 1
  FROM cached_nodes p JOIN active a ON p.nodeId = a.parentNodeId
  WHERE a.d < 32
)
SELECT DISTINCT nodeId FROM active
```
- Seeds come **only** from `cached_albums`; folders only bubble. Folder DM is never read (#2, #17).
- The result is NodeIDs. The VM exposes it via `activeNickname.flatMapLatest`, so dots are per site.
- `markNodeAsViewed(nodeId)`: a gallery → `viewed = its index ILU`. A folder (long-press) → one
  `@Transaction` that walks down over both edges (`cached_nodes` children and `cached_albums` by
  `parentNodeId`, `UNION`, depth ≤ 32) and writes `viewed = ILU` for every index gallery below.
- **Gallery open:** `getAlbum` adds `ImagesLastUpdated` to `_filter`; on success, update the index
  row's ILU (`MAX`) and write the viewed row with that ILU. Fresh truth beats a 15-min-old crawl.
- **Home dot (#5):** `HubAlbumItem` gets `nodeId` (from `AlbumDetails.nodeId`, or the fallback
  node's `nodeId`); `HomeTabView` checks `album.nodeId in activeUpdates`. NodeID everywhere: it is
  what the tree, the viewed table and the dot set use; AlbumKey is only an API handle.
- The doctor's `recent_index_invisible_to_dot` is redefined: recent unviewed ILU rows whose
  `COALESCE(a.parentNodeId, n.parentNodeId)` is NULL. The `DiagnosticsModule` hook passes the site
  root id (closes the 1b `site_root_row` gap).

### 3.6 Repair of caches that already hold bad rows

1. **In migration 15→16** (§4): delete `cached_nodes` rows whose parent is themselves or contains
   `!` (`self_parent`, `bang_parent`); set `cached_albums.parentNodeId = NULL` everywhere (every
   non-NULL value came from the disproven parse, e.g. the emulator's 2,608). Deleting cache rows
   loses nothing the network can't refill; user tables are untouched.
2. **One relist per site** (R-04's damaged listings can't be found locally): after the first
   launch on v16, `SiteSync` runs the tree sync once with `forceRefresh = true` (flag
   `treeRepairDone.<nick>` in `sync_state`, set only when the walk finishes without error, so an
   offline or killed run retries next launch). Cost: one `!children` per reachable folder, 200 ms
   apart, in the background. `replaceChildren` drops stale children on the way.
3. Orphans (`orphan_rows`) are left: unreachable rows are harmless, and the doctor keeps counting.

### 3.7 Offline: the synthetic 504 (findings #6)

- `internal fun Response.isSyntheticCacheMiss() = code == 504 && networkResponse == null &&
  cacheResponse == null` (in `data/api`, no `diag` dependency).
- `RetryingCallFactory.shouldRetry` returns false for it; `onFinalResponse` is still called, and
  the AppModule toast lambda skips it (`shouldShowToast` gets the response, not just the code).
- `SmugMugErrorMapper.userMessage(error)`: a synthetic 504 `HttpException` → "You're offline, and
  this hasn't been opened on this device yet." `loadFolderContents` and the gallery load use it for
  `BrowserUiState.Error` instead of `localizedMessage`. Other screens: Phase 6 (T7).

### 3.8 Launch order: `SiteSync`

`loadUserProfile`/`selectSite` launch one job (cancelled and replaced on site switch; full
scoping is Phase 3): **unlock (§3.2) → crawl (§3.3, may be gated) → tree sync (forced once for
repair, else cache-first) → resolver (§3.4) → reload the shown folder if it was relisted.**
`loadFolderContents(root)` and the Hub load still start at once in parallel (UI first).

## 4. Migration 15 → 16 (one-way; needs sign-off, §7 Q1)

```kotlin
val MIGRATION_15_16 = object : Migration(15, 16) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE cached_albums ADD COLUMN imagesLastUpdated TEXT")
    db.execSQL("DELETE FROM cached_nodes WHERE parentNodeId = nodeId OR instr(parentNodeId, '!') > 0")
    db.execSQL("UPDATE cached_albums SET parentNodeId = NULL")
  }
}
```
`CachedAlbum` gets `val imagesLastUpdated: String? = null`; `version = 16`; `16.json` exported;
`AppModule.addMigrations(…, MIGRATION_15_16)`. No index needed (seed scan is per nickname, ≤ 3k
rows; the 1b-1 5k-row doctor run was < 1 s). After upgrade every ILU is NULL, so **no dot shows
until the first crawl completes** (seconds after launch, online).

**MigrationTest plan** (Robolectric, `MigrationTestHelper`, schema assets as in 1c-1):
- `migrate15To16_addsIlu_repairsTree_keepsUserData`: a v15 DB with root `4zqWw` (parent NULL),
  `2sDN5x` (Folder, `Password`, parent `4zqWw`), `P4BKB` (Folder, `None`, parent `2sDN5x`), gallery
  node `LCdk7F` (parent `P4BKB`, DM now−2 d), plus a self-parent row and an `X!parent` row; index row
  `FfHCms`/`LCdk7F` with `parentNodeId='P4BKB!parent'`; and rows in all five user tables. After
  `runMigrationsAndValidate(16, true, MIGRATION_15_16)`: the 4 good nodes remain, the 2 bad ones are
  gone, the index row survives with `imagesLastUpdated` NULL and `parentNodeId` NULL, every user row
  is byte-equal. **Red check:** comment out the `DELETE` → fails on the bad-row count; comment out
  the `ALTER` → `Migration didn't properly handle: cached_albums`.
- `migrate13To16_keepsUserData` (chain all three) and the Room-open test re-pointed to v16.
- `SchemaGuardTest` passes unchanged (it already demands `16.json` and the registration).

## 5. Failure modes: what the user sees

| Situation | Behaviour | What the user sees |
|---|---|---|
| Offline, nothing cached (fresh install) | Unlock POSTs fail → `Transient`, nothing deleted. Crawl page 1 → synthetic 504, **no retries**, run aborts `Offline`, gate not stamped. Folder load fails at once. | Folder screen: "You're offline, and this hasn't been opened on this device yet." No toast, no 7.5 s wait. No dots. |
| Offline, cache present | Listings come from Room. The crawl aborts at once; the index and dots are unchanged. | The last known dots; folders browse as before. |
| Locked gallery, password never saved | Absent from the session listing, or present without dates (V1, `zzwtrF`): ILU NULL → never new. | Lock icon, no dot. Same as today. |
| 429 mid-crawl | `RetryingCallFactory` honours `Retry-After`, 5 tries. Exhausted → abort, nothing written, gate not stamped. | Old dots; the next launch retries. |
| Sync racing a screen | One transaction at the end; the dot is a Room `Flow`. A relisted folder on screen reloads (existing hook). Gallery open during a crawl: ILU is `MAX`-merged, so the crawl can't move it back. | Dots change once, when the crawl lands; no flicker to empty. |
| Process death mid-crawl / mid-repair | Nothing committed; gate and `treeRepairDone` not stamped; the report shows `Interrupted`. | Next launch redoes it. |
| Wrong or changed saved password | Launch unlock → 401 → `Rejected` in the report, password **kept**, crawl incomplete → no prune; that subtree's rows stay as last synced. | Its dots freeze at the last good crawl (they still age out at 30 days and clear on view). Opening the folder prompts as today (401 path deletes and asks). |
| One root's unlock fails (transient), others succeed | Others' galleries update; the failed root's rows are untouched; no prune. | Dots correct everywhere except under that root, which shows the last good state. |
| Session expires mid-process (lifetime unverified) | The next crawl is the next launch, with a fresh unlock just before it. If it ever lists far fewer albums, the prune guard skips the delete. | Nothing visible. |
| New folder created on SmugMug since its parent was listed | The resolver can't match it; the forced relist of the nearest matched ancestor finds it (bounded). | Dot appears after that relist, in the same launch. |

## 6. Steps (Sonnet, one commit each; full suite + `BUILD SUCCESSFUL` after each)

Fixture shared by all tests ("F"): `TestDb.inMemory()`; root `4zqWw` (parent NULL, WebUri
`https://gallery.idzifamily.com`) → `2sDN5x` (Folder, SecurityType `Password`, WebUri
`…/Family`) → `P4BKB` (Folder, SecurityType **`None`**, WebUri `…/Family/School`) → gallery NodeID
`LCdk7F`, AlbumKey `FfHCms`, UrlPath `/Family/School/2026-09-01--New-School-Year`, `Uris.Folder`
`/api/v2/folder/user/idzifamily/Family/School`, ILU = now−2 d, LU = now−6 d, node DM = now−2 d+10 s;
plus a public control `3BxbFF` → `sXQz4G`/`N74KSK`. Dates via `OffsetDateTime.now()` (findings #9).
API fakes use the `createMockApi` interceptor with V1/V6 shapes (`_verbosity=1` strings, no
`ParentNode`, `!parents` self-first). The session is a **cookie**: the fake server returns
Family's galleries only when the request carries the `Set-Cookie` value from its `!unlock` (R-68).
Acceptance runs on the emulator with the debug build over the old cache (8 saved keys), reading
`report.txt` (`adb pull`) and, where noted, the DB pulled with `run-as … cat databases/smugview_db`.
Rollback for every step before 2-5 is `git revert`. From 2-5 on, an emulator that ran v16 needs an
uninstall to go back (Room can't downgrade); the phone is unaffected because nothing is published
before Phase 3 ends *(owner, 2026-09-29)*.

| Step | Work | Failing-first test (run red, say how) | Acceptance |
|---|---|---|---|
| 2-0 | Findings rows: "Inherited" never sent (V7); LCdk7F's real parent; DM vs ILU (V10); NextPage drops `_verbosity` (V3) | — | rows in findings.md |
| 2-1 | #6: `isSyntheticCacheMiss`, no retry, no toast, `userMessage` | `RetryingCallFactoryTest`: a loopback server + real `Cache` + `only-if-cached` on an uncached URL → `codes=[504]`, 1 attempt, `onFinalResponse` sees it, toast predicate false. Red today: `codes=504,504,504` (seen in 1a-5). `SmugMugErrorMapperTest` for the message. | Emulator in airplane mode, fresh data: opening an unvisited folder shows the message in < 1 s; `report.txt` http line `SYNTHETIC_504 tries=1` |
| 2-2 | `resolvePasswordRootNodeId` via `!parents` + `RootResolution` | Repo test on F: root of `P4BKB` is `2sDN5x` (red: today returns `P4BKB`, because School is `None`, not `Inherited`); root of `LCdk7F` is `2sDN5x`; a 503-then-IO `!parents` gives `Unknown` and **no** `!unlock` request and no password removed (assert on the fake server's request log and `FakePasswordStore`). | Emulator: open School cold (no session) → `report.txt` unlock attempt `target=2sDN5x Success` |
| 2-3 | Remove all `ParentNode` parsers; lineage/`fetchNodeFromApi`/search stop inserting under real parents; `lineageOf` | Repo test on F: `resolveAndCacheAlbumLineage("FfHCms")` with the `!parents` fake leaves `cached_nodes` byte-equal (red: today writes `P4BKB!parent`-style rows, R-04); search result for an existing listed gallery keeps its `parentNodeId` and `sortIndex`. Guard test: no `parentNode` reads in `app/src/main` outside `ResponseModels.kt`. | Doctor after browsing via Search/Home: `self_parent=0 bang_parent=0` (new rows) |
| 2-4 | `replaceChildren` + `Cache-Control: no-cache` on forced listings | DAO test: listing `P4BKB` = [`LCdk7F`, `x2`], then [`LCdk7F`] → `x2` gone (red: REPLACE keeps it, R-07). Loopback test: two forced fetches within 5 min both reach the server (red: second is CACHE, R-35). | Emulator: pull-to-refresh on a folder shows `NETWORK` in `report.txt` |
| 2-5 | **Migration 15→16** (sign-off Q1) | §4 MigrationTest, red as described | Emulator upgrade over the old cache: app opens; doctor `self_parent=0 bang_parent=0 index_parent_set=0` (was 2,608) |
| 2-6 | One-time forced relist (`treeRepairDone`), tree sync after unlock | Repo/VM test: flag unset → every reachable folder fetched once with `no-cache` and the flag set; a 504 midway leaves it unset; flag set → no forced fetch. Red: no such behaviour. | `report.txt`: one `FolderTreeSync` run (record it; 1b gap) `forced=true complete=true`; second launch `forced=false` |
| 2-7 | `SessionUnlocker` + `SiteSync` order (unlock before crawl) | VM/repo test on F with keys `2sDN5x` and `LCdk7F` saved: exactly one `node/2sDN5x!unlock`, `skippedAlreadyUnlocked=[]`, and the crawl's first request carries the cookie (red: `launchUnlock_recordsSkippedKeys` shows 0 calls; that test is rewritten to the new truth). A 401 keeps the password. | `report.txt` `LaunchUnlock savedKeys=8 attempted≥1` with `2sDN5x Success`, before the `GallerySync` start |
| 2-8 | Full crawl (§3.3), ILU, Folder, gate, all-or-nothing, prune guard; drop `SortMethod`/`SortDirection`, `getLatestAlbumDateModified`, `deleteNodesByParent` use | Repo tests: 3 pages where the newest gallery is on page 3 and page 1 is "known" → all 3 fetched (red: today `ReachedKnown page=1`, findings #14); page-2 URL has `_expand` and `_verbosity=1`; 429 on page 2 → nothing written; a run listing 119 after 2,633 skips the prune; gate: a second call within 15 min makes no request. | `report.txt` `GallerySync pages=27 albumsSeen≈2633 stop=NoNextPage complete=true`; second launch within 15 min: no `GallerySync` HTTP lines |
| 2-9 | `IndexParentResolver` + changed-parent relist (≤ 30) | Repo test on F: after the crawl, `FfHCms.parentNodeId == P4BKB` from the path match; a gallery in an unlisted new sub-folder `/Family/School/New` triggers one forced relist of `P4BKB` then resolves. Red: `parentNodeId` NULL. | Doctor `recent_index_invisible_to_dot` (new definition) = 0 or explained |
| 2-10 | Dot query (§3.5), `markNodeAsViewed` on ILU, gallery-open ILU, nickname scope, doctor redefinition | `CollectionDaoTest` dot tests rewritten on F: ILU now−2 d lights `LCdk7F`, `P4BKB`, `2sDN5x`, `4zqWw` with **no** `cached_nodes` row for `LCdk7F` (red: today needs the node row, findings #4); viewed = ILU clears the whole chain; ILU moves forward → relights; a folder bulk-bumped DM lights nothing (#2 kept); site B's gallery lights nothing on site A. The 5 existing dot tests that seed from `cached_nodes` are replaced, not deleted silently (listed in the commit). | Pulled DB on the emulator: the query returns `2sDN5x` and `P4BKB`; screenshot Home → Family → School shows dots; open "New School Year" → its chain clears and nothing else does |
| 2-11 | Home dot by NodeID (#5) | VM test: a `HubAlbumItem` from `AlbumDetails(albumKey="FfHCms", nodeId="LCdk7F")` is dotted when the set holds `LCdk7F` (red: today compares `FfHCms`). | Home "Featured" shows the dot on a recent unviewed gallery |
| 2-12 | Device acceptance (§8) | — | §8 numbers recorded in findings.md |
| 2-13 | #13 housekeeping: `git mv .agents/skills/<x>` → `.claude/skills/<x-with-hyphens>/SKILL.md` (Claude Code names allow `a-z0-9-`, not `_`), `view_file` → Read, add `disable-model-invocation: true` to `publish-app` and `deploy-app`; trim `.agents/AGENTS.md` sections "Antigravity Tool Usage Learnings", "Antigravity Workspace Tool Boundaries" and the legacy brain-path notes (lines 56–62, 104–117). `.agents/resources/*.yaml` stay; links updated. | — (docs only) | `/` skill list in Claude Code shows the six skills |

## 7. Needs owner sign-off (recommended answer first)

| Q | Choice | Recommended | Why it's one-way or meaning-changing |
|---|---|---|---|
| Q1 | **Migration 15→16** exactly as §4: add `imagesLastUpdated`, delete self/`!` parent cache rows, null `cached_albums.parentNodeId` | **Yes** | Room can't downgrade; deleted rows are cache only (refilled online) |
| Q2 | Old viewed rows (recorded with node DM) are kept and compared to ILU | **(a) keep, accept a one-time re-light** of viewed galleries whose DM was earlier than ILU and whose ILU is within 30 days (V10: 5 of 23 samples, by ≤ 2.7 h). (b) Grandfather: on the first crawl after upgrade, set viewed = ILU for every gallery that wasn't lit under the old rule. | (b) adds a one-off code path that can itself be wrong; (a) is bounded and visible |
| Q3 | "New" = `ImagesLastUpdated` within 30 days and later than viewed; folders only bubble | **Yes** (approved in principle 2026-09-29) | Album settings edits (LU) no longer light a dot |
| Q4 | Prune index rows not seen in a *complete* crawl, with the 5% guard | **Yes** | A gallery deleted or moved out of reach on SmugMug disappears from search/Home; today it stays forever |
| Q5 | Crawl cadence: at each launch/site select, skipped if a full crawl of that site finished < 15 min ago; no periodic background crawl | **Yes** (approved 2026-09-29) | ~27 calls per crawl for this site |
| Q6 | Launch unlock re-unlocks every saved password root and **never deletes** a password, even on 401 | **Yes** | Today a 401 on the prompt path deletes; launch would silently drop credentials otherwise |
| Q7 | One-time forced relist of every reachable folder per site after the upgrade | **Yes** | One `!children` per folder once (~tens to a few hundred calls, background) |
| Q8 | Dots are per active site (today the query is global) | **Yes** | A multi-site user no longer sees site A's dots on B |
| Q9 | Home "Featured Galleries" may now include unlocked-folder galleries (the hub's `user!albums` call carries the session) | **Accept**, and note it | Visible content change on Home |

## 8. Phase 2 exit check (emulator first, then the phone at 1b-6)

From `report.txt` after a launch on v16 with the 8 saved keys: `self_parent=0`, `bang_parent=0`,
`two_cycle=0`; `LaunchUnlock` shows `2sDN5x Success`; `GallerySync complete=true`, `albumsSeen`
within 1% of `Pages.Total`; `newInIndex30d` > 0 and `litDotNodes` ≥ `newInIndex30d` (galleries plus
their folders); `recent_index_invisible_to_dot = 0`. On screen: Home → Family → School lit; opening
the School gallery clears only its chain. Airplane mode: an unvisited folder shows the offline
message at once, with no toast.

## 9. Verified vs assumed

| Claim | Status |
|---|---|
| ILU present on public albums; page cap 100; NextPage drops `_expand`/`_verbosity`; `Uris.Folder` ↔ `UrlPath` | verified V1–V4 |
| `!parents` is self-first with `SecurityType`, and works anonymously through Family for a folder (`P4BKB`) | verified V6, V7 |
| Folder `WebUri` path = `UrlPath` | verified for 15 root folders and `P4BKB`; assumed deeper |
| Session listing returns 2,633 albums incl. School with ILU | verified earlier (findings #15, #16); not re-run (no password used today) |
| Session listing gives `Uris.Folder` for Family galleries | assumed (same endpoint and filter) |
| `!parents` works anonymously for a *gallery* inside Family | assumed (folder verified) |
| An album-password gallery (`zzwtrF`) gets ILU after `album!unlock` | **unverified** (its password isn't available) |
| Session cookie lifetime ≥ one app process | unverified; the design re-unlocks right before each crawl |
| Distinct folder count in the session listing (relist budget) | unknown; anonymous is 14 for 119 |
| OkHttp synthetic 504 has no network and no cache response | verified by 1a-5's telemetry test |
| 429 `Retry-After` behaviour of SmugMug | not tested live (would need load) |
| How many bad rows the phone's cache holds | unknown until the phone report (1b-6) |
