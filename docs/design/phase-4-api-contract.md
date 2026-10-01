# Design: Phase 4: API contract (T4) and one HTTP cache policy (T5)

Status: **DRAFT, needs owner sign-off (§7) before step 4-6.** Steps 4-0…4-5 change no meaning
(Q4 and Q6 gate parts of 4-0 and 4-1, see the table) and can start before the rest is signed off.
Written 2026-09-30 by the planning agent, read-only, against `main` at `feab265` (v0.8.0, Phase 3
done, 357 unit tests per the progress log, not re-run here).
Scope: review §4 row "4 — API contract and cache" ([whole-app review](../review/2026-09-29-whole-app-review.md)):
T4 (R-27…R-34), T5 (R-35…R-37), and R-23 from T3. No Room migration, no new module, no new
dependency. Template: [phase-3-scoping.md](phase-3-scoping.md).

Exit criterion (review §4): **the param-echo test is green for every endpoint; thumbnails appear
on page 2+.** §6.1 says how "page 2+" is shown when no public folder has more than 100 children.

## 1. What the code does today (`feab265`), and what the live API says

`Api` = `data/api/SmugMugApi.kt`, `Repo` = `data/repository/SmugMugRepository.kt`, `VM` =
`ui/viewmodel/SmugViewModel.kt`, `AppModule` = `di/AppModule.kt`. Every citation also names the
function; search by name if lines moved.

### 1.1 Premise checks (live API, anonymous, 2026-09-30)

All calls were `curl` against `https://api.smugmug.com/api/v2/...` with `APIKey=<KEY>` read from
`local.properties` inside the shell and redacted from every saved file. No password was read or
used. "Echo" means `Response.Uri` in a 2xx answer: **SmugMug echoes only the parameters it
accepted** (it drops `_expand`, `_verbosity`, `APIKey` and anything unknown), and an `OPTIONS`
request on an endpoint lists its accepted GET parameters. Those two facts are the oracle for §3.1.

