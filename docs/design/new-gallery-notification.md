# Design: notify me when a followed site gets a new gallery

Status: **PROPOSAL, nothing built.** Needs the owner's sign-off on section 6. Written 2026-10-03.
Related: [new-gallery-indicator.md](new-gallery-indicator.md) (the dot, still "needs revision"), findings #14, #16.

## 1. Expected behaviour

- In the Hub, each site has a "Notify me of new galleries" switch (per site; off by default).
- While it is on, the app checks in the background about every few hours. A **new** gallery (one the site did not have at
  the last check) posts one Android notification per check, grouped ("3 new galleries on idzifamily"). Tapping it opens
  the site, or the gallery when there is exactly one.
- An **updated** gallery (new photos in an old gallery) does not notify. That is the dot's job.
- Galleries under password folders are included, using the password the app already saved.
- A delay of hours is accepted.

## 2. Research: is there a better way than polling? (done 2026-10-03)

| Option | Result | Evidence |
|---|---|---|
| Webhooks, events, push, subscriptions | **None exist.** The API root lists only `AlbumBase ... WebUriLookup`; the release notes mention none; no third-party project found uses any. SmugMug has no Zapier app either. | `GET /api/v2`; Development Lab release notes; web search |
| Atom/RSS feed (`/hack/feed.mg?Type=nickname&Data=<nick>&format=atom10`) | Works, but **worse than the API**: public galleries only (0 of 100 entries under Family, the password folder), capped at 100 entries, 129 KB per fetch, no created date (`updated` only), no NodeID. Cached `max-age=3600`. | curl on idzifamily |
| HTTP conditional requests (ETag / 304) | **Not available.** `user!albums` answers `Cache-Control: no-store`, no ETag. | response headers |
| `user!albums` with `Order=Date Added (Descending)` | **Works and is the cheapest.** Sorts by the *node's* `DateAdded`, which the album payload never returns (this is why finding #14 saw "no sort by any date it returns"). Over all 118 anonymous galleries that have a node date: 0 sort violations in 117 comparisons; newest 2026-09-28, oldest 2012-01-02. `Date Added (Ascending)` returned 2012 first, so it is not the same as `Last Updated`. | curl, full crawl, `_expand=Node` |
| `node/{id}!children` with `SortMethod=DateAdded` | Also sorts by `DateAdded`, but is per folder (a tree walk). Not needed. | curl |

**Conclusion:** polling is unavoidable, and the best poll is **one request per site**: page 1 (100 newest by date added) of
`user/{nick}!albums?Order=Date Added (Descending)`, with a minimal `_filter` (NodeID, Name, WebUri, UrlPath), then a set
diff against the known NodeIDs. That is 2 to 3 orders of magnitude cheaper than the 27-page crawl the dot design needs, and
it never needs the whole listing.

## 3. Design

**3.1 What "new" means.** A NodeID in the newest-100-by-date-added list that is not in `known_albums` for that site. A set
diff, not a date comparison, so there is no clock or timezone question. Out of scope (owner, 2026-10-03): a gallery that was created hidden and
later made visible is not a "new album" for this feature, so it need not notify.

**3.2 One owner.** A new table `notify_known_albums(site, nodeId)` plus `notify_sites(site, enabled, baselineDone)`. It does
**not** touch `cached_albums`, `cached_nodes` or the dot (CLAUDE.md: a third owner of "new" is a smell, so this is
deliberately a separate question: "have I told the owner about this gallery?", not "is it unviewed?"). Room migration
(one-way): needs sign-off.

**3.3 Baseline.** Turning the switch on records page 1 as known and notifies nothing. Without this the first check would
announce 100 galleries.

**3.4 Worker.** `PeriodicWorkRequest` (unique, one per site is unnecessary: one worker loops over enabled sites), 6 h
interval, flex 2 h, `NetworkType.CONNECTED` (or UNMETERED, following the existing `OfflineSettings` rule so there is no new
network switch). Never `Result.failure()`; a failed site is skipped until the next run.

**3.5 Passwords (your requirement).** `PasswordStore` already keeps them in `EncryptedSharedPreferences` (Keystore
AES256-GCM keys, `allowBackup=false`, data-extraction rules). Nothing new is stored. The worker:
1. Reads the saved password roots for the site from `PasswordStore` (in memory only, never logged: `Redactor` already
   covers `Password=` and saved values).
2. POSTs `node/{id}!unlock` for each (this fills the OkHttp cookie jar, finding #16), then lists albums.
3. A non-200 unlock (changed password) skips that root and records it; the other roots still run. No notification about
   the failure (a later "locked folder needs a new password" nudge is out of scope).
4. Needs checking before build: the unlocked listing must still honour `Order=Date Added`. **Not verified**, because it
   needs your password typed (nobody else types it). Fallback if it does not: sort client-side on the first page of each
   listing.
The new-gallery notification text never contains a password or the API key.

**3.6 Notification.** Channel "New galleries" (default importance), group summary, `POST_NOTIFICATIONS` requested the
first time a switch is turned on (Android 13+); if denied, the switch shows a one-line explanation and stays off.
Strings are user-visible and go to the owner for approval before shipping.

## 4. How it fails (decided before building)

- Offline / 429 / 5xx mid-poll: abandon this run, keep the old known set, retry at the next interval. Never write a
  partial known set.
- Notification permission denied or revoked: the worker still runs but posts nothing; known set is not advanced, so you are
  not told about the same galleries twice nor skipped.
- Site removed or switch turned off: cancel its rows. A deleted gallery just drops out of the diff.
- Process death between poll and notify: write the known set **after** the notification is posted, so the worst case is a
  repeated notification, not a lost one.
- Battery optimisation (Doze): the interval is a hint; hours of delay is accepted.
- Gallery appears and is deleted between polls: never seen, no notification.

## 5. Stages (each reviewed and committed on its own)

1. **Detector** (pure): diff function, baseline, known-set table + migration, with tests built from the real shape
   (password folder -> sub-folder -> gallery; AlbumKey != NodeID; dates relative to now; `insertNodes`-style REPLACE fakes).
   Red-first: a gallery missing from the known set is reported; the baseline reports nothing.
2. **Worker + scheduler**, including the password unlock path, with a `work-testing` test (`work-testing` is already a dependency).
3. **Notification + permission + Hub switch**, with strings approved.
4. **Device checks (phone, last):** one real new gallery detected on idzifamily including one under Family, the
   permission flow on Android 17. Emulator first for everything else.

## 6. Choices that need the owner

1. Room migration for `notify_known_albums` and `notify_sites` (one-way).
2. Poll interval: 6 h proposed (flex 2 h). Shorter costs battery and gains little, since Android batches background work.
3. Network: follow the existing offline network rule (`OfflineSettings`), or Wi-Fi only for polling regardless?
4. Tapping a grouped notification: open the site, or a list of the new galleries?
5. OK to type your Family password on an emulator once to verify 3.5 item 4, or do you prefer to check it on the phone?
