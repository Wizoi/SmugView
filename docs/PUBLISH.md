# Publishing SmugView to Google Play (Public Release)

This doc collects everything needed to take SmugView from internal testing to a public
production listing on Google Play: the store listing content, the assets, and the
Play Console steps that have to happen outside the repo. It's meant to be read
alongside [`.agents/skills/publish_app/SKILL.md`](../.agents/skills/publish_app/SKILL.md)
(the mechanics of cutting a release) — this doc is about the *store page* and the
*production-readiness* gates, not the build/sign/upload steps.

---

## 1. App identity

| Key | Value |
| :-- | :-- |
| Package name (`applicationId`) | `com.smugview.app` |
| Current version | see `app/build.gradle.kts` (`versionName` / `versionCode`) |
| Current track | `internal` (see `playPublisher { track.set("internal") }` in `app/build.gradle.kts`) |
| Default language | `en-US` (`app/src/main/play/default-language.txt`) |

---

## 2. Store listing content (in-repo, pushed via Gradle Play Publisher)

All of this lives under `app/src/main/play/listings/en-US/` and ships to Play Console with
`./gradlew publishListing` (metadata only, no new build) or as part of `publishReleaseBundle`.
**Publishing to Play Console is not something to run automatically — confirm before
invoking either task.**

- **Title** — `title.txt` → `SmugView` (kept as-is; see the naming note in §5).
- **Short description** (80 char max) — `short-description.txt`:
  > Browse, search, and organize photos from any public SmugMug gallery.
- **Full description** (4000 char max) — `full-description.txt`: feature rundown (site
  browsing by nickname, password-protected galleries, search, tag filtering, immersive
  viewer, save/share, offline collections, casting to Chromecast/Roku/Fire TV), closing
  with a non-affiliation disclaimer.
- **Hi-res icon** — `graphics/icon/icon.png`, 512×512, derived from the app's launcher icon.
- **Feature graphic** — `graphics/feature-graphic/feature-graphic.png`, 1024×500. Went through
  two rounds: the first pass was five "aperture icon + glowing wordmark on gradient" variants
  that all read as the same generic template. Second pass, after running the UX Designer/UX
  Reviewer/PM personas' critique, produced five genuinely different concepts (real-screenshot
  phone mockup, photo mosaic, filmstrip, flat poster, kinetic motion-blur). Landed on the
  **real-screenshot phone mockup**: an actual in-app screenshot tilted in a phone frame over a
  duotone backdrop — shows the real product instead of another abstract logo lockup.
- **Phone screenshots** — `graphics/phone-screenshots/` — 5 real device captures at 1080×2424,
  Play's requirement (2–8) satisfied. These will ship with the next
  `publishListing`/`publishReleaseBundle`.

To see exactly what's currently live in Play Console at any point (read-only), run
`./gradlew bootstrapReleaseListing`. **Caveat learned the hard way**: this task overwrites
local listing files with whatever's live, including wiping versioned release-notes files
that don't match what the API returns — check `git status`/`git diff` immediately after
running it, before touching anything else, and restore via `git restore` if it clobbered
tracked files.

---

## 3. Privacy Policy

Drafted at [`docs/PRIVACY_POLICY.md`](PRIVACY_POLICY.md). Play Console **requires a public
URL** for this in the App Content section — a file in the repo isn't enough on its own.
Easiest path: enable GitHub Pages for this repo pointed at `/docs`, which publishes it at
`https://<user>.github.io/<repo>/PRIVACY_POLICY.html` (GitHub renders `.md` via Jekyll
automatically). Confirm the exact URL once Pages is enabled and paste it into Play Console →
App content → Privacy policy.

The policy reflects what the code actually does (verified against `app/build.gradle.kts`
dependencies and the manifest): no analytics/ads/crash-reporting SDK, no login, gallery
passwords stored locally via `EncryptedSharedPreferences`, browsing cache and offline
collections stored locally, and requests going straight to `api.smugmug.com`. If that ever
changes (e.g. an analytics SDK gets added), the policy needs a matching update.

---

