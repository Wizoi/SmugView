# Two plans from tester feedback (2026-11-06): first-visit blue dots, and sharing the picture

Status: PLANS FOR THE OWNER'S SIGN-OFF. No code has been written for either. Both are shared code (`:shared`), so they change Android and iOS together.

## Plan 1: no blue dots on a site's first visit

### What the feedback says
A person who opens a site for the first time sees blue dots on everything. They should start clear; only later updates should light.

### What happens today (read from the code, not guessed)
`CollectionDao.getNodesWithActiveUpdates(nickname)` is the one place that owns the dot. A gallery lights when its index `ImagesLastUpdated` is within the last 30 days AND it has no "viewed" mark (`viewed_gallery_updates`) or the mark is older. On a first visit there are no marks, so **every gallery updated in the last 30 days lights, and every folder above it lights too**.

### How many, on real sites (live API, 2026-11-06, anonymous `user!albums`, counted over every page)
| Site | Galleries visible | Updated in last 30 days (blue on first entry) |
|---|---|---|
| idzifamily | 128 | 25 (and 2,633 galleries once the Family folder is unlocked, so far more) |
| corvettemuseum | 34 | 1 |
| smugmugfilms | 7 | 0 |
| luxurycars | 28 | 0 |
| Tutorial | 8 | 0 |
So the complaint is real for an active site and invisible on a quiet one.

### The rule (decision: "new" = updated after this phone first saw it)
A gallery is new only if its `ImagesLastUpdated` is later than the moment this phone first saw that gallery's area. Nothing is synced between phones (no accounts), so "first" means first on this phone.

### How, with no database change and no migration
Reuse the existing meaning "viewed = the gallery's index ImagesLastUpdated". Add one step at two moments, and nowhere else:
1. **First full index of a site on this phone** (no `cached_albums` rows for that nickname before the crawl, and the crawl completed every page): in the same transaction as the index write, write a viewed mark equal to each gallery's `ImagesLastUpdated`.
2. **First index of a newly unlocked password root** (`indexUnlockedSubtreeInBackground`, the batch the session reveals): same, for that batch only. Without this, unlocking Family would light hundreds.
A later sync that finds a gallery with a later date, or a brand new gallery, writes **no** mark, so it lights, and it bubbles up to its folders exactly as today.

### What does not change
- Phones that already have data keep the dots they have. (Decision: no retroactive clearing.)
- The "viewed" rule, the 30-day window, the three-dates rule (ImagesLastUpdated only), and the Hub's new-gallery notifications.

### Failure design
- Offline or partial first crawl: no marks until a crawl finishes all pages (a partial crawl must not mark half a site).
- Process death after the index write: marks are written in the same transaction, so both or neither.
- A gallery updated between the first crawl and the first look the same day: its date is later than its mark, so it lights (correct).
- Switching sites: per nickname.
- Fakes must behave like Room (`insert` is REPLACE).

### Tests (each fails on the old code first)
Real topology (password folder, sub-folder, gallery, depth 2 or more), real ID shapes (AlbumKey is not NodeID), dates relative to now:
1. First crawl of a site with 5 recently updated galleries: none lit.
2. A later crawl with one gallery's date moved forward, and one new gallery: exactly those two light, and their folders.
3. First unlock of a password root: none lit; a later update below it lights.
4. A partial first crawl: nothing marked.
5. An existing site with marks already: untouched.

### Open questions for you
a. Is "first time on this phone" the right definition? (The alternative needs accounts, which the app does not have.)
b. Keep existing dots on phones that already have data? (Recommended: yes.)

## Plan 2: share the picture, not just the link

### What exists today (read from the code)
The viewer's share button opens a dialog with a QR code, **Copy**, **Share link**, and a smaller **Share picture** button. Share picture sends the saved copy or the X3 rendition, with camera data and location stripped, and the link as the message. This works the same on Android and iOS in the shared code. I have seen the dialog on the iOS simulator. I have **not** seen the iOS share sheet carry the picture: that needs a real device.

So the picture share exists, but it is the third button in a dialog about links. The feedback ("only does the URL") fits that: people tap Share, see a QR code, and take Share link.

### What I need from you before building
Which screen was the feedback about: the photo viewer, the gallery header, or the Collections tab? The gallery header share is a link on purpose.

### Proposal (smallest change that fixes it)
1. In the photo viewer, make the share button lead with the picture: the dialog's main filled button becomes **Share picture**; **Share link** and **Copy** become the quieter secondary buttons; the QR code stays below, as "Or scan to open".
2. Fix the label under it ("Camera details and location are removed") so it stays next to the picture button.
3. No new screens, no new permissions, same strip-the-camera-data rule.

### Larger options (not recommended for this round)
- Tapping Share opens the system share sheet with the picture straight away, with the link and QR in a second place (more disruptive: two jobs on one button).
- Share several photos from the grid at once (a separate feature).

### Tests
- Dialog test: the picture button is the primary one and is present when a picture can be shared (extends `QrShareDialogTest`).
- iOS: a simulator screenshot of the dialog (CI) and a real-device check by a tester that the share sheet carries the picture.

### Open questions for you
a. Which screen was the feedback about?
b. Proposal as written, or do you want the system share sheet to open straight away?
c. Should the picture sent stay the X3 rendition (what it does now: in the one case measured it was about 428 KB against 689 KB for the original; its pixel size is not recorded here) or be the full original when available?
