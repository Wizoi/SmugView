# Design addendum: Phase 6: one network setting for saving offline

Status: **SIGNED OFF 2026-10-01: all six questions as recommended (1a, 2a, 3a, 4a, 5a, 6a).** Steps 6-N1..6-N4 run after 6-16 and before 6-17.
Written 2026-10-01 by the planning agent, read-only, against `main` at `ea2dbdc` (6-11b), with uncommitted
work by another agent in the tree. **Not re-run:** the suite and Gradle (another agent is building).
Owner decision that starts this addendum *(given, 2026-10-01)*: **replace the per-gallery "Use mobile data
too" / "Use mobile data" controls with ONE global setting, because that is how other apps do it.** This
overrides Phase 5 §8.1 (the Q3 override, the per-gallery `wifiOnly`) and Phase 6 Q15 (a) ("no global
switch"), and the §13 deferral "A Settings screen".
No Room migration (§3.3). No new dependency or permission.

## 1. What the code does today (premise check, against code)

| # | Claim | Where | Verdict |
|---|---|---|---|
| C1 | The rule lives in two columns. `offline_galleries.wifiOnly` (default 1) is the user's per-gallery choice. `offline_files.wifiOnly` is derived from it: 0 when anything that wants the file allows mobile data | `Entities.kt` `OfflineGallery`/`OfflineFile`; `OfflineDao.recomputeWifiOnlyForGallery`, `loosenWifiOnly` | confirmed (code) |
| C2 | Single photos and Image bookmarks always use any network (`wifiOnly = false`); a new kept gallery is Wi-Fi only | `OfflineCollections.savePhoto`/`addBookmark`; `OfflineStore.keepGallery` | confirmed (code) |
| C3 | Two chains. `offline-files` (CONNECTED) takes `wifiOnly = 0` rows. `offline-files-wifi` (UNMETERED) takes every row and is enqueued only while a `wifiOnly` row or gallery is wanted. Each has its own retry name | `OfflineScheduler.kick`/`after`/`nameOf`; `OfflineDao.candidates`/`galleriesToList`/`earliestRetryAt`/`countDue` (`wifiOnly = 0 OR :unmetered = 1`) | confirmed (code) |
| C4 | The pass picks one row at a time through `nextRow` → `store.candidates(...)`, so a value read inside that query is read once per file, live | `OfflineDownloader.runPass`/`nextRow` | confirmed (code) |
| C5 | **`loosenWifiOnly` is one-way.** A photo that is saved alone and is also in a Wi-Fi-only gallery becomes `wifiOnly = 0`. Removing the photo later does not set it back, so if the file is not done yet, the gallery's copy downloads over mobile data | `OfflineStore.request`; nothing recomputes on removal | *likely (code)*; test N6 makes it confirmed or not |
| C6 | UI: the Keep-offline block has a "Use mobile data too" switch and, while waiting, a "Use mobile data ({size})" button. Both call `setGalleryWifiOnly` | `OfflineRowViews.GalleryKeepOffline`; `CollectionsTabView` 418-419 | confirmed (code) |
| C7 | "Waiting for Wi-Fi" is shown only when some network exists (5-11). The flow is a Boolean of "any default network" and cannot tell metered Wi-Fi from unmetered Wi-Fi | `AppModule.networkAvailableFlow`; `OfflineRowState.summary(connected)` | confirmed (code) |
| C8 | **There is no Settings screen and no settings entry point.** The NavHost has `browser`, `keyword_images`, viewers, grid and cast. The only top-bar action is Hub's "Exit Site", and the Folders tab has no top bar. The Collections tab's top bar ("Saved Collections") has an empty `actions` slot | `MainActivity.SmugViewNavigation`; `BrowserScreen` 146-195 | confirmed (code) |
| C9 | Preferences are plain `SharedPreferences` files (`smugview_prefs`, `sync_state`, `app_prefs`, `smugview_search_status`). There is no DataStore. All `sharedpref` data is excluded from backup and device transfer | `SmugViewModel` 151; `SyncStateStore`; `data_extraction_rules.xml` | confirmed (code) |
| C10 | **No installed copy outside the emulator has a v17 database.** The only shipped build is v0.8.0 (26, internal track), which has DB **16** (`git show 754432a`). Phases 5 and 6 are unreleased. So the only per-gallery choices that exist are test rows on emulator-5554 | `AppDatabase` at `754432a` vs HEAD | confirmed (git) |
| C11 | Real sizes: one photo original is 0.7 to 4.0 MB. A gallery is about 100 MB (`N74KSK` 61 photos 103.5 MB, `FfHCms` 122 photos 95.4 MB), with a median of 86 photos and a maximum of 759 | Phase 5 P1, P7 (live) | confirmed (live, Phase 5) |

## 2. Decisions

### 2.1 What the setting means

- **Name and values.** `OfflineNetworkRule` ∈ {`WIFI_ONLY`, `WIFI_AND_MOBILE`}. "Wi-Fi only" means what
  WorkManager's `UNMETERED` means: a Wi-Fi network marked as metered (for example a phone hotspot) counts
  as mobile data. Android's own "Wi-Fi only" options work the same way. The text says so (§5).
- **Scope (Q1, recommended (a)): the setting covers kept galleries only.** Single photos and Image
  bookmarks keep downloading at once on any network. A single photo is at most about 4 MB (C11). The user
  tapped Save on that one photo, and expects it on the phone, for example at a meet with no Wi-Fi. A kept
  gallery is about 100 MB. **What changes for the user:** the per-gallery switch and button go away, and
  one choice in one place covers every kept gallery. Photos behave as today. The rule is derived per file
  at read time (§3.1). A file that a photo or an Image bookmark wants is "any network". A file that only
  kept galleries want follows the setting.
- **Default (Q2, recommended (a)): `WIFI_ONLY`**, for a fresh install and for an upgrade alike. That is
  today's default for every new kept gallery (C2), so nobody gets a default they never chose. No existing
  user has per-gallery choices (C10). The emulator's `wifiOnly = 0` test rows are ignored, and the
  setting is **not** seeded from them.
- **Persistence.** A new `OfflineSettings` interface (`rule: StateFlow<OfflineNetworkRule>`,
  `suspend fun set(rule)`), following `SyncStateStore`. `PrefsOfflineSettings` uses its own file,
  `offline_settings`, key `galleryNetwork`, and stores the enum name. An unknown value or a missing key
  reads as `WIFI_ONLY`. `set` writes with **`commit()` on `ioDispatcher`** before it returns, so a
  process death right after the tap cannot lose the choice (`apply()` can). `InMemoryOfflineSettings` is
  for tests. It is provided as a `@Singleton` in `AppModule` and injected into `OfflineDownloader`,
  `OfflineScheduler`, `OfflineReader` and `OfflineCollections`. Not Room: a setting is not data (the
  `SyncStateStore` rule).
- **Read live.** The pass reads `settings.rule.value` in every `nextRow` and `listGalleries` iteration
  (C4). The scheduler reads it in `kick` and `after`. The reader combines the flow. A change goes through
  `OfflineCollections.setGalleryNetwork(rule)`: `settings.set(rule)`, then **always** `scheduler.kick()`,
  so both directions get their chain at once.

### 2.2 Where it lives in the UI (Q3, recommended (a))

| Option | What | Cost |
|---|---|---|
| **(a)** | A settings icon (`Icons.Default.Settings`) in the Collections tab's top-bar `actions` (C8). It opens a small **"Saving for offline" dialog** with the two choices as radio rows. The choice applies on tap; "Close" dismisses. The dialog body is its own composable, so a later Settings screen can host it unchanged | No new route. It sits where kept galleries live. About 80 lines |
| (b) | A new `settings` NavHost route (a Settings screen), reached from a top-bar icon on Hub and Collections | A screen and a route for one option; the Folders tab has no top bar to put it in |
| (c) | An inline card at the top of the Collections list | Always visible, crowds the list, and scrolls away |

The kept-gallery row while waiting: the main line stays **"Waiting for Wi-Fi"** with its progress detail.
The "Use mobile data ({size})" button becomes a **"Change network setting"** text link that opens the
same dialog. A row never flips the global rule itself, because a row button would read as "this gallery
only". The "Use mobile data too" switch is deleted.

### 2.3 What stays and what goes in the data layer

- **No migration.** Both `wifiOnly` columns stay in the v17 schema and **are no longer read**, like
  `collection_photos.localFilePath` (Phase 5 Q5). Writers write constants: a gallery is `1` (the column
  default) and a file is `0`. A KDoc on both fields says "dead since 6-N1, see addendum". Dropping them
  would be a table rebuild (one-way) for no user-visible gain. There is no v17 user data to clean (C10),
  and `SchemaGuardTest`/`17.json` stay as they are.
- Deleted: `OfflineDao.setGalleryWifiOnly`, `recomputeWifiOnlyForGallery`, `loosenWifiOnly`,
  `countWantedWifiOnly`, `countGalleriesWantedWifiOnly`; `OfflineStore.setGalleryWifiOnly`; the
  `wifiOnly` parameter of `request`; `OfflineCollections`/`CollectionsController`/`SmugViewModel`
  `setGalleryWifiOnly`; `GallerySummary.useMobileDataToo`/`useMobileDataAction`;
  `OfflineMessages.USE_MOBILE_DATA_TOO`/`useMobileData`. This also removes the stale state of C5.
- **The two chains stay**, with new meanings. `offline-files` (CONNECTED) takes the files a photo or
  Image bookmark wants, plus **every** file and gallery listing when the rule is `WIFI_AND_MOBILE`.
  `offline-files-wifi` (UNMETERED) takes everything, and `kick` enqueues it only when the rule is
  `WIFI_ONLY` and a gallery-only file or a gallery listing is wanted. Retry names are unchanged. **The
  pass is the gate.** WorkManager constraints only decide when a pass wakes up, so the rule is never
  enforced by cancelling work.

## 3. Mechanism

### 3.1 The read-time rule (one query predicate replaces both columns)

The DAO parameter `unmetered: Boolean` is renamed `takeGalleryOnly: Boolean`, and the pass passes
`network == UNMETERED || settings.rule.value == WIFI_AND_MOBILE`. The predicate
`(wifiOnly = 0 OR :unmetered = 1)` becomes:

```sql
(:takeGalleryOnly = 1
  OR EXISTS(SELECT 1 FROM collection_photos p WHERE p.imageKey = offline_files.imageKey)
  OR EXISTS(SELECT 1 FROM collection_bookmarks b WHERE b.type = 'Image' AND b.itemKey = offline_files.imageKey))
```

This is in `candidates`, `earliestRetryAt` and `countDue`. `galleriesToList` and
`earliestGalleryRetryAt` drop `g.wifiOnly` and use `:takeGalleryOnly` alone. A new `countWantedGalleryOnly()`
(the same predicate negated, plus wanted galleries) replaces the two `...WifiOnly` counts. These are the
`unreferencedFiles` subqueries (a query, not a counter, as for the reference count).

### 3.2 Scheduler

```
kick():   enqueue(ANY, APPEND_OR_REPLACE)
          if (rule == WIFI_ONLY && store.countWantedGalleryOnly() > 0) enqueue(UNMETERED, APPEND_OR_REPLACE)
after(r, network): unchanged, but earliestRetryAt(takeGalleryOnly = network == UNMETERED || rule == WIFI_AND_MOBILE)
```

## 4. Failure design: what the user sees

| Situation | Behaviour | User sees |
|---|---|---|
| Flip to Wi-Fi and mobile data while a gallery waits on mobile data | `set` commits, `kick` enqueues ANY, the pass takes the gallery's rows | "Saving to this phone… n of m" within seconds |
| Flip to Wi-Fi only **during** a pass on mobile data | The pass re-reads the rule before each file (C4). The file on the wire finishes (at most about 4 MB, C11) and no new gallery file starts. Photos continue. No cancel, so no `.part` churn | "Waiting for Wi-Fi" after the current photo |
| Flip while on Wi-Fi | Wi-Fi is allowed under both rules: no change | unchanged |
| Flip while offline | The setting is stored. `kick` enqueues work that waits for its constraint | 5-11 "No connection. Saving continues when you're back online." |
| Metered Wi-Fi (hotspot, or a network marked metered), rule Wi-Fi only | UNMETERED is not met, so galleries wait | **new:** "Waiting for Wi-Fi" + `WAITING_METERED_WIFI` (step 6-N3), not a bare "Waiting for Wi-Fi" while connected to Wi-Fi |
| No network | unchanged (5-11) | "No connection…", with no link to the setting (it would not help) |
| Storage below the floor | unchanged (5-11: the storage text wins) | storage text, with no link |
| Process death right after the tap | `commit()` returned before `kick`. At the next start, `kickIfWanted` runs and the pass reads the stored rule. A death before `commit` keeps the old rule, and the dialog shows the old rule | consistent with what is stored |
| Roaming, rule Wi-Fi and mobile data | No roaming rule of our own (CONNECTED, as today). Android's data-roaming switch (off by default) already blocks all data when roaming | the dialog's data-use line (§5) |
| A photo saved alone that is also in a kept gallery, then removed from its collection, rule Wi-Fi only | With the photo reference gone, the file is gallery-only and waits for Wi-Fi (fixes C5) | the gallery's progress |
| An old v17 emulator row with `g.wifiOnly = 0` | ignored; the global rule applies | per the setting |
| 429 / 5xx from the CDN | unchanged (BUSY, backoff); never "offline" or "waiting for Wi-Fi" | the Phase 5 busy text |

Intermediate state: between the tap and the next pass (seconds) the row keeps its old line. Nothing says
"saved" before a file is DONE.

## 5. Strings (in `OfflineMessages`, one test each; Q5 approves them)

| Key | Text | Where |
|---|---|---|
| `SETTINGS_ICON` | content description "Saving for offline settings" | Collections top bar |
| `NETWORK_TITLE` | "Saving galleries for offline" | dialog title |
| `NETWORK_WIFI_ONLY` | "On Wi-Fi only" / "Kept galleries wait for Wi-Fi. A Wi-Fi network marked as metered, like a phone hotspot, counts as mobile data." | radio row + line under it |
| `NETWORK_ANY` | "On Wi-Fi or mobile data" / `networkAnyHint(waiting)`: "Kept galleries also save on mobile data. A gallery is often 100 MB or more; {size} is waiting now." ("{size} is waiting now" is left out when nothing waits. When a gallery is not listed yet: "…; its size is known once it is listed.") | radio row + line under it |
| `NETWORK_PHOTOS_NOTE` | "Photos you save one at a time always download right away, on any connection (a few MB each)." | dialog footnote (only under Q1 (a)) |
| `CLOSE` | "Close" | dialog button |
| `WAITING_FOR_WIFI` | "Waiting for Wi-Fi" (**unchanged**) | gallery row main line |
| `waitingWifi(done,total)` | "Will save on Wi-Fi. {done} of {total} saved so far." (**unchanged**) | gallery row detail |
| `CHANGE_NETWORK_SETTING` | "Change network setting" | link on a waiting row; opens the dialog |
| `waitingMeteredWifi(done,total)` | "This Wi-Fi is marked as metered, so it counts as mobile data. {done} of {total} saved so far." | gallery row detail on metered Wi-Fi (6-N3) |

**Removed:** "Use mobile data too" (`USE_MOBILE_DATA_TOO`) and "Use mobile data ({size})" (`useMobileData`)
from Phase 5 §8.1. **Kept unchanged:** every other `OfflineMessages` text, including `NO_CONNECTION`
and the storage text. Every text above names the cause (Wi-Fi rule, metered network, size) and the way out
(the setting).

## 6. Tests (red first on the old code; real-shaped data)

Red evidence goes in the progress log. Each test is run against `HEAD` in a `git worktree` with only the
test (and an `InMemoryOfflineSettings` stub that the old code ignores) copied in, or against a stub that
reads the rule once per pass. Never `git stash`. Fixture: `OfflineFixture` + `FakeCdn`. Kept galleries
`N74KSK` (61 photos) and `FfHCms` (122 photos, NodeID `LCdk7F` ≠ AlbumKey) use real sizes and MD5 shapes.
One kept gallery is under `2sDN5x` → `P4BKB` (password, session via the saved password). Dates are
relative to now.

| # | Test | Red on old code (how) |
|---|---|---|
| N1 | `OfflineSettingsTest`: empty prefs → `WIFI_ONLY`; an unknown value → `WIFI_ONLY`; `set(WIFI_AND_MOBILE)` then a **new** instance over the same prefs file (process death) → `WIFI_AND_MOBILE`; the flow emits once per change | the class does not exist; against a stub that returns a constant, `expected WIFI_AND_MOBILE but was WIFI_ONLY` |
| N2 | `OfflineWorkerTest`: rule `WIFI_AND_MOBILE`, `FfHCms` kept with `g.wifiOnly = 1` → the CONNECTED worker lists it and downloads all 122 (one CDN request each) | old: waits; `expected 122 DONE but was 0` |
| N3 | rule `WIFI_ONLY`, a gallery row with `g.wifiOnly = 0` (old emulator override) → the CONNECTED worker downloads none of its gallery-only files, and the UNMETERED worker downloads them | old: honours the row, downloads on mobile data |
| N4 | `kick` with rule `WIFI_AND_MOBILE` and a wanted gallery → no `offline-files-wifi` work enqueued; with `WIFI_ONLY` → enqueued | old: `countWantedWifiOnly > 0` enqueues it under both rules |
| N5 | **Flip mid-pass:** rule `WIFI_AND_MOBILE`, `N74KSK` listed, `FakeCdn.hold` on photo 1 of the CONNECTED pass; `set(WIFI_ONLY)`; release → photo 1 DONE, photos 2..61 PENDING, exactly 1 CDN request; a single photo saved alone during the pass is still downloaded | against a stub reading the rule once at pass start: 61 requests |
| N6 | **Stale loosen (C5):** rule `WIFI_ONLY`; `XVRvVTM` saved to Favorites while `N74KSK` (which lists it) is kept; remove it from Favorites before its download; CONNECTED pass → no request for it; UNMETERED pass → DONE | old: `loosenWifiOnly` left it at 0, so it downloads on mobile data (this confirms or refutes C5) |
| N7 | `after()` on the CONNECTED chain with rule `WIFI_ONLY` and only gallery-only retryable rows → no retry enqueued (no loop); with `WIFI_AND_MOBILE` → one retry enqueued on `offline-files-retry` | old: decided by `g.wifiOnly`, not by the rule |
| N8 | `OfflineReaderTest`/`OfflineRowState`: rule `WIFI_AND_MOBILE`, mobile network → "Saving to this phone… n of m" even with `g.wifiOnly = 1`; rule `WIFI_ONLY` → `WAITING_FOR_WIFI` with action `CHANGE_NETWORK_SETTING` and no `useMobileDataToo` field; no network → `NO_CONNECTION`, no action; storage → storage text, no action | old: decided by `g.wifiOnly`; the action is "Use mobile data (… MB)" |
| N9 | `OfflineMessagesTest`: every §5 text is non-empty and names a cause and a way out; `USE_MOBILE_DATA_TOO`/`useMobileData` are gone (compile) | new texts missing |
| N10 | `NetworkSettingScreenTest` (ScreenRig): the Collections tab shows "Saving for offline settings"; choosing "On Wi-Fi or mobile data" stores it and enqueues CONNECTED work; a kept gallery row has **no** "Use mobile data too" switch; "Change network setting" opens the dialog | old: the switch is present, and there is no icon |
| N11 | (6-N3) `OfflineRowState`: rule `WIFI_ONLY`, network = metered Wi-Fi → `waitingMeteredWifi`; `NetworkKind` mapping (pure) from the capability booleans | old: plain "Waiting for Wi-Fi" |

Tests that change, because they pin the old per-gallery behaviour: `OfflineGalleryTest`
`aWifiOnlyGalleryIsNotListed…UntilTheUserAllowsIt`, `takingTheMobileDataChoiceBack…`,
`aPhotoSavedAloneIsNotMadeToWait…` (kept: the rule still holds through §3.1), `OfflineWorkerTest`
`kick_withAWifiOnlyRow…`, `…MayUseMobileData…` (×2), `kick_addsTheUnmeteredWork…`, and
`OfflineReaderTest` `…offers mobile data` (×3). Each one is rewritten to the global rule in the same
commit, with the flip listed in `findings.md`. `MigrationTest`, `LegacyRepairTest` and `SchemaGuardTest`
stay **unchanged** (no schema change).

## 7. Steps (Sonnet, one commit each; full suite + `BUILD SUCCESSFUL` after each)

They slot **after 6-16 and before 6-17**. 6-16 rewrites `CollectionsTabView` rows (confirms, blank key),
and 6-17 measures touch targets on those same rows, so the switch must be gone before 6-17 measures. 6-18
(J5) then exercises the final Collections tab. All of them are `git revert`-able (no migration).

| Step | Files | Work | Tests | Risk |
|---|---|---|---|---|
| **6-N1** | new `data/offline/OfflineSettings.kt`; `AppModule` (provider); `OfflineDao` (§3.1 predicate, renames, deletions, `countWantedGalleryOnly`, `galleryOnlyWaitingBytes`); `OfflineStore`, `OfflineDownloader`, `OfflineScheduler`, `OfflineCollections` (+`setGalleryNetwork`), `OfflineRowState`/`OfflineReader` (combine the rule); `Entities.kt` KDoc. **Needs Q1, Q2** | §2.1, §2.3, §3. The per-gallery switch still renders but does nothing until 6-N2. Nothing ships between them (no release) | N1-N8, plus rewriting the pinned tests | Medium: query predicates across 5 DAO methods; the 5-5 no-retry-loop rule (N7) |
| **6-N2** | `OfflineRowViews` (switch removed, link), new `ui/browser/NetworkSettingDialog.kt`, `BrowserScreen` (Collections `actions`), `CollectionsTabView`, `CollectionsController`/`SmugViewModel` (`offlineNetworkRule`, `setOfflineNetworkRule`; old `setGalleryWifiOnly` removed), `OfflineMessages` §5. **Needs Q3, Q5** | §2.2, §5 | N9, N10 | Low |
| **6-N3** | `AppModule.networkAvailableFlow` → `networkKindFlow` (`NONE`/`UNMETERED`/`METERED_WIFI`/`METERED_OTHER` from `NetworkCapabilities`), `OfflineReader`, `OfflineRowState`. **Needs Q4** | §4 metered row | N11; the 5-11 "no connection" tests stay green unchanged | Low (the callback's `onCapabilitiesChanged` is a seam; pure mapping tested) |
| **6-N4** | docs: a superseded note on `phase-5-offline-files.md` §8.1 and on `phase-6-ui-states-and-polish.md` Q15 and §13 (one line each, pointing here, **after** the agent editing them is done); `DESIGN.md` §5 (offline section: one global rule); `findings.md` rows (C5 and the flips); `CLAUDE.md` +1 line "Kept galleries follow one global network rule (`OfflineSettings`); `offline_files.wifiOnly` and `offline_galleries.wifiOnly` are dead columns" (needs the owner's yes, as Phase 6 Q12). `SMUGMUG.md`, `PRIVACY_POLICY.md`: no change (no API or data change) | Docs | none | None |

Emulator check (added to 6-21): `svc wifi disable` with mobile data on, keep `N74KSK` → "Waiting for
Wi-Fi" + "Change network setting". Choose "On Wi-Fi or mobile data" → `photos.smugmug.com` requests within
a minute, without a restart. Switch back mid-gallery → requests stop after the current photo. Kill the app
→ the dialog shows the stored rule. Phone (6-22): a metered Wi-Fi or hotspot shows the metered text.

## 8. Owner questions (recommended first)

1. **Scope.** (a) **Kept galleries only; single photos always download at once on any network.** This
   keeps today's photo behaviour, and a photo is at most about 4 MB. (b) Every save, photos too, as
   streaming apps do. Under the Wi-Fi-only default, a photo saved on mobile data then waits for Wi-Fi,
   which is a new default for photos (each photo row needs a waiting state, +1 step).
2. **Default.** (a) **Wi-Fi only**, for new and upgraded installs (today's gallery default; no shipped
   install has per-gallery choices, C10). (b) Wi-Fi and mobile data: about 100 MB per gallery on mobile
   data unless the user changes it.
3. **Placement.** (a) **A settings icon on the Collections tab's top bar that opens a small dialog**, plus
   "Change network setting" on a waiting gallery. (b) A new Settings screen (route) from Hub and
   Collections. (c) An inline card at the top of the Collections list.
4. **Metered Wi-Fi text (6-N3).** (a) **Yes**: say "This Wi-Fi is marked as metered…" instead of a bare
   "Waiting for Wi-Fi" while on Wi-Fi. (b) No; drop 6-N3.
5. **Words.** (a) **Approve §5 as written** (edit any line). (b) Edit: give the lines.
6. **Switching to Wi-Fi only during a download.** (a) **Finish the photo on the wire (≤ about 4 MB),
   start no more.** (b) Cancel it at once: no extra data, but more cancel paths to test.

## 9. Deviations

*(Filled in during implementation: step, what changed from this addendum, why.)*

| Step | Deviation | Why |
|---|---|---|