## 4. Play Console steps that can't be done from the repo

These are manual, one-time (or per-release) steps in Play Console — nothing here can be
scripted via Gradle Play Publisher:

- [ ] **Data safety form** — based on the code: no data collected/shared with third parties
  beyond SmugMug itself (which the user directs by entering a nickname/password), no
  advertising ID, nothing sold. Encrypted local storage only.
- [ ] **Content rating questionnaire** — answer based on app functionality (photo browser,
  no UGC posting *from* the app itself — see the content-moderation note below).
- [ ] **Target audience & content** — this is a general-audience app, not for children.
- [ ] **Ads declaration** — No ads (confirmed: no ad SDK in `build.gradle.kts`).
- [ ] **App access** — declare that no login is required; all content is public SmugMug
  data (mention that some galleries need a password the *user* supplies, not a test account).
- [ ] **Store settings** — app category (Photography), contact email, external marketing
  opt-in.
- [ ] **Closed testing requirement** — newer personal Play developer accounts must run a
  closed test with enough opted-in testers for 14 continuous days before production access
  unlocks. Check this gate in Play Console if it hasn't been cleared yet — it blocks the
  production track independent of everything else on this list.
- [ ] **Production release** — once the above are green, promote a release to Production
  (staged rollout, e.g. 20% → 50% → 100%, is the safer default over a 100% release).

---

## 5. Two things worth deciding on, not just checking off

- **Naming/trademark exposure**: SmugView talks to SmugMug's public API under a name built
  from "Smug." Google Play's impersonation policy sometimes flags unofficial API clients
  named after the service they wrap. The full description's non-affiliation disclaimer
  mitigates this but doesn't eliminate it. Not urgent, but worth a conscious decision rather
  than discovering it via a Play Console rejection.
- **Shared API key at public scale**: the app compiles in a single developer SmugMug API key
  (`BuildConfig.SMUGMUG_API_KEY`, from `local.properties`) that every install shares. That's
  fine for internal testing; at public scale, every user's browsing/search traffic counts
  against that one key's SmugMug rate limit (the app already handles 429s with backoff, per
  `docs/SMUGMUG.md`, but backoff just degrades gracefully — it doesn't add capacity). Worth
  knowing this ceiling exists before a spike in installs, even though it's not a launch
  blocker.

---

## 6. Quick reference: publishing commands

From [`publish_app.yaml`](../.agents/resources/publish_app.yaml) — **all of these push to Play
Console and need explicit confirmation before running, every time**:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

# metadata / listing only, no new build
./gradlew publishListing

# full release: compile, sign, bundle release notes, upload to the configured track
# (this ALWAYS uploads a fresh bundle, which requires a brand-new, never-used versionCode —
# Play rejects re-uploading an existing versionCode to a different track)
./gradlew publishReleaseBundle

# move a build that's ALREADY on Play (e.g. sitting on internal) to another track WITHOUT
# rebuilding or bumping versionCode — this is the right tool for internal -> alpha/beta/production
./gradlew promoteArtifact --from-track internal --promote-track alpha
```

**Resolved 2026-07-31**: the "still in Play Console draft status" restriction noted below (as of
2026-07-18) no longer applies — verified on the v0.7.1 (versionCode 19) release, where both
`publishReleaseBundle` (internal) and `promoteArtifact --promote-track alpha` succeeded with the
default `completed` release status, no `--release-status draft` needed. For reference, the old
behavior: Play used to reject a `completed` release ("Only releases with status draft may be
created on draft app") for any track beyond internal until the §4 checklist below was cleared —
internal testing tolerated an incomplete listing, closed/open/production tracks didn't. Only add
`--release-status draft` back if a future publish actually hits that rejection again.

Switching the default `track` in `app/build.gradle.kts` from `internal` to `production` is the
actual "go public" switch for the *default* publish target — do that only once the checklist in
§4 is cleared. (There's no generic `-Ptrack=` CLI override actually wired into this project's
`play {}` block — `promoteArtifact --promote-track <name>` is the correct one-off way to target a
different track without touching the committed default.)
