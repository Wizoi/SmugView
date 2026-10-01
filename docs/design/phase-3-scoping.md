# Design: Phase 3: scoping (T2) and one unlock owner (T3)

Status: **DRAFT, needs owner sign-off (§7) before step 3-2.** Steps 3-0 and 3-1 change no meaning
and can start before sign-off. Written 2026-09-30 by the planning agent, read-only, against `main`
at `6725e08`, re-checked at `28c64c3` (2-12 acceptance passed; 2-14 `725afd7` added the runtime-unlock crawl,
see §3.6).
Scope: review §4 row "3 — Scoping" ([whole-app review](../review/2026-09-29-whole-app-review.md)):
T2 (R-10…R-20) and T3 (R-21…R-26, except R-23 = Phase 4 and R-25 = see §7 Q10), findings #19 (the lock
icons). No Room migration, no new module, no MVI framework. The UI keeps calling the same
`SmugViewModel` members; the work moves out of the ViewModel behind them.

Exit criterion (review §4): scenario tests for **switch site mid-sync; open B while A streams;
back while a child loads; double tap; process death**. This design adds three more that share
the same harness: **unlock racing a crawl; wrong password on one of several roots; 429.**

## 1. What the code does today (`6725e08`, see the 2-14 offsets below)

Line numbers are at `6725e08`; the review cites older ones. `VM` = `ui/viewmodel/SmugViewModel.kt`
(2,132 lines), `Repo` = `data/repository/SmugMugRepository.kt` (2,229 lines). **2-14 (`725afd7`)
shifted them:** VM lines 470-1199 are +2 and lines ≥ 1200 are +15. Repo lines < 671 are unchanged,
671-975 are +14 and ≥ 976 are +26. Every citation also names the function, so search by name.

### 1.1 One activity-scoped ViewModel holds every screen

`MainActivity.kt:82` creates one `SmugViewModel` for the whole NavHost, and every destination gets
the same instance. Besides five delegating controllers (Cast, Search, Tag, SiteHub, Collections),
the VM itself owns: the site lifecycle (`loadUserProfile` 638, `selectSite` 685, `disconnectSite`
748); folder navigation (334-340, 783-949, 1909-1932); the password prompt and password lookup
(346-349, 591, 1008-1280); the gallery loader, tag filter and sort (351-377, 1307-1639); the
photo-detail/EXIF caches (380-386, 1671-1859); URL-path helpers (1953-2066); the site sync
(2100). It has no `SavedStateHandle`. A grep of `app/src/main` finds no `SavedStateHandle` and no
`rememberSaveable` anywhere.

### 1.2 Who mutates "where am I" (R-13)

Four independent holders: `_browserState` (listing, 334), `currentFolderId` (337),
`folderNavigationStack` (340), and `savedFolderStateBeforeSearch` (847), plus `_activeTab` (270).

| Writer | What it changes | Problem |
|---|---|---|
| `loadUserProfile` 658-662, `selectSite` 716-727 | id + stack cleared + listing | runs when the profile resolves, not when B is tapped |
| `loadFolderContents` 783-846 | listing; on 401 also pops the stack (808, 827) | an unscoped `viewModelScope.launch` per call, never cancelled (**R-12**) |
| `navigateToChildFolder` 896-916 | pushes **after** a suspend (901 → 909-911) | two taps → two pushes (**R-16**) |
| `navigateBackFolder` 930-949, `navigateToStackFolder` 1921, `navigateToHome` 1909 | stack + id + load | the child's load is not cancelled |
| `navigateToFolderFromSearch` 850, `navigateBack` 861-894 | saved "search back-state" | never reset on site switch (`resetPerSiteState` 421-425) (**R-15**) |
| `selectAlbum` 1384-1388 | **stack and id from the gallery lineage, no listing load** | breadcrumb ≠ listing (**R-13**) |
| `CollectionsTabView.kt:287-290` | `loadFolderContents(f.itemKey)` with **no stack change** | listing ≠ breadcrumb (**R-13**, the reverse) |
| `startSiteSync` 2104-2105 | reload if the sync relisted the shown folder | fine, but unscoped |
| `clearEntireCacheAndReload` 776-781 | loads the literal id `"root"` | no such node (left as is, Phase 6) |
| `BrowserScreen.kt:135-138` | `BackHandler(enabled = stack ≠ ∅ \|\| cameFromSearch)` on **every tab** | invisible pops on Home/Search (**R-14**) |

### 1.3 Load jobs and their lifetime (R-10, R-11, R-12, R-20)