| # | Claim (review id) | Command shape (key redacted) | Result | Verdict |
|---|---|---|---|---|
| P1 | GETs ignore `Password=` (R-23) | `curl -X OPTIONS ".../<endpoint>?_verbosity=1&APIKey=<KEY>"` for 19 endpoints; `GET node/3BxbFF!children?count=5&_filter=NodeID&Password=notreal` | `Options.Parameters.GET`: `user!albums` [Order]; `node!children` [Type, SortMethod, SortDirection]; `album!images` [FileNames, SkuId]; `image!search` [Text, SortMethod, SortDirection, Scope, Keywords, Relevance, DateTaken*, DateUploaded*, Type]; `node!search` [Text, SortMethod, SortDirection, Scope]; `user!imagesearch` [q, Order]; `user!topkeywords` [NumKeywords, NodeURI]; `user!recentimages` [MediaType]; `!sizedetails` [PrefetchSizes]; `user!search` [q]; `user/{n}`, `node/{id}`, `!parents`, `album/{k}`, `image/{k}-0`, `!metadata`, `!bioimage`, multi-key `album/{k1,k2}`: []. **None accepts `Password`.** The echo drops it. The app sends it on **15** methods (`@Query("Password")` count). | confirmed |
| P2 | `NextPage` drops `_expand` (R-27) | `GET user/{nick}!albums?count=100&_expand=HighlightImage&_filter=…&_filteruri=HighlightImage,Folder&_verbosity=1`; then its `NextPage`; `GET node/4zqWw!children?_expand=HighlightImage&count=5&…` then `start=6` with and without `_expand` | NextPage = `…count=100&_filter=…&_filteruri=…&start=101` (no `_expand`, no `_verbosity`). Page 2 followed raw: **0 expansions and `Uris.*` as objects** (findings #22). Root children page 1: 5 expansions; page 2 with `_verbosity=1` re-added (what `overrideUrlCount` does): **0**; with `_expand` re-added: 5. | confirmed |
| P3 | `topkeywords` wants `NodeURI` (R-28) | `GET user/{nick}!topkeywords?_verbosity=1&NodeID=3BxbFF` / `&NodeURI=/api/v2/node/<id>` | `NodeID`: dropped from echo, 165 keywords (= unscoped). `NodeURI`: echoed; root 165, `SSH6Wc` 11, `mC5cbw` 4, `tX3VgT` 3, `3BxbFF` **0** (its galleries are untagged: `FfHCms` has 0 of 122 images with `Keywords`). The review's "77" depends on the folder. | confirmed |
| P4 | `_filteruri` strips `ImageSizeDetails` (R-29) | `GET album/N74KSK!images?count=3&_filter=…,Uris&_filteruri=ImageSizeDetails,LargestVideo`; unfiltered `album/N74KSK!images?count=1`, `image/XVRvVTM-0`, `image!search`, `user!recentimages` | `Uris.ImageSizeDetails` = `/api/v2/image/{key}-0!sizedetails` appears **only when listed in `_filteruri`**; the app asks `LargestVideo,Album`. `DateTime` is never present (there is `DateTimeOriginal`/`DateTimeUploaded`). `WebUri` IS present on `AlbumImage` (the app's `getAlbumImages` doesn't request it) and absent on `image/{k}-0`, `image!search`, `recentimages`. `ImageAlbum` is absent on all three image payloads. | confirmed; review partly wrong on WebUri |
| P5 | `image/{key}` 301s to `{key}-0` with no-store (R-30) | `GET image/XVRvVTM?…`, `image/XVRvVTM!metadata`, `image/XVRvVTM-1`, Serial of all images of `FfHCms`, `N74KSK` | `301`, `Cache-Control: private, no-store, …`, `Location: /api/v2/image/XVRvVTM-0?<same params>&APIKey=<KEY>`; `!metadata` the same; `-1` also 301s; `-0` answers 200. `Serial` = 0 for all 183 images sampled. That this makes details/EXIF fail **offline** follows from OkHttp storing only the 2xx under the `-0` URL. | redirect confirmed; offline failure likely (red test in 4-9) |
| P6 | Search past 10,000 → 500 (R-31) | `GET image!search?Scope=/api/v2/user/{nick}&count=100&start=9901|9902|10001`; `count=500` | Scope with no Text: `Total` 10000 (capped). `start=9901&count=100` → 200, no NextPage; `start=9902` → **500, empty body, text/html**; `10001` → 500. **`count=500` is clamped to `RequestedCount` 100**, so `start=9901&count=500` → 200. | response confirmed; **no current code path found that sends such a request** (§1.3) |
| P7 | Locked `!images` = 200 with no `AlbumImage` (R-32) | `GET album/zzwtrF!images?count=500&…` (anonymous; zzwtrF = "Elliot Grad Photos", SecurityType Password, from the anonymous `user!albums`); `GET album/zzwtrF`, `album/N74KSK`, `node/2sDN5x` | 200, **no `AlbumImage` key**, `Pages.Total` 0, `LastPage …start=-499`, `Cache-Control: no-store` (the app rewrites it to `max-age=300`). `album/zzwtrF`: `ResponseLevel: "Password"`, no `ImageCount`. Public `N74KSK`: `ResponseLevel: "Public"`. Locked folder `2sDN5x`: `"Password"`. `ResponseLevel` comes back even when not in `_filter`. | confirmed (payload); Gson→`emptyList()` likely (all params of `AlbumImagesPayload` have defaults, so Kotlin emits a no-arg constructor) |
| P8 | Hardcoded idzifamily root in photo search (R-33) | code; `GET image!search?Scope=/api/v2/user/{nick}&Text=kentridge`, same for `node!search` | `Repo:1231 targetScope = scopeUri ?: "/api/v2/node/4zqWw"`. A **user-URI scope works** and needs no lookup: 284 = 284 (image), 4 = 4 (node) vs the node-root scope. | confirmed (code) |
| P9 | Hub total sums page 1 (R-34) | code; `GET user/{nick}!albums?count=100&start=1|101&_filter=AlbumKey,NodeID,Name,SecurityType,ImageCount` | `SiteHubController:214` sums the one page `getUserAlbumsResponse` returns. Anonymous: first 100 galleries = 10,802 photos, the other 19 = 3,184 (**Home under-reports by 23%**); with a session it is 100 of 2,633 galleries. | confirmed |
| P10 | Refresh never reaches the network in 5 min (R-35) | code | **Mostly fixed already:** Refresh Folder sends `no-cache` on every page (2-4), the crawl sends `no-cache` (2-8), search endpoints are never cached (rewrite exclusion). Left: a gallery re-opened or retried within 5 min, and the Hub. | partly stale |
| P11 | Cache key ignores the session cookie (R-36) | response headers of 54 anonymous API calls | No `Vary: Cookie` (only `Vary: Accept-Encoding`), **no `ETag`/`Last-Modified`** (so a revalidation is always a full download), **no `Set-Cookie`** on any anonymous response. OkHttp's key is the URL. Today it is **masked whenever a password is known**: `Password=` puts the request on a different URL and the rewrite never caches it (`AppModule:59`). Still live: `submitPassword` → `loadActiveSiteDetails` (VM:1230) re-reads `user!albums` page 1 with **the same URL** as the anonymous read at site open, so for up to 5 min the Hub shows the anonymous listing right after an unlock. **The dot is not affected any more** (the crawl sends `no-cache`, 2-8). | likely (code); red test in 4-6 |
| P12 | Originals in the shared 50 MB cache (R-37) | code; `curl -D -` on a thumbnail, an X3 and an `ArchivedUri` of `N74KSK` | Phase 0 fixed the worker only. **Coil** (`SmugViewApp.newImageLoader`) uses the shared `RetryingCallFactory` → the shared `OkHttpClient` with the 50 MB cache. CDN: Th `public, max-age=31536000` (13 KB), X3 `public, max-age=31536000, must-revalidate` (428 KB), original `-D` `private, max-age=31536000, s-maxage=0` (689 KB): all stored. With R-29, the zoom's tier-1 fallback **is** `ArchivedUri` (the original, `PhotoDetailComponents:184-187`). | confirmed |
| P13 | Page-size caps (new) | `count=500` on each paged endpoint | `album!images` 500 honoured; `node!children` capped at 200; `user!albums` 100; `image!search` and `user!imagesearch` 100. | confirmed |
| P14 | `user!imagesearch` params (new) | `GET user/{nick}!imagesearch?Text=kentridge&count=500` vs `?q=kentridge` | `Text` dropped from echo, Total 0; `q` → 284. `Api.searchImagesUser` sends `Text`/`Scope`/`Password` and has **no caller** (dead). | confirmed |
| P15 | Not checkable without a password | — | (E1) cookie **names** set by `!unlock` and whether session GETs set cookies; (E2) `ResponseLevel` of a Family gallery with a session; (E3) the same `album/{key}!images` URL anonymous vs with session; (E4) whether any folder has >100 children (none of the 14 public top-level folders does: max 24); (E5) the `!images` shape of an **empty public** gallery (none exists anonymously). | step 4-0 evidence tasks |
| P16 | Observation, out of scope | `GET user/{nick}!recentimages?count=2` anonymous | One of the two results is a thumbnail from `/Family/Events/…`, a gallery under the password folder Family. Same class as R-53 (T10). Recorded for the owner, not investigated. | observed |

### 1.2 Endpoints, parameters sent, and who calls them

| Api method | Sends a param the server ignores | Callers (main) |
|---|---|---|
| `getNodeChildren` (`_expand` is a **path literal**, `node/{id}!children?_expand=HighlightImage`) | `Password` | `fetchAndStoreChildren` Repo:246, `fetchAlbumsInScopeRemote` Repo:1357 (**page 1 only**) |
| `getNodeChildrenByUri` (`@Url` = NextPage) | `Password`; loses `_expand` | `fetchAndStoreChildren` page 2+ |
| `getAlbum` | `Password` | `getAlbum` Repo:340 (gallery open, Hub, R-24 path) |
| `getAlbumImages` / `getAlbumImagesByUri` | `Password`; page 2+ loses `_expand=LargestVideo` | `getAlbumImagesPage` Repo:413 + `getAlbumImagesPageByUri` Repo:455 (AlbumLoader stream VM:1549/1668), `getAllAlbumImages` Repo:585 (Cast, Collections downloads), `PhotoPagingSource` (dead) |
| `getImageSizeDetailsByUri` | `Password` | zoom (`getImageSizeDetails` Repo:1542) — never reached for album photos (P4) |
| `searchImages` (`image!search`) + `searchImagesUserByUri` (NextPage) | `Password` on page 2+; asks `count=500` (gets 100) | `performBackgroundSearchImages` Repo:1217 (scope fallback `4zqWw`, R-33) |
| `getImagesByKeyword` + `searchImagesByUri` | — ; `count=500` (gets 100) | `getImagesByKeywordPage` Repo:543 (Tags tab, `MAX_SEARCH_START = 9501` assumes 500/page) |
| `searchImagesUser` (`user!imagesearch`) | `Text`, `Scope`, `Password` | **none** (dead) |
| `getUserRecentImages` | `Password` | Hub; site discovery |
| `searchNodes` | `Password` | `searchNodesRemote` Repo:1910 |
| `getImage` (`image/{key}`) | `Password`; 301 every time | `getImage` Repo:1518 (target photo, details) |
| `getImageExif` (`image/{key}!metadata`) | `Password`; 301 | `getImageExif` Repo:1506 |
| `getUserAlbums` | `Password` (Hub only) | `GalleryCrawl.fetchAll` (correct since 2-8: builds `start`, `no-cache`), Hub `getUserAlbumsResponse` (**page 1 only**), `getAlbumKeyFromWebUri` VM:2015 via `getUserAlbums` (**page 1 only, anonymous**) |
| `getUserAlbumsByUri`, `getAlbumKeywords` | — | **none** (dead) |
| `getUserTopKeywords` | `NodeID` (wrong name), `Password` | Hub (root scope: same answer either way), `TagSearchController:507` (scoped: wrong) |

Dead code that Phase 4 deletes rather than migrates: `searchImagesUser`, `getUserAlbumsByUri`,
`getAlbumKeywords` (Api and Repo), Repo `getUserRecentImages`, Repo `getImagesByKeyword`, Repo's
Paging `getAlbumImages` flow and `PhotoPagingSource` (no caller in `app/src/main`, grep at `feab265`).

### 1.3 Pagination today: four hand-rolled loops

| Loop | How the next page is built | What it loses |
|---|---|---|
| `fetchAndStoreChildren` | NextPage + `overrideUrlCount` (re-adds `count`, `_verbosity`) | `_expand=HighlightImage`: **folder covers for children 101+ are null** (R-27) |
| `fetchAlbumsInScopeRemote` | none: page 1 only | children 101+ of each scanned folder |
| `loadAlbumPages` (VM), `getAllAlbumImages` | NextPage + `overrideUrlCount` | `_expand=LargestVideo`: **videos 501+ have no `videoUrl`** (no public gallery with a video past #500 was found, P15) |
| `performBackgroundSearchImages`, `getImagesByKeywordPage` | NextPage + `overrideUrlCountAndStart(…, 500, start)` | nothing expanded; but `count=500` is clamped to 100, so Tags stops at `currentStart > 9501` = **9,600 of 10,000** |
| `GalleryCrawl.fetchAll` | typed call, `start = 1, 101, …` | nothing (the pattern to generalise) |
| Hub, `getAlbumKeyFromWebUri` | none: page 1 only | galleries 101+ (R-34; and a search photo in gallery 101+ can't be resolved to its gallery) |

No loop sends `start + count − 1 > 10,000`: the search loops follow NextPage, which the server
omits at 10,000, and Tags stops at 9,501. **R-31's 500 was not reachable from current code**; the
cap becomes an invariant of the helper anyway (§3.2).

### 1.4 The HTTP cache path today

`AppModule.provideOkHttpClient`: application interceptors `offlineFallback` (offline GET →
`only-if-cached, max-stale=7d`, `:111`) → `header` → `logging`; network interceptor
`smugMugCacheRewriteInterceptor` (`:50`: a successful GET whose response says `no-store/no-cache/
max-age=0` becomes `public, max-age=300`, except search, `!unlock`, `Text=` and `Password=`
requests); a 50 MB `Cache`; an in-memory cookie jar (`:123`). `RetryingCallFactory` wraps it for
Retrofit **and Coil**. Since 2-1 it does **not** retry the synthetic 504 (`isSyntheticCacheMiss`),
so CLAUDE.md's line "retries every 5xx, including OkHttp's synthetic 504" is stale. Callers that
want fresh data send `Cache-Control: no-cache` through a `@Header` param on three methods.

## 2. Who owns each concern after Phase 4

| Concern | Only owner |
|---|---|
| Which query params an endpoint may receive | `SmugMugApi` signatures, checked by `ApiContractTest` against `api-contract/accepted-params.json` (captured from live `OPTIONS`) |
| Next page, `start`/`count` arithmetic, server clamps, the 10,000 window, page cap, cancellation between pages | `Pager` (§3.2). Nobody follows a `NextPage` URL |
| Whether a cached API response may be reused online | `CachePolicy` interceptor (§3.3): caller's explicit `Cache-Control` > offline rule > credential epoch |
| "Credentials changed" for the cache | `SessionCookieJar` (bumps `CredentialEpoch`); `UnlockManager.sessionEpoch` stays the trigger for the resync (domain), not for the cache |
| "This gallery is locked" | `album/{key}` `ResponseLevel == "Password"` (§3.4), not the shape of `!images` |
| Image bytes on disk | Coil's disk cache only (§3.7); the 50 MB OkHttp cache holds API JSON only |
| Hub totals, WebUri → gallery | the gallery index (`cached_albums`) for the site (§3.6, Q3) |

## 3. Design

### 3.1 The parameter contract (R-23, R-28, P14)

`app/src/test/resources/api-contract/accepted-params.json` (committed; no key, no payload): per
endpoint template (`user/{n}!albums`, `node/{id}!children`, …) the GET parameter names from P1, plus
one shared meta list `APIKey, _filter, _filteruri, _expand, _verbosity, _shorturis, count, start`.
`LiveApiContractTest` (gated by `assumeTrue(key present)`, like `SmugMugApiTest`) re-runs `OPTIONS`
and fails when SmugMug's list drifts from the file; its failure messages go through `Redactor`.

`ApiContractTest` is JUnit `Parameterized`, **one case per `SmugMugApi` method** (a reflection guard
fails when a method has no case, so a new endpoint cannot skip the contract). Each case calls the
method through Retrofit with every optional parameter non-null, against `FakeSmugMugServer`, and
asserts:
1. every non-meta query key it sent is in the endpoint's accepted list (form fields of `!unlock`
   POSTs are exempt; their query is `APIKey` only);
2. **params sent == params echoed**: the fake now answers `Response.Uri` the way SmugMug does (path +
   accepted and meta params, minus `APIKey`, `_expand`, `_verbosity`), and the case compares the
   sent query (minus those three) with the echo. A dropped param shows up as a difference.
Changes it drives: `getUserTopKeywords` takes `@Query("NodeURI") nodeUri` and the repository builds
`/api/v2/node/{id}` (R-28); `searchImagesUser` is deleted (P14); `Password` disappears from all
GETs in 4-7 (R-23). Until 4-7 the test holds an **exact** expected-violation set (the `Password`
key on the methods that still send it): it fails if a violation appears **or disappears**, so it is
green, not `@Ignore`d, and shrinks to empty.

Field check, for `_filter`/`_filteruri` (the echo does not validate field names): `ApiFieldsTest`
parses the committed real payload fixtures (Q4) with the app's models and asserts that every field
the app **reads** is requested and present (`Uris.ImageSizeDetails` on `album!images`, R-29), and
that every requested field is present in at least one item or is in a `SOMETIMES_ABSENT` map with
its reason (`LastUpdated`/`ImageCount` on password galleries, `Keywords` on untagged photos,
`LargestVideo` on stills). Fields that are **never** sent are removed from the filters (`DateTime`
everywhere; `WebUri` and `ImageAlbum` on `image/{k}-0`; `ImageAlbum` on search and recent images).

### 3.2 `Pager`: one pagination helper (R-27, R-31, P13)

```kotlin
// data/api/Pager.kt
internal class Page<T>(val items: List<T>, val start: Int, val count: Int, val total: Int?)
internal object Pager {
    const val SEARCH_WINDOW = 10_000
    /** Calls [fetch] for start = first, first+count, … until done; returns the next start, or null when done. */
    suspend fun <T> each(first: Int = 1, pageSize: Int, window: Int? = null, maxPages: Int = 200,
                         delayMs: Long = 100, fetch: suspend (start: Int, count: Int) -> Page<T>,
                         onPage: suspend (Page<T>) -> Boolean /* false = stop here */): Int?
}
```
It **owns**: the next start (`start + page.count`, from the server's `Count`, because servers clamp:
100/200/500/100, P13); the stop rule (`count == 0`, or `start + count − 1 ≥ total`, or `onPage`
returns false); the window (`count` is trimmed so `start + count − 1 ≤ window`, and it stops when
`start > window`, so a 500 from P6 can never be requested); the page cap (throws `PageCap`, as the
crawl does); `ensureActive()` between pages, and it never catches (cancellation from a site switch
or a newer album propagates); the 100 ms inter-page delay (0 in tests).
It **does not own**: parsing, retries (`RetryingCallFactory`), cache headers, all-or-nothing writes,
or the per-page side effects. **It never follows `NextPage`.** Every page is the same typed call
with `start`/`count`, so `_expand`, `_filter`, `_filteruri`, `_verbosity` and the caller's
`Cache-Control` are on every page by construction. A resumable caller (Tags) keeps the returned
`Int`, not a URL.

API changes: `getNodeChildren` gets `start: Int? = null` and `@Query("_expand") expand =
"HighlightImage"` instead of the path literal; `getAlbumImages` gets `start: Int? = null`. Deleted at
the end: `getNodeChildrenByUri`, `getAlbumImagesByUri`, `searchImagesByUri`, `searchImagesUserByUri`,
`overrideUrlCount`, `overrideUrlCountAndStart`, `MAX_SEARCH_START`.

Call sites that migrate: `fetchAndStoreChildren` and `fetchAlbumsInScopeRemote` (4-3);
`loadAlbumPages` and `getAllAlbumImages` (4-4); `performBackgroundSearchImages` and
`getImagesByKeywordPage` (4-5); `GalleryCrawl.fetchAll` (4-5, refactor only, its 11 tests pin it).
Not paged on purpose: the Hub's featured list (page 1 of an unsorted listing, findings #14; its
composition is Phase 6).

### 3.3 `CachePolicy`: one owner of "may this response be reused?" (R-35, R-36)

One **application** interceptor replaces `offlineFallbackInterceptor` at the same position, and the
cookie jar moves out of `provideOkHttpClient` into a class:
```kotlin
class CredentialEpoch(private val clock: () -> Long) { @Volatile var changedAtMs = clock(); fun bump() { changedAtMs = clock() } }
class SessionCookieJar(private val epoch: CredentialEpoch) : CookieJar  // bumps when a host's name→value set changes
internal fun cachePolicyInterceptor(isOnline: () -> Boolean, epoch: CredentialEpoch, clock: () -> Long): Interceptor
```
Rules, in order, for a GET:
1. **Offline**: `only-if-cached, max-stale=7d` (today's rule, unchanged, every host). It wins over a
   caller's `no-cache`, so Refresh offline shows the cached copy as it does today.
2. **Caller said so**: a request that already carries `Cache-Control` (`no-cache` from Refresh, the
   crawl, the tree repair, Retry and the lit gallery of Q1) is left alone.
3. **Credential epoch** (api.smugmug.com only): add `Cache-Control: max-age=N`, N = whole seconds
   since `changedAtMs`, when N < 300. OkHttp computes a cached response's age as at least
   `now − sentRequestAt`, so a response is reused online **only if its request was sent after the
   last cookie change**. That covers the race of a request sent anonymously and answered after the
   unlock, with no bookkeeping of ours.
4. Otherwise nothing: the network interceptor's 5-minute rewrite applies as today.

`changedAtMs` starts at process start, so after process death nothing the old process cached is
reused online (the cookie jar is empty again, findings #16), and everything stays available offline.

Interactions:
- **Offline / synthetic 504 / `RetryingCallFactory`**: rule 1 is byte-for-byte today's; an uncached
  offline GET still gets the synthetic 504, which is still not retried (2-1). Nothing in
  `RetryingCallFactory` changes.
- **`UnlockManager.sessionEpoch`** (Phase 3) only counts *new* Sessions and lives in the repository;
  the cache needs "the cookies changed" (also at process start, also for a re-unlock after expiry).
  The jar is the literal thing the cache key ignores, so it owns the cache epoch. Both bump on an
  unlock; neither reads the other.
- **The dot** reads `user!albums` only through the crawl, which sends `no-cache` (rule 2) since 2-8,
  so the dot's freshness does not depend on this. What changes for the user is the Hub after an
  unlock (P11) and every gallery opened in the 5 minutes after one.
- **The 50 MB cache** holds API JSON only once Coil moves out (§3.7). Nothing is evicted by the
  policy: entries from before an unlock remain for offline use and age out by LRU.
- Evidence E1 decides what "a change" is: if SmugMug re-sends the same cookie on every session GET,
  the jar compares name→value (not expiry), so a refresh of the expiry is not a change. If the value
  rotates on every response, the effect is "no online reuse while unlocked" (more requests, never
  stale data); the deviation is recorded and the jar compares names only.

### 3.4 Locked galleries (R-32)

The `!images` shape cannot tell locked from empty (P7, E5), so the locked signal is the album's own
`ResponseLevel == "Password"` (P7: always returned). `AlbumDetails.responseLevel` is added (no
filter change). `getAlbumImagesPage` and `getAllAlbumImages`: when page 1 has no images and
`Total == 0`, they reauthorize if a password is saved (as today), and otherwise read `album/{key}`
and throw `AlbumLockedException` when it says Password. The dead `images == null` checks go.
`loadAlbumPages`: an empty first page from an album whose details said Password calls
`requestPassword` instead of publishing an empty, complete grid. Cast and Collections downloads
catch `AlbumLockedException` and show Q5's message. E2 confirms the value with a session (expected
anything but `"Password"`).

### 3.5 Image endpoints (R-29, R-30)

`getAlbumImages`, `searchImages`, `getImagesByKeyword` add `ImageSizeDetails` to `_filteruri`
(present on all three, P4), so the zoom reads the real tiers. `PhotoDetailComponents`' instant
tier-1 guess becomes the X3 URL derived from the thumbnail, and `ArchivedUri` only when there is no
thumbnail (today it is the reverse, which downloads the original first); its existing `onError`
fallback to `ArchivedUri` stays for photos too small to have an X3. `getImage` requests
`image/{key}-0` and `getImageExif` `image/{key}-0!metadata`: the canonical URL is the one the 200
is stored under, so details and EXIF work offline after one online view. A non-zero serial (none
seen, P5) still 301s and is followed as today. `WebUri` is **not** added to `getAlbumImages`: the
share action uses `webUri ?: archivedUri`, and changing what is shared is T10 (Phase 6).

### 3.6 Scope fallback and index reads (R-33, R-34, page-1 lookups)

- Photo and folder search with no resolvable scope use `Scope=/api/v2/user/{nickname}` (P8), from
  the active session's nickname: no network lookup and never another site's root.
- Hub totals (Q3): `CollectionDao.siteTotals(nickname)` = `COUNT(*)`, `SUM(imageCount)` over
  `cached_albums` for the site, observed as a Flow so they update after each crawl. While the index
  is empty (first launch) the value is null and Home keeps today's page-1 fallback
  (`HomeTabView:793`).
- `getAlbumKeyFromWebUri` matches the path against `cached_albums.urlPath` for the site first
  (`getAlbumByUrlPath`, lower-case compare, the index already has the column), then falls back to
  `_userAlbums`. A search photo in gallery 101+ then opens its gallery.

### 3.7 Image bytes out of the API cache (R-37)

`AppModule` provides `@Named("images") Call.Factory` = a `RetryingCallFactory` over
`okHttpClient.forFileDownloads()` (no cache; same pool, same interceptors, same telemetry), and
`SmugViewApp.newImageLoader` uses it. Coil keeps its own disk cache (`respectCacheHeaders(false)`
caches every image it loads), so offline thumbnails come from there, as they already can. API JSON
is then no longer evicted by originals.

## 4. Failure modes: what the user sees

"Today" is traced from code (*likely*) unless marked.

| Situation | Today | After Phase 4 | User sees after |
|---|---|---|---|
| **Folder with 150 children, online** | Rows 101-150 have no cover (P2, confirmed) | Every page carries `_expand` | All covers |
| **Same, offline, page 2 never cached** | The forced or first listing throws (synthetic 504); the navigator falls back to Room | Same: `Pager` throws, nothing is written (one `replaceChildren` after the last page) | The cached listing, or the offline message if none (unchanged) |
| **429 on page 2** | Retries 6×, then the listing fails whole | Same (retries are `RetryingCallFactory`'s, not `Pager`'s) | Same error text as today (Phase 6 improves it) |
| **Site switch while paging** | Loops check nothing; Phase 3's scope cancel stops them at the next suspension | `Pager` calls `ensureActive()` before each page and never catches | Nothing of A lands on B (Phase 3 guarantee kept) |
| **Unlock, then the Hub reloads within 5 min** | Anonymous page 1 served from the HTTP cache (P11) | Request sent after the cookie change only; the cached one is too old | Family galleries and open locks on Home at once |
| **Unlock while a gallery request is in flight** | Its anonymous (empty) answer is cached for 5 min under the post-unlock URL once `Password=` is gone | Its request was sent before the change, so it is never reused online | The gallery reloads with photos |
| **Locked gallery offline, last seen locked** | Empty grid | The cached anonymous album says Password → prompt; the unlock fails offline (Transient, kept) | "Needs password" prompt; the saved password is not deleted (Phase 3 rule) |
| **Gallery re-opened 2 min after new photos were added** | Up to 5 min of old photos while its dot is lit | Q1: a lit gallery opens with `no-cache` | The new photos |
| **Process death, relaunch within 5 min** | Old process's cached responses (anonymous or not) reused online | Epoch = process start: refetched online; still served offline | Fresh data online; cached data offline |
| **Background crawl racing a screen** | The crawl uses `no-cache`; screens use the cache | Unchanged; the epoch does not touch caller headers | — |
| **Search with more than 10,000 results** | Photos stop at 10,000 (search) / 9,600 (Tags) | Both stop at 10,000; no request beyond the window | 10,000 results; the count the server reports (10,000) |
| **Cast/download of a locked gallery** | 0 photos, silently | `AlbumLockedException` → Q5 message | "This gallery needs its password. Open it once to unlock it." |
| **Zoom a photo** | Tier 1 = the original (P4/P12); sizes never loaded | Tier 1 = X3, then the real tiers | Same picture, a fraction of the bytes |
| **Photo details/EXIF offline after an online view** | Fails (301 not stored, P5) | Served from cache | Details and EXIF |
| **Tags tab scoped to Kentridge Cross Country** | 165 site-wide keywords; choosing one finds 0 photos in scope | Q6: the scoped list (0) | An empty keyword list for that folder (empty-state text is Phase 6) |
| **Search with the root unresolved (offline profile, other site)** | Searches idzifamily's root (R-33) | Searches `user/{nickname}` | That site's results, or the offline message |

Intermediate states: while a `Pager` runs, the screens stream as today (folders publish once at the
end; galleries flush every 3 pages; search inserts per page). The first launch after the update shows
the Hub's page-1 totals until the first crawl completes, then the index totals.

## 5. Test harness (step 4-0)

- **Fake server realism** (`FakeSmugMugServer`): `Response.Uri` echo (accepted + meta params from
  `accepted-params.json`, minus `APIKey`/`_expand`/`_verbosity`); `NextPage` built the SmugMug way
  (drops `_expand`, `_verbosity` and unaccepted params, so the old loops are exercised on the real
  shape); expansions **only when `_expand` is sent**; `Uris` entries only when listed in `_filteruri`;
  count clamps (100 albums, 200 children, 500 images, 100 search); `image!search` with `Total`
  10000 and a 500 empty text/html body past the window; locked `!images` (200, no `AlbumImage`,
  `Total` 0) and `album/{k}` with `ResponseLevel`; `image/{key}` → 301 `no-store` to `{key}-0`;
  `user!topkeywords` that honours `NodeURI` only.
- **New real-shaped data** (synthetic IDs with real lengths, AlbumKey ≠ NodeID, dates relative to
  now): public folder `Kp7Wq2` with 150 gallery children under the root (covers on every page); gallery
  `Vd5Qx9`/node `Vn2Lt7` with 620 images, videos at #3, #510, #615; site B unchanged.
- **Production HTTP chain in tests**: `AppModule`'s client construction is extracted, unchanged, into
  `internal fun buildSmugMugClient(cacheDir, isOnline, cookieJar, …)` (as 2-4 did for the rewrite).
  `LoopbackSmugMug` serves the fake's `route()` on a `ServerSocket`, with a `Dns` that resolves
  `api.smugmug.com` to 127.0.0.1 (the `HttpTelemetryTest` pattern), so the **real** OkHttp `Cache`,
  cookie jar and interceptors sit in front of it. `ScenarioRig(httpCache = true)` uses it.
- **Fixtures** (Q4): `app/src/test/resources/api-contract/` (not under the git-ignored `snapshots/`):
  `accepted-params.json`, and trimmed payloads (≤3 items) from public galleries only.
  `ApiFixtureGuardTest` fails if any file there contains the API key literal (read from
  `local.properties` when present), `APIKey=` other than `APIKey=<KEY>`, `Password=`, or `/Family/`.
- **Evidence tasks** (debug build on the emulator that already holds the owner's saved passwords; the
  password is never typed, read or printed by the agent; the debug HTTP log is redacted, R-52):
  E1 `Set-Cookie` **names** on `!unlock` and on 20 session GETs; E2 `ResponseLevel` of a Family
  gallery with a session; E3 one Family gallery's `album/{k}!images`, anonymous at launch vs after the
  launch unlock (same URL, different answer); E4 `SELECT parentNodeId, COUNT(*) FROM cached_albums
  WHERE nickname='idzifamily' GROUP BY 1 ORDER BY 2 DESC LIMIT 3` (and the same on `cached_nodes`)
  for the exit check's >100 folder. E5 (empty public gallery shape) needs a gallery created on
  SmugMug, an outward change: **not done**; §3.4 does not depend on it.

## 6. Steps (Sonnet, one commit each; full suite + `BUILD SUCCESSFUL` after each)

Each test is committed **green with its fix**. Red evidence is recorded in the progress log by
running the test before the fix (comment the fix out, or a `git worktree` at `HEAD`; never `git
stash`). Red tests are never committed or `@Ignore`d *(owner, Phase 1)*. Rollback for every step is
`git revert`: there is no migration and no stored format change.

| Step | Files | Work | Failing-first test (real-shaped data; how it is red) | Risk |
|---|---|---|---|---|
| 4-0 | test: `FakeSmugMugServer`, `LoopbackSmugMug`, `ScenarioRig`, `api-contract/*`, `ApiFixtureGuardTest`, `LiveApiContractTest` (gated); main: `buildSmugMugClient` extracted from `AppModule` (no behaviour change). **Fixtures need Q4** (without it, only `accepted-params.json`) | §5 harness + E1-E4 recorded in the progress log | Sanity, green on old code: the fake's echo drops `Password`; its NextPage drops `_expand`; through `LoopbackSmugMug` + real `Cache` a second identical GET is a cache hit (pins today's 5-min reuse); guard catches a planted `APIKey=abc` in a temp copy | Low. The loopback + Robolectric download lock flake (seen in 2-9): rerun once, record |
| 4-1 | `Api` (`getUserTopKeywords` → `NodeURI`; delete `searchImagesUser`), `Repo.getUserTopKeywords`, `ApiContractTest`, `MockSmugMugApi` in `SmugMugApiTest`. **Q6 decides the Tags behaviour** | §3.1 contract test with the exact `Password` expected-violation set | `ApiContractTest` case `getUserTopKeywords`: red `NodeID not accepted by user/{n}!topkeywords (accepts NumKeywords, NodeURI)` and echo diff `{NodeID}`; case `searchImagesUser`: red `Text, Scope not accepted` (the case is deleted with the method). `TagScopeKeywordsTest` (fake: School scope has 2 keywords, site 5): red `expected:<[a, b]> but was:<[a, b, c, d, e]>` | Low. Tags shows fewer keywords for folders (correct, Q6) |
| 4-2 | `Api`, `Repo`, delete `PhotoPagingSource` + `PhotoPagingSourceTest` (6 tests), `SmugMugApiTest` cases of deleted methods | Delete the dead surface of §1.2 | None possible (deletion). Evidence: grep in the commit message shows no `app/src/main` caller; the suite compiles and the reflection guard's method list shrinks | Low. A hidden reflective use: none (Retrofit interface only) |
| 4-3 | new `data/api/Pager.kt`; `Api.getNodeChildren` (`start`, `_expand` as a query); `Repo.fetchAndStoreChildren`, `fetchAlbumsInScopeRemote`; delete `getNodeChildrenByUri` | §3.2 helper + folders (R-27) | `FolderPage2CoversTest`: `getNodeChildren("idzifamily", "Kp7Wq2", force = true)` → rows 101-150 have `highlightImageUrl`, every page request has `_expand=HighlightImage` and `Cache-Control: no-cache`. Red: `50 of 150 rows without a cover` (page 2 via NextPage). `fetchAlbumsInScopeRemote` finds gallery #150 (red: missing, page 1 only). `PagerTest` (clamp to the server's Count, window trim, total stop, empty page, cancellation propagates, page cap): new class, cannot be red on old code | Medium: the token-checked listing publish and the 2-4 `no-cache`-on-every-page rule must hold; `BrowserNavigationScenarioTest`, `FolderTreeSyncTest`, `ForcedRefreshBypassesCacheTest` must stay green unchanged |
| 4-4 | `Api.getAlbumImages` (`start`), `Repo.getAlbumImagesPage(start)`, `getAllAlbumImages`, VM `loadAlbumPages`; delete `getAlbumImagesByUri`, `getAlbumImagesPageByUri` | Galleries and Cast/downloads through `Pager` (R-27 videos) | `AlbumPage2VideoTest`: open `Vd5Qx9` in `ScenarioRig` → photos #510 and #615 have `videoUrl`; `getAllAlbumImages` the same; every page echoes `_expand=LargestVideo`. Red: `videoUrl of #510 expected not null` (page 2 lost the expansion). Pin: #3 plays on old code | Medium: AlbumLoader's token, 3-page flush, partial-failure keep (R-46) and LRU must not change; `OpenBWhileAStreamsTest` (7) and `AlbumLoaderTest` (10) unchanged |
| 4-5 | `Repo.performBackgroundSearchImages`, `getImagesByKeywordPage` (resume by `Int`), `TagSearchController` (drop `MAX_SEARCH_START`, `nextUrlToLoad`), `GalleryCrawl.fetchAll` (refactor onto `Pager`); delete `searchImagesByUri`, `searchImagesUserByUri`, `overrideUrlCount*` | Search through `Pager` with `SEARCH_WINDOW` (R-31, P13) | `SearchWindowTest`: a 10,000-result keyword in the fake; Tags loads **10,000** and sends no request with `start + count − 1 > 10,000`; zero 500s. Red: `expected:<10000> but was:<9600>`. Pin (green on old code): photo search stops at 10,000 because the fake omits NextPage there, as the server does. `GalleryCrawlTest` (11) unchanged | Low-medium: Tags resume after leaving the tab (3-10's R-19 fix) now resumes from an `Int`; `TagSearchReentryTest` must stay green |
| 4-6 | new `data/api/CachePolicy.kt` (`CredentialEpoch`, `SessionCookieJar`, `cachePolicyInterceptor`); `AppModule`; `Api.getAlbum`/`getAlbumImages` get the `Cache-Control` header param; AlbumLoader `force` and the lit gallery send `no-cache`. **Needs Q1** | §3.3 (R-35, R-36) | Through `LoopbackSmugMug` with the production chain: **(a)** `HubAfterUnlockTest`: cookie-gated F, Hub loaded anonymously, `submitPassword` (fake `Set-Cookie`), Hub reload → lists the Family galleries. Red: `the Hub request after the unlock reached the server expected:<2> but was:<1>` (served from cache). **(b)** a request held across the `Set-Cookie` (gate, `answerFirst`) is not reused afterwards (red: reused). **(c)** process restart (new jar/epoch, same cache dir) → first online GET goes to the network (red: cache hit). **(d)** a lit gallery opens with `no-cache` (red: no header). Pins green on old code: offline after an unlock still serves the cache; no cookie change → second GET within 5 min is a cache hit; caller `no-cache` reaches the server (2-4); uncached offline → one synthetic 504, not retried (2-1) | Medium: more requests right after an unlock (Hub, resync, subtree index, the open folder) can meet a 429; `RetryingCallFactory` covers it. E1 may force the "names only" variant (§3.3) |
| 4-7 | `Api` (remove `@Query("Password")` from 15 → 13 remaining methods), callers keep `password` only for `reauthorize`; rewrite interceptor drops its `Password` clause; contract test's expected-violation set → empty. **Needs Q2; must follow 4-6** | R-23 | `ApiContractTest` with the empty set: red on 4-6's tree, listing the 13 methods that still send `Password`. `LockedGalleryAfterUnlockTest` (loopback, cookie-gated gallery `FfHCms` under Family): open anonymously (200, no images, cached), submit the password, reopen → 150 photos. It is green on 4-6's tree and **red if 4-7 is applied without 4-6** (record that run: `expected:<150> but was:<0>`), which pins the order | Medium: any GET that secretly depended on `Password=` (none per P1); offline viewing of unlocked galleries starts to work (Q2) |
| 4-8 | `ResponseModels.AlbumDetails.responseLevel`, `Repo.getAlbumImagesPage`, `getAllAlbumImages`, VM `loadAlbumPages`, `CastController`, `CollectionsController`. **Needs Q5; E2 first** | §3.4 (R-32) | `LockedAlbumTest` on the P7 payload: parse gives `images == emptyList()` (pins the Gson premise); `getAllAlbumImages("zzwtrF")` throws `AlbumLockedException` (red: returns `[]`); opening it with no saved password prompts (red: empty grid with `complete = true`). Variant: a saved password and a Transient unlock → no prompt, no deletion (Phase 3 rule) | Low-medium: a public gallery that is really empty must stay an empty grid (`ResponseLevel` "Public") |
| 4-9 | `Api` filters (`ImageSizeDetails`; drop `DateTime`, never-present `WebUri`/`ImageAlbum`), `getImage`/`getImageExif` paths `-0`, `PhotoDetailComponents` fallback (extracted pure `zoomFallbackUrl`) | §3.5 (R-29, R-30) | `ApiFieldsTest`: the `album!images` fixture answered to the app's query has `uris.imageSizeDetails` (red: null, `_filteruri` lacks it). `ImageDetailsOfflineTest` (loopback, fake 301 `no-store` → `-0`): view online, go offline, details and EXIF served (red: synthetic 504). `zoomFallbackUrl(photo)` = the X3 URL (red: `ArchivedUri`) | Low. Removing `ImageAlbum` from filters: every reader already falls back to the thumbnail path (it was never sent, P4) |
| 4-10 | `Repo.performBackgroundSearchImages`, `searchNodesRemote` callers, `SearchController`; `CollectionDao.siteTotals`, `getAlbumByUrlPath`; `SiteHubController`; VM `getAlbumKeyFromWebUri`. **Needs Q3** | §3.6 (R-33, R-34, page-1 lookups) | `SearchScopeFallbackTest` (site B, `user/siteb` answering 503): the search request has `Scope=/api/v2/user/siteb` (red: `/api/v2/node/4zqWw`). `HubTotalsTest` (index of 150 galleries, 2 under Family): totals = index count and sum (red: page-1 sum). `WebUriLookupTest`: a photo URL of gallery #150 resolves to its AlbumKey (red: null) | Low. No migration (queries only) |
| 4-11 | `AppModule` (`@Named("images")` factory), `SmugViewApp`, `HttpClientsTest` | §3.7 (R-37 remainder) | `CoilClientHasNoHttpCacheTest`: through the images factory a loopback CDN response (`public, max-age=31536000`, 1 MB) leaves the API `Cache` size unchanged; through the shared factory (old wiring) it grows by ~1 MB (red: Coil's factory **is** the shared one) | Low. Offline thumbnails come from Coil's disk cache only (it holds every loaded image) |
| 4-12 | docs: `SMUGMUG.md` (Password= does not unlock: l.36, 145; no ETag/304: l.294; page caps; OPTIONS oracle; never follow NextPage), `.agents/AGENTS.md` v4 SortMethod bullet, `CLAUDE.md` (Q7), `findings.md` rows (§10) | Docs only | — | None |
| 4-13 | — | Exit check §6.1 | — | — |

Order: 4-0 → 4-1 → 4-2 → 4-3 → 4-4 → 4-5 are independent of meaning. **4-6 must precede 4-7**
(P11: `Password=` currently partitions the cache by accident). 4-8…4-11 are independent of each
other and can follow 4-5 in any order. Expected suite: 357 → about **400-410** (4-0 +5 and 3 skipped
live cases; 4-1 about +20 parameterized cases; 4-2 −6 to −8; 4-3 +8; 4-4 +2; 4-5 +2; 4-6 +8; 4-7 +1;
4-8 +3; 4-9 +3; 4-10 +3; 4-11 +1). The exact count is recorded per step.

### 6.1 Phase 4 exit check

Unit: `ApiContractTest` green for every `SmugMugApi` method with **no** expected violations;
`FolderPage2CoversTest`, `AlbumPage2VideoTest`, `SearchWindowTest`, `HubAfterUnlockTest`,
`LockedGalleryAfterUnlockTest` by name; `LiveApiContractTest` green when run with the key (records
that `OPTIONS` still matches, and a live `Pager` over `node/4zqWw!children` with `count=5` gets
`HighlightImage` expansions on pages 2-4).

Emulator (debug build of the 4-12 commit, AVD API 34, idzifamily, the emulator's existing saved
passwords; nobody types or prints a password):
1. **Page 2+ thumbnails**: open the folder E4 found with the most children; scroll past row 100;
   every row has a cover (screenshot). If E4 finds no folder over 100, the live `Pager` run above is
   the "page 2+" evidence, and that is recorded as a deviation.
2. `diag-0.log` has **no `Password=`** in any GET line (count = 0; the redactor would show
   `Password=<r>`, so this needs no secret), and `report.txt` http stats show no 5xx from search.
3. Launch: the Hub totals equal `SELECT COUNT(*), SUM(imageCount) FROM cached_albums WHERE
   nickname='idzifamily'`; after the launch unlock the Hub shows Family galleries without a relaunch.
4. Open a gallery and one photo's details, then airplane mode: the gallery, the details and EXIF
   still show (R-30); a never-opened gallery shows the offline message.
5. Zoom one photo: the debug OkHttp log shows a `!sizedetails` call and an `X3`/`X2` CDN request
   before any `-D.` (original) request.
6. Tags tab scoped to Kentridge Band and Jazz shows 11 keywords, Kentridge Cross Country none (Q6).
7. `run-as … du -s cache/http_cache` before and after zooming 20 photos: grows by API JSON only
   (kilobytes), not by images.
Phone: the next `report.txt` shows no `Password=` in http lines and the doctor counts unchanged.

## 7. Needs owner sign-off (recommended answer first)

| Q | Choice | Recommended | Consequence of each option |
|---|---|---|---|
| Q1 | What Refresh means and how long cached API data is trusted online | **(a) Keep 5-minute reuse for ordinary navigation; never reuse online a response whose request was sent before the last session-cookie change (an unlock, or app start); Refresh/Retry always go to the network; a gallery whose dot is lit opens from the network** | (a) fresh after unlocks and for new photos, back/forward stays instant. (b) the same without the lit-gallery rule: a gallery with new photos can show the old ones for up to 5 min while its dot is lit, and opening it marks it viewed. (c) always network for galleries and listings when online, cache only for offline: more data and slower re-opens (AlbumLoader's 3-gallery memory still covers Back) |
| Q2 | Stop sending `Password=` on GETs (R-23) | **(a) Stop on every GET** (P1: no endpoint accepts it). Password-gallery JSON then enters the HTTP cache like everything else: reused 5 min online (subject to Q1) and up to 7 days offline, in the app's private cache (excluded from backup and transfer, R-54) | (a) offline viewing of galleries you unlocked starts working. (b) stop sending, but mark responses under a password root `no-store` (callers ask `UnlockManager.needsPassword`): no password-gallery data on disk, offline stays broken for them, more code. (c) keep sending (today): the contract test can't go green; URLs carry the password |
| Q3 | Hub "galleries" and "photos" totals (R-34) | **(a) From the gallery index**: every gallery you can see, including unlocked password galleries (2,633 galleries with the Family session vs 119 public), no extra request, updated after each crawl | (a) the numbers change meaning from "public" to "what this app can show you". (b) public only: page through `user!albums` anonymously on every Home open (2 requests here, up to 27 with a session's worth of data). (c) remove the totals |
| Q4 | Commit real API payloads as test fixtures | **(a) Yes, trimmed** (≤3 items each, public galleries only, key replaced by `<KEY>`, a guard test blocks the key, `Password=` and `/Family/`) | (a) fields the app depends on are checked against real data (R-65 class). (b) synthetic fixtures copied by hand: no real-data check of `_filter`/`_filteruri`, which is how R-29 survived |
| Q5 | Cast or download of a locked gallery (R-32) | **(a) Stop with "This gallery needs its password. Open it once to unlock it."** | (a) one new message, no new dialog host. (b) prompt for the password from the Cast/Collections screens: the dialog only renders in Browser and PhotoGrid (AGENTS.md), so this needs UI work (Phase 6). (c) today: 0 photos, no message |
| Q6 | Tags tab keywords for a folder scope (R-28) | **(a) Only that folder's keywords** (the server's `NodeURI` answer) | (a) folders with untagged photos show none (Kentridge XC: 0 instead of 165 that matched nothing). (b) the folder's, falling back to site-wide when empty (a chip may then find 0 photos, as today). (c) site-wide (today, by accident) |
| Q7 | Correct `CLAUDE.md` in 4-12 | **Yes**: replace "`RetryingCallFactory` retries every 5xx, including OkHttp's synthetic 504" (stale since 2-1) with "…retries 429/5xx, but not the synthetic 504"; add "Never follow `Pages.NextPage`: it drops `_expand`/`_verbosity`; page with `Pager`" and "`OPTIONS` on an endpoint lists the params it accepts; `Response.Uri` echoes only those" | `CLAUDE.md` is the rules file, so its edit needs the owner's yes. No: 4-12 edits only `SMUGMUG.md`/`AGENTS.md` |

Decided here (no sign-off; same meaning, or a fix to match what the server already does):
- `Pager` never follows `NextPage`; all paging is the typed call plus `start`/`count`.
- At 10,000 search results both searches stop silently at the server's window (the server itself
  reports `Total` 10000); new wording is Phase 6 (R-47).
- `image/{key}-0` and `image/{key}-0!metadata`; a non-zero serial is followed as today.
- Coil gets a cache-less client (§3.7); originals and thumbnails stay in Coil's disk cache.
- The cache epoch comes from the cookie jar, not `UnlockManager` (§3.3).
- Search scope fallback is `user/{nickname}` (P8). The WebUri lookup reads the index first.
- Filters lose fields the server never sends; `ImageSizeDetails` is added. `WebUri` is **not**
  added to gallery photos (sharing is Phase 6, T10).
- The Hub's featured list stays page 1 (its order and contents are Phase 6).
- No Room migration (two new DAO queries only).

## 8. Verified vs assumed

| Claim | Status |
|---|---|
| P1-P14 | verified live 2026-09-30 (anonymous; §1.1) or by reading `feab265` |
| `Response.Uri` echoes exactly the accepted params; `OPTIONS` lists them | verified on 8 endpoints for the echo and 19 for `OPTIONS`; `LiveApiContractTest` keeps it verified |
| OkHttp age ≥ `now − sentRequestAt`, so request `max-age` = seconds since the epoch excludes pre-change requests | from OkHttp's `CacheStrategy` age formula (*assumed* for 4.12); pinned by 4-6 test (b) |
| Retrofit omits a `@Header` whose value is null | relied on since 2-4 (verified there) |
| Gson gives `emptyList()` for the missing `AlbumImage` key | *likely*; pinned by 4-8's parse test |
| Session cookie names; whether session GETs set cookies; `ResponseLevel` with a session; anonymous-vs-session `!images` under one URL | **verified 2026-10-01 (4-0, E1-E3)**: `shm` + `SMSESS`, no cookie on session GETs, `ResponseLevel` "Public" with a session, same URL answers differently |
| A folder with >100 children exists in the owner's data | **verified (4-0, E4)**: six, all under the Family root, max 269 |
| A gallery with videos past #500 exists | not found (3 largest public galleries have no videos); 4-4 is unit-only |
| `MockSmugMugApi` must change with every `SmugMugApi` signature change | AGENTS.md Retro v5; expected in 4-1, 4-2, 4-3, 4-4, 4-5, 4-7, 4-9 |
| Suite size 357 | from the progress log (3-11); **not re-run** by this planner |

## 9. Recorded deviations

*(Filled in during implementation: one line per deviation, with the step, what changed from this
design, and why.)*

| Step | Deviation | Why |
|---|---|---|
| 4-0 | E1-E4 gathered with read-only calls to the live API (cookie names and attributes, `ResponseLevel` with a session, anonymous vs session `!images`, folders over 100 children), not on the emulator | The emulator was not needed to answer them; the unlock cookie flow is the same over HTTP. The password was used only for `!unlock` and reads; no cookie value recorded |
| 4-0 | `LiveApiContractTest` is gated on the key AND `SMUGVIEW_LIVE_API=1`, not the key alone | With the key alone every `testDebugUnitTest` run would hit the live API |
| 4-0 | `FfHCms` keeps a 100-per-page image cap in the fake (`imageCaps`); every other gallery gets the live 500 | The Phase 3 scenarios pin its two-page shape (150 images) and must stay unchanged |
| 4-0 | Fake `cookieGate` answers 401 for a locked folder; the real anonymous `!children` of one is 404 | Observed, left as is: Phase 3 tests depend on the 401 and no Phase 4 step does |
| 4-0 | The big folder `Kp7Wq2` joins the root listing only after `listBigFolder()` | So the Phase 3 scenarios see the root they were written for |
| 4-3 | A node listing with no `Pages` block is treated as complete (total = rows seen), and `Pages.Total` of 0 is ignored when rows came back | The `Pager` stops on `count == 0` or the total; stubbed answers in the older repository tests have no `Pages` and would otherwise repeat the same page until `PageCap`. A real answer always has `Pages` |
| 4-3 | `fetchAlbumsInScopeRemote` now rethrows `CancellationException` instead of swallowing it with the per-folder `catch (Exception)` | It now makes several requests per folder; a cancelled scan must stop, not carry on to the next folder (design 3.2: the pager never catches cancellation) |

## 10. findings.md rows

| R / # | Planned | Note |
|---|---|---|
| R-23 `Password=` on GETs | 4-7 (after 4-6), Q2 | 15 methods; none accepted (P1) |
| R-27 `NextPage` drops `_expand` | 4-3 (folders), 4-4 (gallery videos), 4-5 (search, crawl refactor) | user!albums no longer pages via NextPage (crawl since 2-8) |
| R-28 `topkeywords` `NodeURI` | 4-1, Q6 | |
| R-29 `ImageSizeDetails` stripped | 4-9 | WebUri part of the review is wrong for `AlbumImage` (P4) |
| R-30 `image/{key}` 301 | 4-9 | |
| R-31 search >10,000 | 4-5 | 500 confirmed; unreachable today (§1.3); window made an invariant |
| R-32 locked `!images` | 4-8, Q5 | |
| R-33 hardcoded root | 4-10 | |
| R-34 Hub totals | 4-10, Q3 | |
| R-35 refresh within 5 min | 4-6, Q1 | folder Refresh and crawl already fixed (2-4, 2-8) |
| R-36 cache key ignores the cookie | 4-6 | dot no longer affected (2-8); Hub after unlock still is |
| R-37 originals in the shared cache | 4-11 | worker fixed in Phase 0; Coil remains |
| new: search `count` clamped to 100; Tags stops at 9,600 | 4-5 | P6, P13 |
| new: `user!imagesearch` takes `q`, not `Text`; method dead | 4-1 | P14 |
| new: WebUri→gallery lookup and `fetchAlbumsInScopeRemote` read page 1 only | 4-10, 4-3 | §1.3 |
| new: API responses carry no `ETag`/`Last-Modified` (SMUGMUG.md's "304" is wrong) | 4-12 docs | P11 |
| new: anonymous `user!recentimages` returned a photo from a gallery under the password folder Family | **not in scope** (T10, with R-53); recorded for the owner | P16 |

## 11. What in the review is wrong or stale at `feab265`

- **R-35** is mostly fixed: Refresh Folder (2-4) and the crawl (2-8) send `no-cache`; search is never
  cached. Left: galleries and the Hub.
- **R-36** "directly affects the dot redesign": no longer; the crawl sends `no-cache` (2-8). It still
  affects the Hub after an unlock, and `Password=` currently hides it elsewhere, so **R-23 must not
  land before R-36's fix** (the review's Phase 4 row lists them side by side with no order).
- **R-31** "retried 5×, then toasted": the 500 is real, but no current path sends such a request.
  The real search defect is that `count=500` is clamped to 100 (Tags stops at 9,600).
- **R-37**: Phase 0 fixed the download worker only; Coil still fills the shared cache, including
  originals via the zoom fallback (R-29).
- **R-29** "`WebUri` never present on image payloads": it is present on `album!images` photos; the
  app just doesn't ask for it there.
- **R-28** "165 vs 77": reproduced as 165 vs 11/4/3/0 depending on the folder.
- **R-27** "galleries 101+ have no thumbnail": `user!albums` isn't paged by `NextPage` any more; the
  live cases are folder covers past child 100 and gallery videos past #500.
- **Phase 4 row "correct param names (`Order`…)"**: nothing sends `SortMethod`/`Order` to
  `user!albums` since 2-8; only `NodeURI` (and `q`, on a dead method) remain.
- **CLAUDE.md** "RetryingCallFactory retries every 5xx, including OkHttp's synthetic 504": stale
  since 2-1 (Q7). **SMUGMUG.md** l.36/145 (Password= unlocks) and l.294 (ETag 304) are wrong.

## 12. Dependencies on Phase 3, and what is deferred

Must not regress (their tests stay green **unchanged** unless a step says otherwise):
`BrowserNavigator` (one job, token-checked publish, `Refresh(force)` = `no-cache` on every page),
`AlbumLoader` (token, LRU of 3 complete galleries, partial-failure keep, 3-page flush), `UnlockManager`
(one `!unlock` flight per root; background paths never delete; `reauthorize` on the first page of a
read: callers keep their `password` argument for it after 4-7), `SiteSession` (cancellation reaches
`Pager` between pages), `GalleryCrawl` (`crawlMutex`, `coveredEpoch`, all-or-nothing, 15-minute gate,
`no-cache`), and the 2-1 synthetic-504 rule. Scenario suites to watch: `OpenBWhileAStreamsTest`,
`BrowserNavigationScenarioTest`, `UnlockRacingCrawlTest`, `PasswordPromptScenarioTest`,
`ProcessDeathScenarioTest`, `SwitchSiteMidSyncTest`.

Deferred: **Phase 5** — `OfflineDownloadWorker` and the album-download path (T6; 4-8 only adds the
locked message to Collections downloads). **Phase 6** — empty/locked/10k wording (R-47), the Hub
featured list's order, sharing `WebUri` vs the original link and EXIF (T10, R-53), the password
prompt outside Browser/PhotoGrid (Q5 b), image-details cache lifetime (R-61), and the P16
observation. **Not scheduled** — R-65's snapshot filenames (the contract tests supersede them for
Phase 4's purposes).

## 13. Owner sign-off (2026-10-01)

All seven questions answered as recommended: Q1 (a) 5-minute reuse, never reuse a response sent before the last cookie change, Refresh/Retry always hit the network, a gallery with a lit dot opens from the network. Q2 (a) stop sending `Password=` on every GET (4-7 stays after 4-6). Q3 (a) Hub totals from the gallery index. Q4 (a) commit trimmed real fixtures. Q5 (a) Cast/download of a locked gallery stops with "This gallery needs its password. Open it once to unlock it." Q6 (a) Tags shows only the scoped folder's keywords. Q7 yes, edit `CLAUDE.md` in 4-12.
