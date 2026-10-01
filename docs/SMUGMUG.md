# SmugMug API v2 System & Core Interface Learnings

This document records the architectural details, endpoint definitions, expansions, and gotchas identified while integrating the SmugMug API v2 into the SmugView application.

> Scope note: this doc covers **SmugMug API v2 traffic only**. Casting to Chromecast/Roku/Fire TV
> uses a separate local-network HTTP server (the "Web Companion") that never talks to SmugMug's
> servers — see **"Casting & the Web Companion Server"** in [DESIGN.md](DESIGN.md).

---

## 👥 SmugMug Expert Personas & Audit Framework

To maintain a resilient and high-performing integration, any future changes or optimizations related to the SmugMug API or CDN must be reviewed against these five expert focus areas:

1. **The API Hierarchy & Expansions Specialist**:
   * *Role*: Audits graph navigation, entity relationships, and expansions (`_expand`).
   * *Goal*: Merge requests and retrieve parent-child dependencies inline to prevent N+1 queries.
2. **The Serialization & Payload Optimization Architect**:
   * *Role*: Audits JSON deserialization size and speeds on the mobile client.
   * *Goal*: Enforce minimal verbosity (`_verbosity=1`), flat URI models, and strict server-side filtering (`_filter`) to download only required properties.
3. **The CDN, Caching & Media Delivery Engineer**:
   * *Role*: Audits image loaders (Coil), caching configurations, and prefetching/preloading logic.
   * *Goal*: Prevent duplicate image loads, optimize local cache retention, enforce aggressive disk caching, and configure ExoPlayer caching.
4. **The Security, Sessions & Auth Handler**:
   * *Role*: Audits password flows, POST `!unlock` actions, session cookies, rate-limiting, and network retries.
   * *Goal*: Implement reactive retries, store cookies persistently in OkHttp, and handle exponential backoffs for Rate Limit errors (429/5xx).
5. **The Offlining, Paging & Sync Coordinator**:
   * *Role*: Audits Paging 3 source configurations, local database caching (Room), and background worker syncing.
   * *Goal*: Implement query deduplication, local caching lookups, and prevent background worker infinite retry loops on network errors.

---

## 📡 API Authentication & Access Control

* **Public-Key Access**: Since this application operates in read-only guest browsing mode, full OAuth 1.0a signature flows are not required. The client authenticates successfully simply by appending the developer API Key as a query parameter: `?APIKey=YOUR_API_KEY`.
* **Guest Password Protections**: Password-protected folders and albums are unlocked by appending `&Password=YOUR_PASSWORD` directly to the request query parameters.

---

## 🔗 Endpoint Structure & Node Hierarchy

* **Profile Resolution**:
  - Endpoint: `GET /api/v2/user/{nickname}`
  - Purpose: Resolves the public nickname to the user's root node ID via `response.user.uris.node.uri` (e.g. `/api/v2/node/4zqWw` -> Root Node ID `4zqWw`).
* **Hierarchy Navigation & Paging**:
  - Endpoint: `GET /api/v2/node/{node_id}!children` (requests initial batch of up to 100 children subnodes)
  - Paging Endpoint: `GET {dynamic_url}` (traverses subsequent pages using `getNodeChildrenByUri` and the `pages.next` URI)
  - Purpose: Recursively lists and caches all child folders, albums, and pages under a specific folder node.
* **Album Data Retrieval**:
  - Detail Endpoint: `GET /api/v2/album/{album_key}` (gets title, style, configuration)
  - Images Endpoint: `GET /api/v2/album/{album_key}!images` (gets paginated album image assets)
* **Metadata & EXIF Details**:
  - Endpoint: `GET /api/v2/image/{image_key}!metadata`
  - Purpose: Queries camera-recorded metadata (exposure, focal length, aperture, camera brand, date taken).
* **Image Properties (Size & Naming)**:
  - The API Image objects return the actual filename using the `FileName` property, and the raw byte size using the `OriginalSize` property.
---

## ⚡ Expansion Queries (N+1 Prevention)

* **Node Highlights**:
  - Query: `_expand=HighlightImage` on child node requests.
  - Usage: Allows fetching the cover/highlight image URI for folders and albums concurrently with the hierarchy list, preventing separate HTTP queries for each item row.
* **Video Playback URLs**:
  - Query: `_expand=LargestVideo` on album image requests.
  - Usage: Returns video streaming URLs directly inside the payload expansions mapping.
