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

### Owner's answers (2026-11-06)
- A tester (Danielle, on an iPad) saw many dots, most likely after unlocking a password folder: its galleries all arrive at once. So the unlock case is in scope (rule 2 above), not an afterthought.
- "First time on this phone" accepted as the rule; phones that already have data keep their dots (nothing retroactive).
- Found while reading the code: a gallery the tree walk indexed has no `ImagesLastUpdated` until the crawl fills it, so it is the crawl that first dates it. The baseline therefore applies to a row whose index date is being set for the FIRST time (`old == null` or `old.imagesLastUpdated == null`), not only to brand-new rows.

## Plan 2: share the picture, not just the link

### What exists today (read from the code)
The viewer's share button opens a dialog with a QR code, **Copy**, **Share link**, and a smaller **Share picture** button. Share picture sends the saved copy or the X3 rendition, with camera data and location stripped, and the link as the message. This works the same on Android and iOS in the shared code. I have seen the dialog on the iOS simulator. I have **not** seen the iOS share sheet carry the picture: that needs a real device.

So the picture share exists, but it is the third button in a dialog about links. The feedback ("only does the URL") fits that: people tap Share, see a QR code, and take Share link.

### What I need from you before building
Which screen was the feedback about: the photo viewer, the gallery header, or the Collections tab? The gallery header share is a link on purpose.

### Decided by the owner (2026-11-06): ask the size, then hand the file to the system share sheet
1. The viewer's Share button opens a small prompt: **Mobile size** or **Original size** (each with what it weighs: the original's size is already in the payload, `ArchivedSize`).
2. The choice opens the system share sheet with the picture as a **file**, which is where AirDrop lives on iOS and Quick Share / Nearby Share / any app that takes a picture lives on Android. No custom transfer code.
3. Camera data and location are stripped from both sizes (CLAUDE.md: renditions and originals keep the camera's EXIF, including serial numbers and the owner's name).
4. The link and QR code stay available, as a second entry in the same prompt ("Share link or QR code"), so nothing is lost from today.

### What is already in the code, and what is not
- Mobile size exists: the X3 rendition, stripped (`ShareFiles.prepare` on Android, `prepareShareFile` in `PhotoFileOps`, handed to `IosShare.shareJpeg` which passes the file URL to `UIActivityViewController`; on iPad it is anchored so it does not crash).
- Original size does not exist for sharing. The download path already knows how to get an original (`ArchivedUri`) and, when its address answers 404 (107 of 850 in one gallery, findings #24), to fall back to the largest rendition. Share reuses that, and says which size was actually sent.

### Two places share a picture today, differently (found 2026-11-06)
- **Photo viewer:** share button, a dialog (QR code, Copy, Share link, then Share picture).
- **Collections, a saved photo's row:** a 14 dp faded share icon that opens the system share sheet with the picture and link at once. No dialog, no size choice.
The size prompt must replace both, so a picture is shared the same way from either place; the link and QR stay a separate entry.

### Things the plan has to handle (from the code and CLAUDE.md)
- **Memory:** `ShareFiles` reads the whole picture into a byte array and strips it in memory. Fine for a 0.4 MB rendition, risky for a 20 to 50 MB original. The original path streams to a file in the cache directory and strips from the file; above a size cap (to decide, for example 60 MB) Original is not offered and the prompt says why.
- **Not a JPEG:** `JpegStrip` only knows JPEG. If the original is another type, Original is not offered for that photo (an unstripped copy would leak the camera's serial number). Mobile size is always a JPEG.
- **Video:** a video's `ArchivedUri` is a JPEG still, not the video (CLAUDE.md), so Original must never be offered for a video; the existing behaviour for video is kept and checked.
- **A saved offline copy** is used when there is one, so sharing works offline, as today.
- **The link as extra text:** the iOS share sheet gets the file and the link text together. Whether AirDrop sends both, or drops one, is NOT known and cannot be seen in the simulator. Decision after a real-device test: if AirDrop drops the file when text is present, the link moves out of the AirDrop items (it stays for Messages and Mail).
- **Failure states** the prompt must say plainly (CLAUDE.md "what the user sees"): offline with nothing saved (not "try again"); SmugMug rate limited (429); the original is gone but a smaller one exists (we send that and say so); out of disk space; the user cancels the sheet (no error).
- **Temp files:** the existing sweep of the cache directory after a day stays; originals are the reason to check it still runs.

### Tests
- Unit: which sizes are offered (JPEG original: both; non-JPEG original, video, over the cap, original missing: mobile only), what is sent when the original answers 404, stripping on both sizes (reuses the real-EXIF fixtures in `JpegStripTest`).
- Dialog test: the size prompt shows both options with sizes, and "Share link or QR code".
- iOS CI: a simulator screenshot of the prompt and of the share sheet (the sheet itself can be shown in the simulator, AirDrop cannot).
- Real device (a tester): AirDrop of both sizes to another Apple device, and Quick Share on an Android phone.

### Open questions for you
### Owner's answers (2026-11-06)
- **Ask every time**; no "remember my choice" (a remembered setting would need somewhere to be changed later).
- **Original that is not a JPEG, or very large:** offer it, with a warning (replaces "hide Original" above). The warning says camera details and location stay in a non-JPEG original, because the app can only strip a JPEG. Very large: show the size and let the person choose.
- **Context decides the popup** (owner, same day): a photo shares through the photo popup, a gallery or folder through the link popup, in the viewer and in Collections. Done in Collections for the existing popup; the size prompt will join the photo popup.