| Job | Where | Cancelled by |
|---|---|---|
| site sync (unlock → crawl → tree → resolver) | `startSiteSync` VM:2100, `treeSyncJob` | the *next* `startSiteSync` (after B's profile resolves) or `disconnectSite`. The crawl catches `CancellationException` and returns (Repo:760-768); `withContext` then throws, so the tree walk should not start, but this is untested. |
| folder load | VM:788, one per call | nothing |
| gallery load: lineage + viewed + target photo | VM:1370 | nothing |
| gallery load: page 1 + streaming child | VM:1502, 1583 | nothing; writes `_rawPhotos`, `_availableTags`, title/style/webUri and the **shared** `_isBackgroundLoading` with no album check (**R-11**); on failure it sets `_rawPhotos = emptyList()` (1634) |
| hub dashboard | `SiteHubController.kt:86` | nothing (late A results overwrite B's Home) |
| header image | VM:522 | nothing |
| subtree index after an unlock | VM:1205, `unlockSubtreeIndexJob` | the next unlock only; **not by a site switch**; walks ≤300 folders × 200 ms |
| resync after a runtime unlock (2-14) | `resyncAfterUnlock` (VM:1203 at `725afd7`), `unlockResyncJob` | the next unlock only; **not by a site switch**; it reads `_activeNickname`/`siteRootNodeId` when it starts |
| search / tag scan | `SearchController`, `TagSearchController` | `resetPerSiteState` (good) |
| album download | `CollectionsController.kt:163-242` | nothing; shares `_isBackgroundLoading`/status with the grid (**R-20**) |
| image details | VM:1704 | nothing; results cached forever in `_imageDetailsStates` (**R-61**), never reset per site |

Other per-site state that a switch does **not** reset: `_userAlbums` (VM:1951, set by the hub,
used by `getAlbumKeyFromWebUri` and `handleAlbumLoadError`), the three detail caches,
`savedFolderStateBeforeSearch`, and `repository.albumsCache` (one global `StateFlow`, Repo:107,
overwritten by whichever crawl or merge lands last: 698, 731, 743, 973, 1158).

### 1.4 Writers that take no nickname (R-10)

The repository is a singleton with a mutable `activeNickname` (Repo:81-87), which the VM sets in
`selectSite` (704) **before** cancelling the old sync (728). Every cache writer reads it at write
time, so any A listing that lands after the switch is stamped B:

| Writer | Reads `activeNickname` | Reached from |
|---|---|---|
| `insertNodesScoped` Repo:90 | 91 | `fetchAndStoreChildren` (virtual parents) 329, `fetchAlbumsInScopeRemote` 1365, `insertNodes` 1608, `searchNodesRemote` 1967 |
| `replaceChildrenScoped` Repo:101 | 102 | `fetchAndStoreChildren` 329, so `getNodeChildren`, `syncFolderTree` (even though it *has* a `nickname` param, 999), `relistOne`, `unlockAndIndexSubtree` |
| `mergeAlbumsIntoIndex` Repo:1128 | 1131 | `fetchAndStoreChildren` 335 |
| `unlockAndIndexSubtree` Repo:1071 | report label | VM:1208 |
| `getCachedNodesForActiveSite` Repo:1396 (read) | 1397 | `getImageDetails`, `getPasswordForPhotoUrl` |

`buildInMemoryGalleryCache`, `syncFolderTree`, `unlockSavedRoots` and `GalleryCrawl.write` already
take `nickname`. Note that the harm is *wrong stamps*, not wrong IDs: NodeIDs are global.

### 1.5 Every unlock path (T3)

| Path | File:line | Reads | Unlocks | Deletes a saved password |
|---|---|---|---|---|
| Launch (Phase 2) | `unlockSavedRoots` Repo:855 | `passwordStore.all()` | each root once | never |
| Prompt: open folder | VM `navigateToChildFolder` 902, `promptPassword` 1008 | `getUnlockedPassword` | — | — |
| Prompt: gallery pre-flight | VM `checkAndNavigateToAlbum` 951-1006 (up to 3 s) | `isNodeUnlocked` + `getUnlockedPassword` | — | — |
| Prompt: submit | VM `submitPassword` 1101 → `apiTestFetch` 1172 | — | `unlockNode` / `verifyAlbumPassword` (Boolean) | — ; **writes copies** under the prompt node, the target and every cached descendant (1118-1150, R-26) |
| Folder load 401 | VM 802-818 | — | — | `nodeId` key (804) |
| Gallery load error | VM `handleAlbumLoadError` 1051 | — | — | album key + stack node (1056, 1064) |
| Gallery locked | VM `selectAlbum` 1531-1549 | — | — | — |
| Image details | VM `getImageDetails` 1787-1792 | 3 lookups | `unlockAlbum ‖ unlockNode` | — ; writes a copy (1792) |
| Password walk | VM `getUnlockedPassword` 1221, `inheritedPasswordFor` 1255 | store + `lineageOf` (network) | — | — ; **writes copies** (1262-1265) |
| Read retries on 401 | Repo 266-272, 349-350, 423-424, 438-439, 599-600 | caller's password | `unlockInheritedPasswordRoot` / `unlockAlbum` | via 1846-1908 (root + key, on Rejected only) |
| Tag scan / tag images | `TagSearchController.kt:323-326, 485-488` | `getUnlockedPassword` | `unlockInheritedPasswordRoot` | same (1898-1908) |
| Lock icon | VM `unlockedNodeIds` 153-188, `isNodeUnlocked` 207 | saved keys + `albumsCache` | — | — |
| "Is it protected?" | VM 902, 985, 1532; `FoldersTabView.kt:236`, `HomeTabView.kt:935`, `SearchTabView.kt:578`, `BrowserScreen.kt:582`, Repo 1328 | `access == "Password" \|\| "Inherited"` | — | — |

So "saved", "session" and "invalid" are never represented: `isNodeUnlocked` means "a password is
saved" (R-22), there is no record that a session exists, and nothing remembers a 401 except by
deleting. Nothing is single-flight: a folder load, a tag scan and image details can each POST
`!unlock` for the same root at once (it matters under 429). `"Inherited"` is never sent by SmugMug
(findings #19), so 7 of those checks are half-dead. `BrowserScreen.kt:368-378` navigates after a
submit only if the **prompt** node is not a folder; when the prompt is the root folder (from Search
or Home), the gallery never opens, but `submitPassword` → `selectAlbum` already marked it viewed
(**R-24**).

### 1.6 Process death today (R-17)

`rememberNavController` saves the route back stack (library behaviour, *assumed*, §8), so after a
kill the app reopens on e.g. `photo_grid/FfHCms/...`, and `PhotoGridScreen.kt:77-79` reloads the
gallery. The VM is new: `init` (611) reads `active_nickname` from prefs and loads the **root**;
the folder stack, the tab, the search query and results and the tag selection are gone. The
breadcrumb in a restored grid is rebuilt from the lineage. A restored `search_photo_detail` or
`keyword_photo_detail` opens on an empty list (blank screen). Sessions are gone too (in-memory
cookie jar), and only the next site sync re-unlocks.

## 2. Who owns each datum after Phase 3

| Datum | Only owner | Lifetime |
|---|---|---|
| Active site (nickname, root id) | `SiteSession` (§3.1), created by `selectSite`/launch, closed by switch/disconnect | until switch |
| Nickname stamped on a cache row | the **caller's explicit `nickname` argument** | — |
| Folder stack + listing + "back to search" | `BrowserNavigator.state` (§3.2) | per site session; saved in `SavedStateHandle` |
| Shown gallery (photos, tags, title, style, webUri, loading, error) | `AlbumLoader` (§3.3), one `AlbumState` per album key | per site session; LRU of 3 |
| Saved password | `PasswordStore`, written **only** by `UnlockManager` | disk |
| Session (cookie) exists for root R | `UnlockManager.access[R] == Session` (§3.4) | process (as the cookie jar) |
| "Saved password for R was rejected" | `UnlockManager.access[R] == Invalid` | process |
| "Does this node need a password?" | `UnlockManager.needsPassword(node)` | — |

## 3. Design

### 3.1 `SiteSession`: one scope per active site (R-10, R-15, R-61)

```kotlin
class SiteSession(val nickname: String, val rootNodeId: String?, parent: Job) {
    val scope = CoroutineScope(SupervisorJob(parent) + Dispatchers.Main.immediate +
                               DiagContext.element(DiagContext.newActionId("site")))
    fun close() = scope.cancel()
}
```
The VM holds `private var session: SiteSession?`. `selectSite` closes the old session **at tap
time** (§7 Q1), then opens one for B whose `rootNodeId` is filled when the profile resolves. Every
site-bound launch moves from `viewModelScope` to `session.scope`: the site sync, the folder and
gallery loads, the header image, the hub (`SiteHubController` takes `scope: () -> CoroutineScope`
instead of a fixed scope), search, tag scan, subtree index, image details, and `markNodeAsViewed`.
`viewModelScope` keeps only what outlives a site: cast, collections and offline downloads, and
history prefs. Per-site holders are created inside the session, so a switch drops them with no
reset list to keep in sync: `_userAlbums`, the three detail caches, the navigator state (§3.2), the
album LRU (§3.3), and the open password prompt. `resetPerSiteState` keeps calling `search.reset()`
and `tag.reset()`.

Two repository changes go with it:
- `buildInMemoryGalleryCache` rethrows `CancellationException` after recording `Cancelled`
  (today it swallows it, Repo:760-768, which the Phase 1 doc deferred to Phase 3).
- `albumsCache` becomes `albumIndex: StateFlow<AlbumIndexSnapshot(nickname, nodes)>`, and
  `albumsCacheFor(nickname)` returns empty when the snapshot belongs to another site. The readers
  are VM 156, `SearchController.kt:300`, `SiteHubController.kt:144`, and Repo 1386/1398.

A late write can't be stamped wrong any more (§3.5), and a late *publish* can't happen: the job is
cancelled, and every publish goes through `session.publish { }`, which checks `session === current`
first (cheap belt and braces for code that runs between a resume and the next suspension point).

### 3.2 `BrowserNavigator`: one state, one load job, one entry point (R-12, R-13, R-14, R-16)

A plain Kotlin class in `ui/viewmodel/`, constructed per `SiteSession`:
```kotlin
data class BrowserState(val stack: List<CachedNode>, val listing: BrowserUiState,
                        val returnToSearch: List<String>? /* stack ids before a search jump */) {
    val currentId get() = stack.lastOrNull()?.nodeId ?: rootId }
sealed interface NavIntent { Root; Child(node); Back; ToIndex(i); Refresh;
                             FromSearch(node); Reveal(galleryKey); Shortcut(nodeId) }
fun navigate(intent: NavIntent)
```
- **One entry point.** All ten writers in §1.2 call `navigate(...)`. `selectAlbum` sends
  `Reveal(albumKey)` instead of writing the stack (§7 Q3). `CollectionsTabView` sends
  `Shortcut(id)`, which builds the stack from `lineageOf(id)` (read-only). The VM keeps
  `navigateToChildFolder`, `navigateBack`, `navigateToStackFolder`, `navigateToHome`,
  `loadFolderContents(id, force)`, etc. as one-line delegates.
- **One job.** `navigate` cancels the previous navigation job, which includes its load. Latest
  intent wins. The access check (`UnlockManager.needsPassword`/`passwordFor`) runs inside the job,
  so a stale tap can't push. The listing publishes only if `state.currentId == requested` (token).
- **Idempotent push.** `Child(node)` is a no-op when `stack.last() == node` or an identical
  `Child` is pending. That fixes the folder double tap. Galleries: `checkAndNavigateToAlbum` keeps a
  pending-key guard, and `MainActivity.kt:92` (and the other `photo_grid` navigations) get
  `launchSingleTop = true`.
- **Compose compatibility.** `folderNavigationStack` (a `SnapshotStateList`) and `currentFolderId`
  stay as **read-only mirrors**, written only by `render(state)` on Main, so `BrowserScreen`,
  `PhotoGridScreen` and the 289 tests read them unchanged. `browserState` is `state.map { listing }`.
- **Back (R-14).** The `BackHandler` predicate moves to a pure `BackPolicy.enabled(tab, stackSize,
  returnToSearch, openCollection)`. It is enabled only on the Folders tab, or while a collection is
  open in Collections, where back closes it (§7 Q2).
- **Saved state (R-17).** On every `render`, write `nav.nickname`, `nav.stack` (NodeIDs,
  `ArrayList<String>`), `nav.returnToSearch`, `nav.tab` and `search.query` to the VM's
  `SavedStateHandle` (§7 Q4). On init, if `nav.nickname == active_nickname`, rebuild the stack from
  `repository.getNodeById` per id, stopping at the first missing row, then `navigate(Refresh)`
  (cache-first, so it works offline). Otherwise the restore is ignored and the app opens the root.
  Passwords, photos and results are never stored there.

### 3.3 `AlbumLoader`: per-destination gallery state (R-11, R-18, R-20, R-46 partly)

```kotlin
data class AlbumState(val albumKey: String, val photos: List<AlbumImageData>, val tags: Set<String>,
    val title: String?, val style: String?, val webUri: String?, val loading: Boolean,
    val status: String?, val error: String?, val complete: Boolean)
```
- `select(albumKey, target)` makes `albumKey` current, cancels the stream of the previous album
  (one job), and reuses a **complete** state from an LRU of 3 (back from B to A is instant; an
  incomplete one restarts) (§7 Q8).
- Every write is `update(albumKey) { ... }`, which drops the write if `albumKey` isn't the job's
  own. A's page, title or failure can't touch B, and A's 401 can't prompt on B's screen.
- The VM's `_rawPhotos`, `_availableTags`, `currentAlbumTitle/Style/WebUri`, `_albumLoadError` and
  `photosFlow` become views of `current: StateFlow<AlbumState?>` (same names, same types). The
  grid's spinner reads `AlbumState.loading/status`. `CollectionsController` keeps its own status
  flow, so the album download no longer drives the gallery spinner (R-20). The grid shows the
  download status from that flow where it does today (no visible loss).
- A stream that fails partway keeps the pages it got and sets `error` with `complete = false`
  (R-46: the grid shows a partial grid *and* the error; the retry button calls `select` with
  force). The full R-46/R-47 UI states are Phase 6.

### 3.4 `UnlockManager`: one owner, explicit states (T3: R-21, R-22, R-24, R-26, #19)

Lives in `data/repository/`. It is created by `SmugMugRepository` as `val unlocks by lazy {
UnlockManager(io = this, store = passwordStore) }`, so it is a singleton like the cookie jar, needs
no new Hilt binding, and has no dependency cycle. `SmugMugRepository` implements a 5-method
`UnlockIo` it already has the bodies for: `getNodeByIdOrKey`, `lineageOf`,
`resolvePasswordRootNodeId`, `unlockNodeResult`, `unlockAlbumResult`.

```kotlin
enum class Access { None, Saved, Session, Invalid }          // per password-ROOT NodeID
val access: StateFlow<Map<String, Access>>
suspend fun rootOf(idOrKey: String): String?                  // cached lineage, then !parents
suspend fun passwordFor(idOrKey: String): String?             // root key first, legacy copies as fallback; never writes
suspend fun ensureSession(idOrKey: String): UnlockResult      // single-flight per root (Mutex)
suspend fun submit(target: CachedNode, password: String): UnlockResult  // prompt path
fun recordLaunch(summary: UnlockSummary)                      // Phase 2 launch unlock → Session/Invalid/Saved
suspend fun needsPassword(node: CachedNode): Boolean          // access=="Password" or a cached ancestor root without Session
val sessionEpoch: StateFlow<Int>                              // +1 on every new Session
```
Rules:
- `ensureSession` returns at once if `Session`, and returns `Rejected` without a request if
  `Invalid`. Otherwise it unlocks the **root** (Folder → `node!unlock`, album root →
  `album!unlock`): `Success` → `Session`, `Rejected` → `Invalid` (password **kept**),
  `Transient` → stays `Saved`. Concurrent callers for one root share one request.
- **Deletion happens in one place**, `submit`/`forget(root)`, and only on an explicit 401/403
  while the user is in front of a prompt for that root (§7 Q5). Background paths (launch, tag scan,
  read retries, image details) never delete; they mark `Invalid`.
- `submit` unlocks the root, saves the password under the **root key only** (§7 Q5), marks
  `Session`, bumps `sessionEpoch`, starts the subtree index in the session scope, and returns the
  target. The caller then opens the **target** (R-24): a folder via `navigate(Child)`, a gallery
  via the `onNavigate(albumKey, title)` of the target, not of the prompt node.
- A read that gets 401 while `Session` marks `Saved` and calls `ensureSession` once (session
  expiry, lifetime unverified).
- Callers that move to it: the read retries (Repo 266-272, 349-350, 423-424, 438-439, 599-600 →
  `unlocks.ensureSession(id)`), `TagSearchController` 323-326/485-488, `getImageDetails`
  1787-1792, `apiTestFetch`, `getUnlockedPassword(ForNode)`, `getSavedPasswordForNode`
  (Repo:1161), `getPasswordForPhotoUrl`, `handleAlbumLoadError`, the folder-load 401 branch, and
  `runSiteSync` (`recordLaunch`). `unlockInheritedPasswordRoot` and the Boolean `unlockNode`/
  `unlockAlbum` are deleted at the end. The VM keeps `getUnlockedPassword`/`isNodeUnlocked`/
  `unlockedNodeIds` as delegates. `unlockedNodeIds` keeps meaning "saved or session" (no icon change
  for saved folders).
- **Prompt state** stays in the VM (it's Compose `mutableStateOf`), behind one
  `requestPassword(target)` that replaces the six call sites of `promptPassword`. A switch dismisses
  it.
- **`needsPassword`** replaces the 7 `"Password" || "Inherited"` checks (#19): School (`None`)
  under Family needs a password when Family has no session or saved password, so it shows a lock
  (§7 Q7).

### 3.5 Writers take an explicit nickname

`getNodeChildren(nickname, nodeId, ...)`, `fetchAndStoreChildren(nickname, ...)`,
`insertNodesScoped(nickname, ...)`, `replaceChildrenScoped(nickname, ...)`,
`mergeAlbumsIntoIndex(nickname, ...)`, `insertNodes(nickname, ...)`,
`fetchAlbumsInScopeRemote(nickname, ...)`, `searchNodesRemote(nickname, ...)`,
`unlockAndIndexSubtree(nickname, ...)`, `relistOne(nickname, ...)`, and
`getCachedNodesForSite(nickname)` all take the parameter **with no default**. Callers pass
`session.nickname`, or the `nickname` they already have (`syncFolderTree`, the crawl). The last step
deletes `activeNickname`/`setActiveNickname`, with a source guard test (like
`NoParentNodeParsersGuardTest`). `CacheDoctor` gains `cross_site_rows`, the count of `cached_nodes`
rows whose parent row has a different non-empty nickname. That measures existing R-10 damage on the
phone. The repair is a separate decision (§7 Q9).

### 3.6 Unlock racing a crawl (mostly done by 2-14)

2-14 already serializes crawls (`crawlMutex`) and, after a runtime unlock, runs
`resyncAfterUnlock`: crawl with the gate bypassed and no prune, then the tree and the resolver. One
gap is left. The launch crawl that began without the cookie still writes with `prune = true`, so
galleries the subtree index merged in mid-crawl (≤ max(20, 5%)) are deleted, and they come back
only when the resync lands. Phase 3 makes three changes:
1. The crawl reads `unlocks.sessionEpoch` at start and skips the prune if it changed by write time.
2. The resync job moves into the `SiteSession` scope, so a switch cancels it.
3. The resync is triggered by `sessionEpoch` and not by `apiTestFetch`, so a session opened by any
   path counts, not only the prompt.

None of this changes meaning, so there's no sign-off question.

## 4. Failure modes: what the user sees

"Today" is traced from code (*likely*) unless marked. A is `idzifamily` (F fixture), and B is a
second site.

| Situation | Today: resulting state | Today: user sees | After Phase 3 | After: user sees |
|---|---|---|---|---|
| **Switch A→B mid-sync** | A's sync runs until B's profile resolves. A's folder/hub/header/subtree jobs never stop. Any A listing that lands after `setActiveNickname(B)` is stamped B (§1.4). `albumsCache` = whoever merged last. `_userAlbums`, the detail caches and the search back-state stay A's | B's Folders tab can show A's root listing (R-12). B's Home "Featured" can show A's galleries. B's search finds A's folders/galleries, and A's loses them until relisted. Back after a search jump restores an A folder on B (R-15) | Session A is cancelled at tap; the crawl/tree runs end `Cancelled` in the report. Writes carry A's nickname explicitly. B starts from empty per-site holders | B's root, B's Home, B's search only. A's in-flight call (at most one per job) is discarded. Nothing of A is stamped B |
| **Open B while A streams** | A's child job flushes pages into `_rawPhotos`, sets tags, title/style/webUri, and clears the shared spinner. A's failure empties B's grid, shows A's error, and prompts for A's password (R-11). `webUri` isn't reset, so Share encodes A (R-18) | B's grid gains A's photos and tags. The spinner stops early. Possibly a blank grid with an error, or a password prompt for A. QR shares A | A's stream is cancelled. Every write is keyed by album. B's state starts empty with B's webUri | Only B's photos; B's title and link. Back to A: instant if A was complete, otherwise A reloads |
| **Back while a child loads** | The parent loads from cache and shows. The child's network result lands later and overwrites the listing (R-12). A child 401 still prompts | The breadcrumb says Family, the list shows School's galleries. Tapping one opens the wrong level | Back cancels the child job. The listing publish is token-checked | Family's listing, stable. No prompt for School |
| **Double tap a folder / gallery** | Two coroutines each suspend in `getUnlockedPassword` (DB, maybe `!parents`) and each push. Two `photo_grid` entries; the gallery pre-flight (≤3 s) widens it | Breadcrumb "Family › Family", back needs two presses. The gallery opens twice, and back shows it again | Latest-wins job and idempotent push. A pending-key guard plus `launchSingleTop` | One push, one grid |
| **Process death** (backgrounded, killed) | Routes restored (*assumed*). VM: root listing, empty stack, Folders tab, no search, no tags. Detail viewers have empty lists. Sessions gone until the next sync's launch unlock | The Folders tab is at the root instead of School. Search/Tag viewers are blank (R-17) | Stack ids, tab, back-to-search and query text restored from `SavedStateHandle`, listing cache-first. Viewers with no data navigate up (§7 Q4). The launch unlock runs as in Phase 2 | Back in School with its listing (from cache, works offline). A viewer restored without data returns to its list, and the query is still in the box |
| **Unlock racing a crawl** (as of 2-14) | Pre-cookie pages lack Family's galleries. The subtree index merges them (no ILU). The launch crawl writes with `prune = true`, so if unseen ≤ max(20, 5%) they're **pruned**. The 2-14 resync (it waits on `crawlMutex`) then re-adds them with ILU. A site switch doesn't cancel the resync | Just-unlocked galleries can vanish from search for the length of one crawl (~27 calls), then return with dots | Epoch changed: no prune. The resync runs in the session scope | Search keeps them throughout. Dots appear when the resync lands |
| **Wrong password on one of several roots** | Launch: `Rejected`, kept (Phase 2). Opening that root: a request with the stale password → 401 → `unlockInheritedPasswordRoot` deletes the root keys, the VM deletes the node key, then a prompt. **Copies** under descendants (R-26) survive, so a deep gallery opened from Search/Home 401s again and prompts again; the tag scan retries the stale copy silently | Two or more prompts for one wrong password. Other roots fine | Launch marks the root `Invalid`. Opening it prompts at once with no doomed request. A correct submit replaces the root key and drops copies with the old value (§7 Q5). Other roots `Session` | One prompt: "Saved password was rejected, please re-enter." Other roots unaffected |
| **429** | `RetryingCallFactory` honours `Retry-After`, 5 tries. Unlock → `Transient`, kept (Phase 0). Parallel paths (folder retry, tag scan, image details, launch) each POST `!unlock` for the same root, which multiplies requests | Slow loads, then "Failed to load…". Possibly more 429s from the duplicates | `ensureSession` is single-flight per root. `Transient` leaves `Saved` (no delete, no `Invalid`). The crawl aborts unstamped as in Phase 2 | Same error text (Phase 6 improves it). Fewer duplicate requests. The password is kept |

Intermediate states the user can see: while a switch is resolving B's profile, the splash shows
`Loading` (today too). A's screens are not shown, because the browser is on the Hub tab when a site
is chosen. While `navigate` resolves access (≤ one `!parents`), the old listing stays and the tapped
row shows no change (today the same).

## 5. Test harness (step 3-0)

- **Fixture F** (`FakeSmugMugServer`) is extended, keeping the real shapes and documented as
  synthetic topology (findings #20). It gains `user/{nick}` (Uris.Node → root), `album/{key}`, and
  `album/{key}!images` with real `AlbumImage`/`Pages` shapes (page 1 has `NextPage` for 150-image
  galleries). **Site B** is `siteb`: root `Rb7Tq2` → public folder `Hq2Lm9` → gallery NodeID
  `Vt4Kp8` / AlbumKey `jX9wQe`. The IDs are synthetic, but NodeID ≠ AlbumKey and they have the real
  lengths. A second password root on A (`Wq8Rz3`, `SecurityType: Password`) is added for the
  several-roots case.
- **Gates.** `server.hold(pathContains): Gate`, where `Gate.awaitArrived(5 s)` and `release()`
  block that request inside the interceptor (an OkHttp thread). Ordering is forced by gates, not
  virtual time, because OkHttp threads and the repository's hard-coded `Dispatchers.IO` (R-62) are
  outside any test scheduler. Main is `Dispatchers.setMain(newSingleThreadContext("main"))`, and
  assertions use `awaitUntil(5 s) { ... }`. A `server.respond429(path, times)` serves `Retry-After: 0`.
- **Rig.** `ScenarioRig` (Robolectric `sdk=33`, `TestDb.inMemory()`, real `SmugMugRepository` with
  `treeSyncDelayMs = 0`/`unlockDelayMs = 0`, `RecordingSyncReporter`, `FakePasswordStore`, a real
  `SmugViewModel` with the Robolectric application's prefs and an explicit `SavedStateHandle`).
  Dates are relative to now (findings #9). The existing `SmugViewModelTest` (Mockito repository)
  stays. Its `setUp` gets one line, `when(mockRepository.unlocks).thenReturn(UnlockManager(mockRepository,
  fakePasswordStore))`, in step 3-6.

## 6. Steps (Sonnet, one commit each; full suite + `BUILD SUCCESSFUL` after each)

Each scenario test is committed **green with its fix**. Red evidence is recorded in the progress log
by running it before the fix (comment the fix out, or a `git worktree` at `HEAD`, never `git
stash`). Red tests are never committed or `@Ignore`d *(owner, Phase 1)*. Rollback for every step is
`git revert` of that commit: there is no migration, and `SavedStateHandle` keys are new, so an
old build ignores them.

| Step | Work | Failing-first test (run red, say how) | Acceptance |
|---|---|---|---|
| 3-0 | §5 harness: fake routes, site B, `Wq8Rz3`, gates, 429, `ScenarioRig`; `SmugViewModel` gets a last param `savedStateHandle: SavedStateHandle = SavedStateHandle()` (Hilt supplies it) | Sanity only, green on old code: the rig opens A, the root listing shows `2sDN5x`/`3BxbFF`; a held request is observed `arrived` and completes on `release` | suite green (289 + new) |
| 3-1 | §3.5 explicit nickname on every writer (keep `setActiveNickname` for now, unused by writers); `CacheDoctor.cross_site_rows` | `ScopingRepoTest`: hold `node/P4BKB!children` in `syncFolderTree("idzifamily")`, call `setActiveNickname("siteb")`, release → `LCdk7F` row and its `cached_albums` row have nickname `idzifamily` (red: `siteb`, Repo:102/1131). Doctor test: one cross-stamped child → `cross_site_rows=1` | Emulator `report.txt` shows `cross_site_rows=<n>` (record n; the phone's n comes at its next report) |
| 3-2 | §3.1 `SiteSession`, all site-bound launches moved, per-site holders inside the session, crawl rethrows cancel, `albumIndex` snapshot. **Needs Q1** | **Switch site mid-sync** (rig, empty cache): hold A's `node/4zqWw!children` and A's `user/idzifamily!albums` page 2; `selectSite("siteb")`; release both → `browserState` lists B's `Hq2Lm9`, hub albums are B's, A's `GallerySync` run is `Cancelled`, no A request starts after the switch mark, no row with nickname `siteb` has an A id (red: listing = A's root, R-12/R-10; hub = A's). Plus: back-state after a search jump is cleared on switch (red: restores A's folder, R-15) | Emulator: open idzifamily, switch to a second public site within 2 s; `report.txt` shows A's `GallerySync`/`FolderTreeSync` `stop=Cancelled`, no `http [sync#…]` lines for A after B's `site#` id; `cross_site_rows` unchanged |
| 3-3 | §3.2 `BrowserNavigator`: one state, one job, token, mirrors, `Reveal`/`Shortcut`; `selectAlbum` stops writing the stack. **Needs Q3** | **Back while a child loads**: Family and root cached; hold `node/P4BKB!children`; `navigateToChildFolder(School)`; `navigateBack()`; release → listing = Family's children, stack = [`2sDN5x`] (red: listing = School's, R-12). R-13: open `FfHCms` from Search → stack and listing both end at `P4BKB` (red: stack `P4BKB`, listing root) | Emulator: tap School and press back at once on a cold cache (airplane off) → Family listing stays |
| 3-4 | Idempotent push, latest-wins, pending-key gallery guard, `launchSingleTop` on every `photo_grid` navigate | **Double tap**: hold `node/2sDN5x!parents` (the access walk); `navigateToChildFolder(Family)` twice; release → stack = [`2sDN5x`] (red: [`2sDN5x`,`2sDN5x`], VM:909-910). `checkAndNavigateToAlbum` twice with `album/FfHCms` held → `onNavigate` once (red: twice) | Emulator: double-tap a folder and a gallery → one breadcrumb level, one back press to leave the grid (no Compose test infra yet, R-67) |
| 3-5 | §3.3 `AlbumLoader` + LRU(3) + separate collections status. **Needs Q8** | **Open B while A streams**: `FfHCms` 150 images, hold its page 2; open `FfHCms`, then `N74KSK`; release → photos are exactly `N74KSK`'s keys, title/webUri `N74KSK`'s, spinner off only when B is done (red: A's page-2 keys present, R-11; webUri stale, R-18). Variant: A's page 2 answers 500 ×5 → B's grid unchanged, no prompt (red: grid emptied). Back to A when complete → no new `!images` request | Emulator: open a big gallery and immediately a small one → only the small one's photos; Share/QR shows the small one's link |
| 3-6 | §3.4 `UnlockManager` core: states, `rootOf`, `passwordFor` (no writes), single-flight `ensureSession`, `recordLaunch`; read retries + tag scan + image details routed to it | **429**: saved `2sDN5x`; `!unlock` answers 429 ×5; three concurrent `ensureSession` (`LCdk7F`, `P4BKB`, `2sDN5x`) → the `!unlock` POSTs are those of **one** call and its retries (red: three calls' worth, via three `unlockInheritedPasswordRoot`), password kept, `access[2sDN5x]=Saved`. Background 401 → `Invalid`, password **kept** (red: deleted by Repo:1898-1908 through the tag-scan path) | Emulator `report.txt`: `LaunchUnlock` + unlock lines show ≤ 1 attempt per root per action |
| 3-7 | §3.4 prompt path: `requestPassword`, `submit` saves the root key only, opens the target (R-24), `forget` drops same-value copies, `needsPassword` replaces the 7 checks. **Needs Q2, Q5, Q7** | **Wrong password on one of several roots**: saved `2sDN5x` (good) and `Wq8Rz3` (server 401) → launch: `Session`/`Invalid`; open `Wq8Rz3` → prompt with **no** `!children` request first (red: one 401 request, then a prompt); submit correct → one key for `Wq8Rz3`, old copies gone; `2sDN5x` untouched. R-24: from Search, open `FfHCms` with no saved password → prompt Family → submit → `onNavigate("FfHCms")` called and viewed marked once (red: no navigation). #19: `needsPassword(P4BKB)` true with no session (red: `None` → false) | Emulator: change nothing on SmugMug; type a wrong password once, then the right one → one prompt each, gallery opens |
| 3-8 | §3.6: epoch check skips the prune; resync job in the session scope, triggered by `sessionEpoch` | **Unlock racing a crawl**: cookie-gated F, no saved password, index holds the anonymous albums; hold launch-crawl page 1; submit Family's password (subtree index merges `FfHCms`); release → the first `GallerySync` run has `pruned=0` and `FfHCms` is never absent from `cached_albums` (sampled after the first write) (red at `725afd7`: `pruned=1`); then switch site during the resync → its run is `Cancelled` (red: it completes) | Emulator: unlock Family right after launch → `report.txt` shows two `GallerySync` runs, the first `pruned=0` |
| 3-9 | §3.2 saved state: write on render, restore on init; detail viewers navigate up when their list is empty. **Needs Q4** | **Process death**: navigate root → Family → School; save the handle to a `Bundle` (`savedStateProvider().saveState()`), build a new VM and repository from `SavedStateHandle.createHandle(bundle, null)` and the same DB, with the network **off** (`childrenOverride` throws IOException) → stack = [`2sDN5x`,`P4BKB`], listing has `LCdk7F`, tab Folders, query restored (red: stack empty, listing root). Variants: a missing row → root; a different nickname → root | Emulator: open School, `adb shell am kill com.smugview.app` while backgrounded, reopen → School shown |
| 3-10 | `BackPolicy` (R-14, close collection), R-19 (keep tag photos when the scope is unchanged) | `BackPolicyTest` table: Home tab + stack 2 → disabled (red: enabled, `BrowserScreen.kt:136`); Collections + open collection → enabled. `TagSearchController`: re-entering the Tags tab with the same scope keeps `allScopePhotos` (red: emptied at 460) | Emulator: Home tab, back → app backgrounds; open collection, back → closes |
| 3-11 | Delete `activeNickname`/`setActiveNickname`, `unlockInheritedPasswordRoot`, Boolean `unlockNode`/`unlockAlbum`, `_unlockedPasswords` (dead, VM:343); source guard test; findings rows for R-10…R-26 status | `NoGlobalNicknameGuardTest`: Repo source has no `activeNickname` (red on 3-10's tree) | Suite green; `assembleDebug`/`assembleRelease` BUILD SUCCESSFUL |
| 3-12 | Phase 3 exit check (§6.1) on the emulator, then the phone's next report | — | §6.1 |

Order: 3-1 → 3-2 → 3-3 → 3-4 → 3-5 are T2. 3-6 → 3-7 → 3-8 are T3, and they need 3-2's session
scope. 3-9 needs 3-3. 3-0 and 3-1 can start before sign-off.

### 6.1 Phase 3 exit check

The unit suite is green, with the five exit scenarios and the three extra ones by name. Emulator
(debug, over the old cache, 8 saved keys): the switch-mid-sync, back, double-tap, open-B-while-A
and process-death checks of steps 3-2…3-9 pass; `report.txt` has `cross_site_rows` unchanged
across a mid-sync switch, A's runs `Cancelled`, `LaunchUnlock` with ≤1 attempt per root, and no
deleted keys (header `saved password keys` unchanged) after a wrong-password run. Phone: the next
report gives the real `cross_site_rows` for Q9.

## 7. Needs owner sign-off (recommended answer first)

| Q | Choice | Recommended | Why it's user-visible or one-way |
|---|---|---|---|
| Q1 | What a site switch cancels, and when | **At tap time, cancel everything site-bound** (sync, crawl, tree, subtree index, folder/gallery loads, hub, header, search, tags, image details, the open password prompt). **Not** casting, offline downloads or album downloads | A switch now stops a running crawl (it resumes at the next select of A, gate permitting). A cast or download of A keeps going on B |
| Q2 | What system back does | **On the Folders tab: pop the folder stack** (or return to Search after a search jump). **In Collections with a collection open: close it.** **On any other tab: Android default** (app goes to background) | Today back pops an invisible stack on every tab. Alt: back from any tab goes to Folders first |
| Q3 | Opening a gallery from Search/Home/Collections moves the Folders tab | **Yes, move breadcrumb and listing together** to the gallery's folder (cache-first) | Today only the breadcrumb moves. Alt: leave the Folders tab where it was |
| Q4 | What survives process death | **Folder stack (NodeIDs), active tab, back-to-search marker, search query text.** Not results, photos, tag selection or passwords. Search/Tag viewers restored with no data return to their list | Was nothing. Alt: also re-run the search on restore (network on every restore) |
| Q5 | Password copies (R-26) and deletion | **Save under the password root only.** Read old copies as a fallback. On an explicit 401/403 **while the user is at the prompt for that root**, delete the root key and every key holding the same value. Background paths never delete | Deleting is one-way; copies created by old versions get cleaned only on a rejection. Alt: keep writing copies (today) |
| Q6 | *(dropped: 2-14 already re-crawls after a runtime unlock; Phase 3 only skips a prune, §3.6)* | — | — |
| Q7 | Lock icon on a sub-folder of a password folder (School) | **Show the lock when an ancestor root has neither a session nor a saved password** | New icon where there was none (#19). Alt: defer to Phase 6 |
| Q8 | Gallery memory | **Keep the last 3 *complete* galleries in memory.** A gallery you leave stops streaming and restarts if you return before it finished | Back to a big gallery is instant. Memory: 3 × ≤ ~10k `AlbumImageData`. Alt: no cache (reload on back, today) |
| Q9 | Rows already stamped with the wrong site | **Count only in Phase 3** (`cross_site_rows`). Decide on repair after the phone's number | A repair would delete cache rows (they refetch on browse) |
| Q10 | R-25 (plaintext fallback in `PasswordStore`) | **Out of Phase 3**: Phase 3 doesn't touch the store's internals. Schedule with T10 | Changing the storage of saved passwords is one-way |

No Room migration and no new default beyond Q1-Q5 and Q7-Q8.

## 8. Verified vs assumed

| Claim | Status |
|---|---|
| Every file:line in §1 (jobs, writers, unlock paths, BackHandler, no `SavedStateHandle`/`rememberSaveable` in `app/src/main`) | verified by reading `main` at `6725e08` |
| `selectSite` sets the repository nickname (704) before cancelling the old sync (728); writers read it at write time | verified (code) |
| `unlockSubtreeIndexJob`, hub, folder and gallery jobs are not cancelled by a switch | verified (code) |
| The crawl swallows `CancellationException`, and `withContext` then stops `runSiteSync` before the tree walk | swallow verified (Repo:760-768); the stop is *assumed* (prompt-cancellation guarantee), pinned by the 3-2 test |
| Late-landing races (R-10, R-11, R-12, R-16) actually occur | *likely* (traced); each becomes a gated red test before its fix |
| Unlock-racing-crawl prune of ≤ max(20, 5%) galleries | *likely* (traced through `GalleryCrawl.write` 122-130); red test in 3-8 |
| `rememberNavController` restores the route stack after process death | *assumed* (library behaviour); checked on the emulator in 3-9 |
| `launchSingleTop` with different args reuses the top `photo_grid` entry | *assumed*; emulator check in 3-4 |
| The owner uses more than one site (how much R-10 damage exists) | unknown until the phone reports `cross_site_rows` |
| Session cookie lifetime | unverified (as in Phase 2); `Session → Saved` on 401 covers expiry |
| The UI report the review cites for R-49 | not in the repo (only the review exists); not needed here |
| Suite size 289 | from the progress log; **not re-run** by this planner, to avoid a Gradle collision with the 2-12 emulator session (R-69) |
| Live API | not called: Phase 3 rests on no new API premise |
| 2-14's resync and `crawlMutex` behave as its commit says | read in the diff (`725afd7`); not re-run |