* **Optimized Tag Scanning (AlbumKeywords)**:
  - Query: `_expand=AlbumKeywords&_filter=Uri&_verbosity=1` on batched album requests (`album/{id1},{id2}...`).
  - Usage: Allows extracting tags for multiple galleries simultaneously without pulling heavy image/node payloads.
  - *Example cURL*:
    ```bash
    curl -X GET -H "Accept: application/json" \
      "https://api.smugmug.com/api/v2/album/PxJ4h,bCDkX,mRt9P?APIKey={YOUR_API_KEY}&_expand=AlbumKeywords&_filter=Uri&_verbosity=1"
    ```

---

## ⚠️ API Gotchas & Critical Debugging Findings

### 1. The HTTP 200 OK "False Success" on Password Album Queries
* **Problem**: Calling `GET album/{album_key}!images` with a wrong or missing password returns an `HTTP 200 OK` response status instead of throwing an error (like `401 Unauthorized` or `404 Not Found`).
* **Symptom**: Standard try-catch exception blocks pass validation as successful, caching the incorrect password and rendering empty image grids to the user.
* **Solution**: The API excludes the `"AlbumImage"` key entirely from the JSON payload when locked. Validation logic must inspect the parsed body to ensure the images array is not null:
  ```kotlin
  val response = api.getAlbumImages(albumKey, apiKey, password)
  val isPasswordCorrect = response.response.images != null
  ```

### 2. HTTP 404 Not Found on Password Folder Queries
* **Behavior**: Unlike albums, calling `GET node/{node_id}!children` with an invalid password throws an `HTTP 404 Not Found` exception.
* **Handling**: Catch the HTTP exception to invalidate incorrect password credentials and trigger user password entry prompts.

### 3. Nested Sub-resource Lock Propagation
* **Problem**: Querying EXIF metadata (`image/{image_key}!metadata`) for photos inside a password-protected album fails with 401/404 errors, even if the photo's direct CDN image URL is accessible.
* **Solution**: You must pass the password query parameter downstream to the metadata call:
  ```kotlin
  @GET("image/{image_key}!metadata")
  suspend fun getImageExif(
      @Path("image_key") imageKey: String,
      @Query("APIKey") apiKey: String,
      @Query("Password") password: String? = null
  ): ExifResponse
  ```

### 4. Profile Avatar Fallback Flow
* **Behavior**: SmugMug hosts user bio and avatar media in different endpoints. Implement a multi-tier loading chain:
  1. **User BioImage API Key**: Call `GET user/{nickname}!bioimage` to resolve `Response.BioImage.ImageKey` and load CDN path: `https://photos.smugmug.com/photos/i-{ImageKey}/0/M/i-{ImageKey}-M.jpg`.
  2. **Direct Redirect URL**: `https://{nickname}.smugmug.com/bioimage`
  3. **Standard Avatar Path**: `https://secure.smugmug.com/users/{nickname}-avatar.jpg`
  4. **Initials Placeholder**: Jetpack Compose custom gradient letter box.


### 5. POST-based `!unlock` Action & Cookie Persistence Requirements
* **Problem**: Passing a `Password` query parameter directly to `GET node/{node_id}!children` does not unlock the resource — the server ignores it and returns a `404 Not Found` response when the node is password-protected.
* **Nuance — Code vs. Server Behavior**: The Android client *does* still send `Password` as a query param on the first attempt (defensively, in case SmugMug changes behavior or a future endpoint variant accepts it). The code then detects the 401/404 failure and falls back to the POST unlock pattern. Do not confuse the code's defensive pass-through with the API actually honoring it.
* **Album images behave differently**: `GET album/{album_key}!images` with a wrong or missing password returns **HTTP 200 OK** with an empty images array — not a 404. The unlock retry logic for albums therefore detects a null images array as the failure signal, not an HTTP error code.
* **Findings**: Under the hood, SmugMug's REST API requires a **POST** request to the endpoint's respective unlock action to establish a session:
  - **Folders/Nodes**: `POST node/{node_id}!unlock` (POST body parameters: `Password=xxx`)
  - **Albums**: `POST album/{album_key}!unlock` (POST body parameters: `Password=xxx`)
* **Session Persistence**: Unlocking successfully sets session cookies (`shm` and `SMSESS`). In OkHttp/Retrofit, you must configure a custom `CookieJar` on the client, otherwise cookies are discarded and subsequent GET requests remain unauthorized. The current implementation uses an **in-memory CookieJar** — cookies do not survive app process death, so `!unlock` will be re-invoked on next launch.
* **Repository Architecture & Rate Limiting Prevention**: Repeatedly calling the POST `!unlock` endpoint on every GET request (such as refreshing pages, loading folder children, paging through album photos) will trigger SmugMug's brute-force defense system. This security filter temporarily blocks the password and locks the resource (returning 401 Unauthorized), which appears to the user as if the password was changed.
  - To prevent this, the repository layer must employ a **reactive retry pattern**:
    1. Eagerly perform the GET children/details request first without sending a POST unlock request.
    2. If the GET request succeeds, return the results immediately (using existing OkHttp CookieJar session cookies).
    3. If the GET request fails with an `HTTP 401` or `HTTP 404` error and a password is cached locally, invoke the POST `!unlock` endpoint *exactly once* to establish a new session, then retry the original GET request.

