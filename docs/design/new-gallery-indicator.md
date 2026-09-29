# Design: the "new gallery" dot

Status: **approved** — §5 choices 1, 2, 3 accepted as recommended *(owner, 2026-09-29)*: "new" = ImagesLastUpdated with migration 15→16; crawl once per launch, at most every 15 min per site; saved password folders re-unlocked at launch.
Findings: #1, #2, #3, #4, #5, #14, #15, #16, #17 in [findings.md](../findings.md).

## 1. Expected behaviour

When a gallery gains photos (or is created), it shows a dot, and so does every ancestor folder
down to the one you're looking at. That holds for galleries under a password folder you've
unlocked (Family → School). The dot clears when you open the gallery, or when you long-press
"Mark as Viewed", and not otherwise. Changes older than 30 days don't count.

Acceptance check on the owner's real site: after launch, Home → Family → School all show a dot,
driven by "2026-09-01 - New School Year" and "2026-08-21 - Laurel at Digipen" (modified
2026-09-28). This is measured on the device, not inferred from tests.

## 2. Why it fails today (evidence in findings)

| Where the data comes from | What's wrong |
|---|---|
| Change detection: `buildInMemoryGalleryCache` → `user!albums` | Runs anonymous at launch, because the unlock cookie is in memory only (#16), so Family's 2,514 extra galleries are invisible. It also sends sort params the endpoint ignores, and stops early on an order that isn't sorted (#14). |
| Stored date: `cached_albums.dateModified` | Album `LastUpdated` (School: 09-24), while the dot compares node `DateModified` (09-28) (#15). |
| The dot: CTE over `cached_nodes` | Only knows galleries whose parent folder was browsed *and* re-fetched since the change (#4). |
| Home dot | Keyed by AlbumKey against a set of NodeIDs (#5). |

That's three owners for one question. v0.7.6 patched the third, and this session's #4 patch
did too. Per CLAUDE.md, a second fix for one symptom means asking which single place should
own it.

## 3. Design: one owner

**`cached_albums` owns "is this gallery new?"** It's the only store that is synced eagerly and,
once §3.1 is done, completely. `cached_nodes` only supplies the folder tree that dots bubble
up through.

1. **Sync with the session.** At sync start, POST `node/{id}!unlock` for every saved password
   root in `PasswordStore`, which fills the in-memory cookie jar. Then list `user!albums`.
   (Verified: 119 → 2,633 albums.)
2. **Full paged crawl, no early stop.** Diff by album NodeID against `cached_albums`. Request a
   minimal `_filter` (NodeID, AlbumKey, Name, ImagesLastUpdated, SecurityType, PasswordHint, Uri,
   WebUri, UrlPath, ImageCount, ParentNode). For this site that's 27 pages of 100.
3. **One date: `ImagesLastUpdated`.** It's stored per gallery and it is what "new" means. It is
   within seconds of the node `DateModified` that existing viewed-rows were recorded with. In the
   one sample checked, DateModified was 10 s *after* ILU, so an old viewed-row still clears the
   dot. The migration must be verified on the device before relying on that.
4. **The dot query** seeds from `cached_albums` (recent, unviewed `ImagesLastUpdated`) and
   bubbles up through `cached_nodes` folder rows by `parentNodeId`. `markNodeAsViewed` records
   the same `ImagesLastUpdated`.
5. **#4's patch is removed**, and folder-listing invalidation goes back to deleting the parent's
   cached listing. That delete refreshes the listing and no longer affects the dot.
6. **The Home dot** looks up by NodeID.

## 4. How it fails (decided before building)

- **A wrong or changed saved password:** the unlock returns non-200. Skip that root, log it (#11),
  and keep its previously synced galleries rather than dropping them.
- **Offline, or a 429 mid-crawl:** abandon the crawl without writing a partial diff. A partial
  crawl can't prove anything was deleted, so rows are only upserted, never removed.
- **A gallery inside a folder that's never been browsed:** it has no `cached_nodes` ancestor, so
  there's nowhere to bubble. The gallery is marked new in the index, but no folder can show it
  until the folder is browsed once. This limitation is known, and the Diagnostics screen reports
  a count of it.
- **A background sync while a screen is open:** the dot is a Room `Flow`, so it updates live.
  The listing refresh stays as it is.
- **Process death mid-crawl:** nothing is committed until the crawl completes (one transaction).
- **What you see meanwhile:** the old dots stay until the crawl finishes; there's no flicker to
  empty.

## 5. Choices that need the owner (one-way or cost)

1. **Room migration 15 → 16:** add `cached_albums.imagesLastUpdated`, and "new" comes to mean
   "photos added or changed" (`ImagesLastUpdated`) rather than "album settings changed"
   (`LastUpdated`).
2. **API cost:** a full crawl is about 27 calls per sync for this site, on the shared API key.
   Proposal: at most once per launch, and no more than every 15 minutes per site.
3. **Automatic unlock:** re-unlock every saved password folder at each launch, using credentials
   you already saved.

## 6. Stages (each reviewed and committed on its own)

| Stage | Contents | Tests, each failing first |
|---|---|---|
| 1 | Sync: session unlock, full crawl, `ImagesLastUpdated`, migration | A fake API that shows Family's galleries only after unlock; an *unsorted* listing where the newest gallery is on page 2; the three-date gallery shape; the migration test |
| 2 | Dot owner: CTE from `cached_albums`, viewed-date, remove #4's patch, Home dot by NodeID | Family → School → gallery (depth 2) lights School and Family; opening the gallery clears the chain; a bulk-bumped folder doesn't light (#2, kept) |
| 3 | Offline: don't retry or toast OkHttp's synthetic 504 from `only-if-cached` (#6) | The RetryingCallFactory test with a cache-miss 504 |
| 4 | Diagnostics (#11): release-safe log ring buffer, a sync report (per root: unlocked yes/no, galleries seen, new count, orphans), and export | — |
| 5 | Process (#13): skills → `.claude/skills`, trim Antigravity-only content from AGENTS.md | — |

Stages 1–3 are required before the next Play publish *(owner, 2026-09-29)*.
