# SmugMug API v2 System & Core Interface Learnings

This document records the architectural details, endpoint definitions, expansions, and gotchas identified while integrating the SmugMug API v2 into the SmugView application.

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
* **Hierarchy Navigation**:
  - Endpoint: `GET /api/v2/node/{node_id}!children`
  - Purpose: Lists child folders, albums, and pages under a specific folder node.
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


### 6. Unsearchable Albums and Scoped Image Search (`user/{nickname}!imagesearch`)
* **Problem**: SmugMug's global user-level image search (`user/{nickname}!imagesearch` with no `Scope` param) returns **0 results** for any photos inside albums/galleries marked `"Searchable: No"`.
* **Solution**: To search inside unsearchable albums, you must execute a **scoped image search** specifically restricted to each matching gallery's URI (passing `Scope = /api/v2/node/{node_id}` or `Scope = gallery.uri`).

### 7. Scoped Searches and Password Access
* **Problem**: Scoped searches on password-protected galleries (even if unlocked via `!unlock` previously in the session) fail or return 0 images if the password query parameter is omitted on the search call.
* **Solution**: Retrieve the cached folder/gallery password from local session preferences (using the gallery key, node ID, or parent folder node ID) and explicitly supply it via the `Password` parameter to `searchImagesUser`.


### 8. Crawl Cap & Prioritization Budget for Global Scoped Search
* **Problem**: Performing sequential scoped API searches across a large number of cached albums during a global search fallback is extremely slow and triggers SmugMug's rate limiter (HTTP 429 Too Many Requests).
* **Solution**: Implement a hard crawl cap (e.g., maximum 40 albums). To maximize the effectiveness of this cap, split the cached albums list into `passwordAlbums` and `publicAlbums`, placing the `passwordAlbums` first (i.e. `(passwordAlbums + publicAlbums).take(40)`). Password-protected albums are **definitively never** returned by the unscoped global API search step, so they must be crawled explicitly. Public/searchable albums are placed after the password ones in the budget.
* **Caveat — Public Album Indexing**: The assumption that public albums are "fully covered" by the global search step only holds if SmugMug has fully indexed those albums. Very recently uploaded images, galleries with low crawl frequency, or accounts with misconfigured searchability settings may produce gaps. For completeness, including public albums in the crawl budget tail (after password albums) still adds coverage for these edge cases within the budget cap.

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

### 4. Concurrent Request Deduplication & Caching Optimizations
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
*   **Resolution**: The `@Database` annotation version inside `AppDatabase.kt` must be incremented. Because `AppModule.kt` configures the Room instance with `fallbackToDestructiveMigration()`, Room automatically recreates the tables on app startup, avoiding manual migration script writes for cached data.

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
*   To minimize JSON payload sizes and accelerate parsing times on the mobile client, we applied the `_filter` parameter to limit attributes on high-volume endpoints:
    *   **User Albums**: Requests only `AlbumKey,Name,GalleryStyle,UrlPath,WebUri`.
    *   **Search Nodes**: Requests only `Uri,NodeID,Type,Name,Description,SecurityType,PasswordHint,Uris,WebUri`.
    *   **Get Image**: Requests only `ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,Format,OriginalWidth,OriginalHeight,Uris,WebUri`.
    *   **Get Album Images**: Requests `Keywords` alongside basic metadata to ensure tag-filtering lists are fully populated.