### 6. user-level imagesearch vs. image!search — both are used, deliberately
> Corrected: this section previously claimed `user/{nickname}!imagesearch` was "legacy/deprecated —
> do not use it". That contradicted `DESIGN.md` §D and the shipping code, which calls **both**
> endpoints on purpose (`SmugMugRepository.searchImages` -> `image!search`, and a user-scoped
> `user!imagesearch`, whose Retrofit method was deleted in Phase 4 as dead code). Treat `DESIGN.md` §D as canonical.

* **`GET user/{nickname}!imagesearch` (primary, scoped)**: user-scoped and supports the `Password`
  parameter. Required for searching inside password-protected or `Searchable: No` albums. This is
  the main search path for the SmugView use case.
* **`GET image!search` (secondary, global index)**: queries SmugMug's global search index. Does
  **not** return results from albums marked `Searchable: No` or password-protected albums, even if
  unlocked in the session.
* **Scope Requirement**: Pass the user's root node URI (e.g. `/api/v2/node/4zqWw`) to the `Scope`
  query parameter to query the whole account. Passing a specific gallery/folder node URI restricts
  the search to that container.

### 7. Scoped Searches and Password Access
* **Problem**: Scoped searches on password-protected folders or galleries (even if unlocked via `!unlock` previously in the session) fail or return 0 images if the password query parameter is omitted on the search call.
* **Solution**: Retrieve the cached folder/gallery password from local session preferences (using the gallery key, node ID, or parent folder node ID) and explicitly supply it via the `Password` parameter to `searchImages`.

### 8. Custom Header `X-Ignore-Errors` for Silent Verification
* **Problem**: When the user is typing a nickname in the explorer view, incomplete inputs trigger immediate API checks. These requests often fail with `404 Not Found` responses, causing our standard network error interceptor to pop up disruptive "Not Found" Toast messages.
* **Solution**: Pass `@Header("X-Ignore-Errors") ignoreErrors: String?` (value `"true"`) on validation lookups. The OkHttp interceptor catches this header and bypasses UI Toast alerts, silently allowing the app to handle validation states.

### 9. Parent Gallery Identification in Scoped Search Results
* **Problem**: Photos returned by `image!search` omit the parent gallery's `AlbumKey`, making navigation links (like "Go to Gallery") impossible to populate directly.
* **Solution**: Ensure the `_filter` query parameter includes `Uris` and `_filteruri` includes `ImageAlbum` (e.g. `LargestVideo,ImageAlbum`). The repository maps this to `uris.imageAlbum`. If `imageAlbum` is null due to API redactions on locked/anonymous resources, the view model parses the gallery path segment from the photo's `thumbnailUrl` or `webUri`, matches it against all cached nodes, and injects the resolved album URI.

### 10. Bypassing Global Redactions on Password Unlocked Photos
* **Problem**: Even after successfully unlocking a password-protected gallery via `POST !unlock`, retrieving details for a photo in that gallery via `GET image/{key}` can return redacted/empty `uris.album` links.
* **Solution**: In `getImageDetails`, detect if `uris.album` is redacted/null on a successful image payload. Extract the parent gallery path slug segment from the thumbnail, look up its `resolvedKey` in `getAllCachedNodes()`, and if unlocked or public, manually construct and attach the correct album path `/api/v2/album/$resolvedKey` to override the redaction.

---

## 🚀 Advanced API Operations & Performance Tuning

### 1. Payload Minimization (`_verbosity=1`)
*   **Concept**: Setting `_verbosity=1` as a query parameter on GET calls tells the SmugMug server to strip optional and redundant metadata.
*   **Benefits**:
    *   Removes the bulky `Options` schema description (describing supported request methods and parameters).
    *   Strips `UriDescription`, `Locator`, `LocatorType`, and `EndpointType` from the Response section.
    *   Implies `_shorturis`, which converts links under the `Uris` object from nested metadata maps (e.g. `{"Uri": "/api/v2/...", "Locator": "..."}`) to simple key-value primitive strings (e.g. `"ChildNodes": "/api/v2/..."`).
*   **Result**: Reduces payload size by **60-80%** and speeds up GSON deserialization/parsing.
*   **Kotlin Implementation**: Refactored GSON models to represent URI containers as direct raw `String` values.

