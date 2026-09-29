# SmugView — working rules

Short on purpose. Every rule here has cost time on this project. The longer reasoning, environment
notes and retros live in [.agents/AGENTS.md](.agents/AGENTS.md); open problems live in
[docs/findings.md](docs/findings.md). Read findings.md before starting on a bug.

## Running things

- Gradle needs the Android Studio JBR. From Git Bash:
  `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew.bat testDebugUnitTest`
- **Don't pipe Gradle into `tail`/`grep` and read success from the output.** The pipe reports
  `tail`'s exit code. Redirect to a file and check `$?`, or grep for `BUILD SUCCESSFUL`.
- adb is at `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`. Use `export MSYS_NO_PATHCONV=1`
  for `adb shell` commands with `/data/` or `/sdcard/` paths, but not in the same command as the
  JBR export.
- Release builds are not debuggable (`run-as` fails) and currently log nothing (findings #7). To
  read a device's state, check the live API first, then a debug build on an emulator.
- Live API, read-only: `curl "https://api.smugmug.com/api/v2/...?APIKey=$KEY"` with the key from
  `local.properties` (`smugmug.api.key`). Never echo the key.

## Before changing behaviour

- **Check the premise against real data before writing a fix.** Run a curl against the live API
  and look at what the payload actually contains. In v0.7.6, four "obviously right" fixes shipped
  broken (findings #2, #3, #5, #6). Two minutes of curl would have caught #2 and #5.
- **Name where each side gets its data.** The dot reads `cached_nodes`; change detection reads
  `cached_albums`, fed by an *anonymous* `user!albums`. They disagreed, and that disagreement
  *was* the bug (findings #1, #4). AlbumKey ≠ NodeID (findings #5).
- **The second fix for one symptom is not a fix.** Retro v6 and v0.7.6 both patched the "new"
  dot. Before a third, answer: which single place should own "is something new here?"
- **Every fix gets a test that fails on the old code first.** Run it before the fix and say that
  it failed, and how. Don't use `git stash` for this; comment the fix out, or run the test in a
  `git worktree` at `HEAD`.
- **A test's data must look like the real thing.** Mirror the real topology (password folder →
  sub-folder → gallery, depth ≥ 2), real ID shapes (AlbumKey ≠ NodeID), and dates relative to
  now (a hardcoded date aged out of the 30-day window, findings #9). Fakes must behave like Room:
  `insertNodes` is REPLACE, not append (findings #8).
- **Design for how it fails before building.** That means an offline request with nothing cached,
  a locked gallery, a 429, a background sync racing a screen, and process death. Also decide
  what the user sees in each intermediate state. The v0.7.6 offline change didn't consider the
  uncached case (findings #6).
- **Ask before a one-way or meaning-changing choice.** Examples: a Room migration, changing what
  "viewed" or "new" means, a new default the user never chose. Get a plan signed off before
  editing, even for a bug fix that "looks small". AGENTS.md already required this and was not
  loaded (findings #13).

## Things that are true here

- `cached_nodes` is filled lazily, on browse. `cached_albums` is synced eagerly, but only with
  what the anonymous listing can see. Anything under a password folder is invisible to it.
- Password folders are visible to `user!albums` only with the **session cookie** from
  `node/{id}!unlock`. The `Password=` query parameter does nothing there, and the cookie jar is
  in memory, so it is empty at every launch (findings #16).
- `user!albums` takes `Order`, not `SortMethod`/`SortDirection`, and even `Order` doesn't
  sort by any date it returns. Never stop a sync early on the assumption that it's sorted
  (findings #14).
- An album has three dates: album `LastUpdated`, `ImagesLastUpdated`, and node `DateModified`
  (≈ `ImagesLastUpdated`). They differ by days. Pick one per comparison (findings #15).
- A folder's `DateModified` is not a "something new inside" signal. It doesn't bump when its
  galleries change (findings #17), yet it does bump on site-wide events (findings #2).
- `RetryingCallFactory` retries every 5xx, including OkHttp's synthetic 504 from `only-if-cached`.

## Before committing or shipping

- Run the full unit suite: `./gradlew.bat testDebugUnitTest`.
- Record the finding's row and the commit in `docs/findings.md`.
- Publishing to Play, `git push`, and destructive git operations each need explicit
  confirmation in the moment. A plan approval doesn't cover them (see AGENTS.md "Silent Mode
  EXCLUSIONS").
- Google Play requires targetSdk within 1 year of the latest Android (API 36 since
  2026-08-30). A rejected upload can still burn its versionCode.