### 2. URL Path Lookup (`!nicknameurlpathlookup`)
*   **Endpoint**: `GET /api/v2!nicknameurlpathlookup?nickname={nickname}&urlpath={urlpath}`
*   **Usage**: Resolves any folder, album, or page directly using its user-friendly slug segment path.
*   **Example**: Resolving `/MVYSO/2026-07-03-MVYSO-4th-of-July-Fireworks` for nickname `idzifamily` returns the target Album object inline, containing its `AlbumKey` and other metadata parameters.

### 3. Multi-Get Request Pattern
*   **Concept**: Instead of fetching multiple objects of the same type sequentially, you can retrieve them in a single batch request by comma-separating their identifiers.
*   **Example**: `GET /api/v2/user/cmac,onethumb`
*   **Behavior**: The API changes the `LocatorType` to `Objects` and returns the payload under the target key as a JSON list.

### 4. Field Filtering — `_filter` vs. `_filteruri`
*   **`_filter`**: restricts which *properties* come back on the main response object (e.g. `Name,GalleryStyle,WebUri`).
*   **`_filteruri`**: restricts which entries appear in the `Uris` expansion map (e.g. `ChildNodes,Album,HighlightImage,ParentNode`). Every node/album/image endpoint in `SmugMugApi.kt` sets both — `_filter` alone is not enough to trim payload size, because the `Uris` map is filtered independently.
*   **Gotcha**: `getAlbumKeywords` deliberately sets `_filteruri = ""` (empty) — the keyword-scan batch call only wants the `AlbumKeywords` expansion itself, not any of the album's other `Uris` entries, so it blanks the filter rather than omitting it (an omitted `_filteruri` falls back to the server default, which is not empty).

### 5. Automatic Retry & Backoff (429 / 5xx)
*   **Where**: `AppModule.provideRetryingCallFactory` wraps the shared `OkHttpClient` in
    `RetryingCallFactory` (`data/api/RetryingCallFactory.kt`), used as the `Call.Factory` for both
    Retrofit (`provideSmugMugApi`) and Coil's image loader (`SmugViewApp.newImageLoader`) — so
    every SmugMug API call *and* every image load gets this for free, no per-repository-method
    retry logic needed.
*   **Behavior**: on a `429` or any `5xx` response, retries up to **5 times**. Delay starts at 500ms and doubles each attempt (exponential backoff: 500ms, 1s, 2s, 4s, 8s). For a `429` specifically, it first checks the `Retry-After` header (seconds) and sleeps that long instead of the computed backoff value if present.
*   **Non-blocking as of 2026-08-01**: this used to be a plain OkHttp `Interceptor` that called a
    blocking `Thread.sleep` on the dispatcher thread — under heavy concurrent load that could
    starve the dispatcher's thread pool (see AGENTS.md's Retro v4/v6 for the incident and fix).
    It's now a decorating `Call.Factory` that schedules retries on a `ScheduledExecutorService`
    instead, so a backing-off request releases the dispatcher thread instead of parking it.
    `Interceptor.intercept()` can't do this — it's a synchronous API by design, so retry logic had
    to move up a layer. Don't add a second layer of manual retry/backoff in repository code — this
    already covers every request made through the shared `Call.Factory`.

### 6. Concurrent Request Deduplication & Caching Optimizations
*   **Problem**: Parallel background synchronization and foreground UI activities triggered simultaneous, identical HTTP requests for the same folder node. Additionally, search operations called sequential requests for the same user root node ID, and pager swiping caused redundant album re-fetches and UI blanking out.
*   **Optimizations**:
    1.  **Node Request Deduplication**: Introduced a nodeId-based `Mutex` synchronization lock map (`nodeLocks`) in the repository. Concurrent requests for the same node suspend and wait for the in-flight network call to complete and populate the Room database, rather than triggering parallel API fetches.
    2.  **Early Cache Return**: Added early-returns (`return@flow`) to folder queries if Room cache is present and `forceRefresh` is false, bypassing network requests entirely for previously visited directories.
    3.  **Search Scope Query Reuse**: Resolved user root node ID once in search operations and cached it locally, eliminating sequential network calls.
    4.  **Same-Album Selection Short-Circuit**: In `selectAlbum`, checking if the requested album is already loaded (`albumKey == _currentAlbumKey.value && _rawPhotos.value.isNotEmpty()`) prevents clearing the image list and re-triggering album downloads during grid-to-detail navigation and swiping.

---

## 🛠️ Resolved Build, Database, and Compilation Gotchas

### 1. Short URIs GSON Deserialization Mismatch
*   **Symptom**: When `_verbosity=1` is applied to request headers or query parameters, SmugMug simplifies nested resource links. Instead of maps like `{"Uri": "/api/v2/node/xxx", "Locator": "Node"}`, it returns raw strings like `"Node": "/api/v2/node/xxx"`.
*   **Resolution**: Kotlin API response data classes (such as `UserUris`, `NodeUris`, and `AlbumImageUris`) must map these properties as primitive `String` types instead of nested wrapper classes. Ensure all downstream repo and view model callers remove the `.uri` attribute accessor and consume the raw string directly.

### 2. Room Database Columns and Schema Instability
*   **Symptom**: Introducing columns (like `webUri` to `CachedNode` in `Entities.kt`) causes the Room database build helper to throw runtime migration errors if the schema version is not updated.
*   **Resolution**: The `@Database` annotation version inside `AppDatabase.kt` must be incremented **and a real `Migration` must be written and registered** in `AppModule.provideDatabase`.

> [!WARNING]
> This section previously stated that `AppModule.kt` uses `fallbackToDestructiveMigration()` so no
> migration scripts are needed. **That is no longer true**, and following it will destroy user data.
> Blanket destructive fallback was removed because `offline_collections`, `collection_photos`, and
> `collection_bookmarks` hold user-created personal data that must survive schema changes.
>
> Current policy (`AppModule.provideDatabase`):
> * Real migrations are registered via `addMigrations(...)` — e.g. `AppDatabase.MIGRATION_12_13`.
> * `fallbackToDestructiveMigrationFrom(1..11)` covers only the legacy pre-v12 releases, which
>   shipped with destructive fallback and therefore have no upgrade path. Without this, users on
>   those versions crash on launch with "A migration from N to 13 was required but not found".
> * `exportSchema = true`; schemas land in `app/schemas` so migrations can be validated with
>   `MigrationTestHelper`. Write a migration test for every new migration.

### 3. Strict Compile Warnings-as-Errors (`-Werror`)
*   **Symptom**: Jetpack Compose experimental components (such as `SwipeToDismissBox` in Material 3) generate compiler warnings. If the Gradle compiler is set to treat warnings as errors (`-Werror`), the build will fail.
*   **Resolution**: Always explicitly decorate the enclosing Compose functions with `@OptIn(ExperimentalMaterial3Api::class)` to suppress the compiler warning and ensure clean compilation.

### 4. Resolving Parent Galleries for Search Images
*   **Problem**: Images returned by the SmugMug search API do not provide a reference to their parent gallery's `albumKey`. This makes it impossible for buttons like "Go to Gallery" to load the correct screen out-of-the-box.
*   **Resolution**: 
    1. Fetch all user albums using `getUserAlbums` and cache them in the view model.
    2. Parse the parent gallery path slug from the search photo's `webUri` (e.g. extracting `/MVYSO/2026-07-03-MVYSO-4th-of-July-Fireworks` from `https://gallery.idzifamily.com/MVYSO/2026-07-03-MVYSO-4th-of-July-Fireworks/i-xyz`).
    3. Perform a case-insensitive match against the cached user albums list to retrieve the correct `albumKey` and map it to the photo details screen.

### 5. Flow Builder Type Inference (`Not enough information to infer type variable T`)
*   **Problem**: Returning early from a Flow coroutine builder block using `return@flow` causes the Kotlin compiler to fail to infer the type variable `T` for the `flow { ... }` function block. This triggers compilation errors on subsequent generic calls like `Result.failure(e)`.
*   **Resolution**: Explicitly declare the type argument on the flow constructor (e.g., `flow<Result<List<CachedNode>>> { ... }`) to bypass builder inference constraints.


---

## 🔍 Learnings & Analysis from SmugMugCore.Net (C# SDK)

We reviewed the `SmugMugCore.Net` library codebase in `C:\src\kidzi\GitHub\SmugMugSync\SmugMugCore.Net` to extract key architectural patterns, endpoints, and object relationships.

### 1. Key API Endpoints Discovered
*   **Direct Album Search (`/api/v2/album!search`)**:
    *   **Parameters**: `Text` (query), `Scope` (URI path of user or node, e.g. `/api/v2/user/{nickname}`), `SortMethod` (`Rank`, `LastUpdated`), and `SortDirection` (`Ascending`, `Descending`).
    *   **Usage**: Allows direct remote queries to fetch matching galleries from the SmugMug server instead of relying only on the local database Room cache.
*   **Direct Node Search (`/api/v2/node!search`)**:
    *   **Parameters**: Same as album search (`Text` and `Scope`).
    *   **Usage**: Returns matching folders, albums, or pages directly from the network.
*   **Image Lookup by Key (`/api/v2/album/{albumKey}/image/{imageKey}-{serial}`)**:
    *   **Usage**: Represents the exact path schema to query individual photo details if the key and serial are known.

### 2. Client Architecture & Querying Patterns
*   **OAuth 1.0a Access Token Flow**: The SDK implements full access token authentication via RestSharp's `OAuth1Authenticator.ForAccessToken(apiKey, apiSecret, userAuthToken, userAuthSecret)`.
*   **Progressive Page Traversal**: Similar to the Android app, the C# SDK uses a progressive `while` loop that parses `Response.Pages.NextPage` from the returned JSON payload to continuously fetch subsequent pages in paged searches.
*   **Field Filtering (`_filter` & `_filterurl`)**: The C# code uses parameter filtering to limit the fields returned, reducing download bandwidth and parsing times.

### 3. Metadata Discovery (`ContentMetadataService.cs`)
*   **Double-Fallback Strategy**:
    1.  First attempts to extract camera EXIF and QuickTime movie properties using the `MetadataExtractor` library.
    2.  If an `ImageProcessingException` is caught, falls back to Windows Shell properties (`ShellObject.FromParsingName`) to extract `System.Title`, `System.Keywords`, `System.Document.DateCreated`, and `System.Media.Duration`.
*   **Keyword Cleanups**: Performs a cleaning regex on tags to remove uri-unfriendly characters like `&` and `-` for compatibility:
    ```csharp
    keyword.Replace("&", "").Replace("-", "")
    ```

### 4. Architectural Recommendations for SmugView Android
*   **Remote Folder/Gallery Search**: Integrate the `node!search` or `album!search` endpoints directly into the Android app's Search tab. When a search is triggered, the app can perform a concurrent remote search on `node!search` (using the user's root node as the `Scope`) to load folders and galleries from the SmugMug API. This ensures that the Folders and Galleries search results are 100% complete and populated instantly from the network, even on a clean install.

---

## 🎨 Advanced CDN & Caching Optimization Techniques

We implemented a set of deep optimizations to protect the SmugMug API and CDN resource limits while dramatically improving overall application response speeds.

### 1. HTTP Cache Control & ETag Validation
*   **Network Interceptor Overrides**: Configured a custom OkHttp `cacheInterceptor` that intercepts GET responses and adds `Cache-Control: public, max-age=300` (5 minutes) if the response is missing cache headers or marked with `no-store` or `no-cache`.
*   **ETag Caching**: Combined with OkHttp's `Cache` directory, this enables automatic conditional HTTP validation using standard ETags (`If-None-Match`). If the data has not changed on the server, the SmugMug API responds with `304 Not Modified`, saving user bandwidth and processing power.

### 2. Aggressive Image Loading Cache (Coil)
*   **Coil ImageLoader Factory**: Implemented `ImageLoaderFactory` on `SmugViewApp` to hook Coil up to the Hilt-provided singleton `OkHttpClient`. This routes all image file queries through the same caching interceptors and retry policies used by Retrofit.
*   **Ignore Restrictive Cache Headers**: Set `.respectCacheHeaders(false)` on Coil's `ImageLoader` configuration. This forces Coil to cache downloaded image bytes aggressively in its own local disk cache directory, ignoring any guest/temporary Cache-Control restrictions returned by the SmugMug CDN.
*   **Avoid Cache Invalidation on Rotation**: Removed the `onDestroy()` cache clearing calls (`diskCache?.clear()`) inside `MainActivity`. Since Android destroys and recreates activities on screen rotation, clearing the cache on destroy forced a re-download of every visible photo.
*   **Loader Instance Sharing**: Refactored Palette extraction features to query `context.imageLoader` instead of spawning new local `ImageLoader` instances, ensuring all previews are served instantly from the shared cache.

### 3. API Call Merging & Expansions
*   **BioImage Request Merger**: Added `_expand=BioImage` to the root profile request (`GET user/{nickname}`). The repository extracts the user profile bio avatar `imageKey` directly from the inline expansion map, eliminating a separate `user/{nickname}!bioimage` network roundtrip.
*   **AlbumKeywords Expansion & Key Parsing Suffix Gotcha**:
    *   To gather all keywords for galleries within a scope without downloading image payloads, we use `GET album/{albumKeys}?_expand=AlbumKeywords&_filter=Uri&_verbosity=1`.
    *   **Gotcha**: SmugMug API v2 keys expansions by the fully expanded sub-resource URI (e.g., `/api/v2/album/qRsz5f!keywords`). Simple path extraction using `uri.substringAfterLast("/")` yields `"qRsz5f!keywords"` rather than the clean album key `"qRsz5f"`.
    *   **Mitigation**: Always parse and clean expansion keys by removing query parameters or action symbols, such as stripping everything after and including the `!` symbol (e.g., `uri.substringAfterLast("/").substringBefore("!")`).

### 4. Server-Side Field Filtering (`_filter`)
*   To minimize JSON payload sizes and accelerate parsing times on the mobile client, we applied the `_filter` parameter to limit attributes on high-volume endpoints (field lists below reflect the actual `SmugMugApi.kt` defaults, which have grown since this section was first written):
    *   **User Albums / Get Album**: `Uri,AlbumKey,NodeID,Name,GalleryStyle,UrlPath,WebUri,SecurityType,Privacy,PasswordHint,ImageCount,Uris,LastUpdated`.
    *   **Get Node / Search Nodes**: `Uri,NodeID,Type,Name,Description,SecurityType,Privacy,PasswordHint,Uris,WebUri,ThumbnailUrl,DateModified` (`Privacy`, `ThumbnailUrl`, and `DateModified` were added later — `Privacy` because `SecurityType` alone can disagree with it on password-protected items, see the "Privacy vs SecurityType" retro note in `AGENTS.md`; `DateModified` powers the folder/gallery update-indicator feature).
    *   **Get Image / Search Images / Get Album Images**: `ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,Keywords,KeywordArray,Uris(,WebUri)`. `FileName`/`OriginalSize` support the save-to-device flow; `Keywords`/`KeywordArray` populate tag-filtering chips.

---

## 📂 Core Data Layer API Specifications & System Configurations

This section provides a structured guide to all Retrofit endpoint definitions, query parameters, configurations, and enums used in the data layer.

### 1. Data Layer API Methods (`SmugMugApi`)

| HTTP Method | API Endpoint / Path | Kotlin Function | Purpose & Parameters |
|:---|:---|:---|:---|
| **GET** | `user/{nickname}` | `getUserProfile` | Fetches the user profile details. Resolves root node and bio avatar image.<br>• *Params*: `nickname`, `apiKey`, `expand = "BioImage"`, `verbosity = 1` |
| **GET** | `user/{nickname}!bioimage` | `getUserBioImage` | Retrieves user profile bio image reference if not expanded in profile.<br>• *Params*: `nickname`, `apiKey`, `verbosity = 1` |
| **GET** | `node/{node_id}!children` | `getNodeChildren` | Lists folders, albums, and children subnodes.<br>• *Params*: `nodeId`, `apiKey`, `password` (optional), `filter`, `verbosity = 1` |
| **GET** | `node/{node_id}` | `getNode` | Fetches metadata for a single node.<br>• *Params*: `nodeId`, `apiKey`, `verbosity = 1` |
| **GET** | `album/{album_key}` | `getAlbum` | Fetches details (title, security hint, key) for a gallery.<br>• *Params*: `albumKey`, `apiKey`, `password` (optional), `verbosity = 1` |
| **GET** | `album/{album_key}!images` | `getAlbumImages` | Retrieves paginated images in a gallery (up to 500/request).<br>• *Params*: `albumKey`, `apiKey`, `password` (optional), `count = 500`, `start` (page 2+ is the same call with `start`; `Pages.NextPage` is never followed, it drops `_expand`), `expand = "LargestVideo"`, `filter`, `verbosity = 1` |
| **GET** | `image!search` | `searchImages` | Performs global public image search (scoped or unscoped).<br>• *Params*: `apiKey`, `scope`, `text` (search term), `sortMethod`, `sortDirection`, `count = 500`, `start = 1`, `filter`, `expand`, `verbosity = 1` |
| **GET** | `node!search` | `searchNodes` | Finds folders and galleries matching a keyword.<br>• *Params*: `apiKey`, `scope`, `text`, `password` (optional), `expand = "HighlightImage"`, `filter`, `verbosity = 1` |
| **GET** | `image/{image_key}` | `getImage` | Fetches details and metadata for a single photo.<br>• *Params*: `imageKey`, `apiKey`, `password` (optional), `expand`, `filter`, `verbosity = 1` |
| **GET** | `image/{image_key}!metadata` | `getImageExif` | Fetches EXIF/camera metadata for a photo.<br>• *Params*: `imageKey`, `apiKey`, `password` (optional), `verbosity = 1` |
| **POST** | `node/{node_id}!unlock` | `unlockNode` | Submits folder/node password for authentication session.<br>• *Params*: `nodeId`, `apiKey`, `@Field("Password") password`, `X-Ignore-Errors` header (always sent — unlock attempts must never pop the generic error Toast). |
| **POST** | `album/{album_key}!unlock` | `unlockAlbum` | Submits gallery/album password for authentication session.<br>• *Params*: `albumKey`, `apiKey`, `@Field("Password") password`, `X-Ignore-Errors` header (always sent, same reason). |
| **PATCH** | `image/{image_key}` | `updateImageMetadata` | Modifies keywords/tags list for a photo.<br>• *Params*: `imageKey`, `apiKey`, `@Body body: UpdateImageMetadataRequest` |
| **GET** | `user/{nickname}!albums` | `getUserAlbums` | Lists all galleries/albums in the user account.<br>• *Params*: `nickname`, `apiKey`, `count = 500`, `expand = "HighlightImage"`, `filter`, `verbosity = 1` |
| **GET** | *(Dynamic Url)* | `getUserAlbumsByUri` | Traverses subsequent pages of all user albums.<br>• *Params*: `url`, `apiKey` only — no other params (baked into `pages.next`). |
| **GET** | `album/{album_keys}` | `getAlbumKeywords` | Fetches keywords for one or more galleries (comma-separated keys).<br>• *Params*: `albumKeys`, `apiKey`, `password` (optional), `expand = "AlbumKeywords"`, `filter = "Uri"`, `verbosity = 1` |
| **GET** | `user/{nickname}!topkeywords` | `getUserTopKeywords` | Aggregates most used keywords/tags in user profile or folder node.<br>• *Params*: `nickname`, `apiKey`, `nodeId` (optional), `verbosity = 1` |
| **GET** | `image!search` | `getImagesByKeyword` | Specifically optimized query for keyword tags searching.<br>• *Params*: `apiKey`, `scope`, `text` (space-separated tag query), `count = 500`, `start = 1`, `filter`, `verbosity = 1` |
| **GET** | `user!search` | `searchUsers` | Looks up public SmugMug accounts by nickname/name fragment (site explorer autocomplete).<br>• *Params*: `apiKey`, `q` (query text — **not** `Text`), `verbosity = 1` |
| **GET** | `user/{nickname}!recentimages` | `getUserRecentImages` | Fetches the account's most recently added images (seeds the Home/Hub tab).<br>• *Params*: `nickname`, `apiKey`, `count = 4`, `password` (optional), `filter = "ImageKey,Title,Caption,ThumbnailUrl,WebUri,Uris"`, `filterUri = "ImageAlbum"`, `verbosity = 1` |

### 2. Core Application Enums

#### A. `BrowserTab` (defined in `SmugViewModel.kt`)
Represents the main navigation scopes of the SmugView browsing interface:
*   `Folders`: Tree traversal of user directories and galleries.
*   `Search`: Text search screen for photos, galleries, and folders.
*   `TagSearch`: Explore view showing top keyword tag clouds, autocomplete, and filters.
*   `Hub`: Entry dashboard with profile statistics, shortcuts, and navigation links.
*   `Collections`: Bookmark tabs or custom user groups.

#### B. `GalleryFilterType` (defined in `SmugViewModel.kt`)
Determines photo grid display filtering by media type:
*   `ALL`: Render both photos and video files.
*   `IMAGES`: Filter out videos, displaying only photos.
*   `VIDEOS`: Filter out photos, displaying only video items (with overlay play badge).

#### C. `TagFilterState` (defined in `SmugViewModel.kt`)
Defines the inclusion state of a tag pill filter:
*   `INCLUDED`: Images matching this tag are selected (forces logical OR/AND intersection).
*   `EXCLUDED`: Images containing this tag are explicitly discarded from results.

### 3. API Invocation Guidelines (Avoid Basic Mistakes)

*   **Pacing & Rate Limiting (429)**: Standard calls should be rate-limited or paced. For scoped searches or batch requests, maintain at least a `250ms` delay between sequential calls. Note this is in addition to, not instead of, the automatic 429/5xx retry interceptor described above.
*   **Hard Result-Window Cap on Keyword/Tag Search**: SmugMug's Elasticsearch-backed search backend rejects pagination once `from + size > 10000` ("Result window is too large"). With a 500-per-page size, the last safe 1-indexed `start` is **9501** — hardcoded as `TagSearchController.MAX_SEARCH_START`. The tag/keyword search loader stops paginating past this point rather than surfacing the error, and reports the loaded count as the total so the progress UI settles at 100% instead of stalling short. Any new keyword-search entry point must respect the same cap or it will hit this error on large tag result sets.
*   **Searchable Index Exclusions**: The global `node!search` or `image!search` endpoints omit unsearchable nodes. Combine network searches with local database lookups on `cached_nodes` using `title LIKE %query%`.
*   **Case Sensitivity**: Autocomplete queries must compare tags case-insensitively (`it.lowercase().contains(query)`).
*   **Stripping Key Suffixes**: Album key mapping for expanded resources requires removing the trailing action suffix (e.g. splitting `/api/v2/album/{key}!keywords` at `!`).




