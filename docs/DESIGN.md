# SmugView Android App Development Plan

This document outlines the configurations, API details, architectural guidelines, customizations, and roadmap for building the **SmugView** Android application. The goal of this application is to browse a preconfigured public SmugMug site directly, support password-protected galleries securely, and provide advanced search/filtering (including inclusive/exclusive tag filtering), as well as a full-featured photo detail experience.

---

## 🛠️ Application Configuration & Settings

All project-specific configurations and customization values are tracked here.

| Configuration Key | Expected Value / Format | Purpose |
| :--- | :--- | :--- |
| **App Name** | `SmugView` | User-facing name of the Android application |
| **Package Name** | `com.smugview.app` | Unique identifier for the Android application |
| **SmugMug Nickname** | *Dynamic User Input* | User enters a public SmugMug nickname (e.g. `username`) or custom domain to explore |
| **SmugMug API Key** | *TBD (Developer API Key)* | Required to authenticate requests to the SmugMug API v2 |
| **Default Node ID** | *TBD (e.g., Root Node)* | Starting Node ID for the app, representing the root folder or a specific sub-folder |

> [!IMPORTANT]
> **API Key Setup**: To request a SmugMug API key, register your application on the [SmugMug Developer Portal](https://api.smugmug.com/api/developer/apply).
> Never hardcode the API Key in the source code. Instead, store it in `local.properties` or retrieve it at compile-time using the Secrets Gradle Plugin.

---

## 📡 SmugMug API v2 Integration (Public Access & Security)

Since this app uses **public access only**, OAuth 1.0a signatures are not required. We can access public resources simply by passing the `APIKey` query parameter.

### 1. Base URL & Client Configuration
All API requests must target the SmugMug API v2:
```
https://api.smugmug.com/api/v2
```
*   **Headers**:
    *   `Accept: application/json`
    *   `User-Agent: SmugView-Android-App/1.0`
*   **API Interceptor / Resiliency**:
    *   Implement exponential backoff retry mechanism in OkHttpClient for handling temporary network dropouts or `429 Too Many Requests` status codes.
    *   Intercept API `404 Not Found` for profile lookups to show an explicit "User Profile Not Found" error rather than a generic network error.

### 2. Pagination (Paging 3 Integration & Recursive Children Loading)
All lists (Nodes, Images, Search Results) in SmugMug are paginated.
*   **Pagination parameters**: Use `_config` (to retrieve metadata like total counts and pagination URLs) and limit parameters.
*   **Next Page**: The API responses contain a `Response.Pages.Next` URI string.
*   **Implementation**: 
    *   **Images & Search Results**: Use Android's `Paging 3` library. The network data layer uses `PagingSource` implementations that extract the `Next` URI from the JSON response and execute subsequent network calls using this URI.
    *   **Node Children (Folders/Albums)**: For full local directory caching, the repository pages through child nodes recursively. It initiates the request with a count of 100, then executes sequential `getNodeChildrenByUri` calls using `pages.next` URIs in a loop with a small 100ms pacing delay until all children are fetched and cached in the local Room SQLite database.

### 3. Key Endpoints

#### A. Fetch User Profile (Dynamic Lookup & Verification)
Retrieve public user profile details to verify the nickname entered in the explorer and locate their root node URI.
- **Endpoint**: `GET /api/v2/user/{nickname}`
- **Query Params**: `APIKey={api_key}`
- **Response path to Node**: `Response.User.Uris.Node` or `Response.User.Uris.Node.Uri`
- **Error Handling**: If the nickname is invalid or returns a `404`, show a clear "Site Not Found" inline error. To prevent transient typos from throwing user-facing error flyout popups (Toasts) during real-time typing validation, pass `X-Ignore-Errors: true` as an HTTP header to tell the client interceptor to silence/ignore these lookup errors.

#### B. Fetch Node Details (Folder / Album Structure)
Discover the children of a folder or album node.
- **Endpoint**: `GET /api/v2/node/{node_id}!children`
- **Query Params**: `APIKey={api_key}&_expand=HighlightImage`
- **Key Parameters**: Inspect `Access` and `PasswordHint` fields. If `Access` equals `"Password"`, flag the node as locked in the UI (showing a padlock icon) and prompt the user for input.

> [!IMPORTANT]
> The expansion parameter is `HighlightImage` (the folder/album cover thumbnail), **not** `Image`. `_expand=Image` does not exist as a valid SmugMug expansion on this endpoint and will be silently ignored by the server.

#### C. Fetch Album Images (Including Locked Albums)
Retrieve the list of images/videos inside a specific album node.
- **Endpoint**: `GET /api/v2/album/{album_key}!images`
- **Query Params**: `APIKey={api_key}&Password={password}` (include the password parameter if the album is password-protected)
- **Response Fields**:
  - `Title`: Image caption/title.
  - `ThumbnailUrl`: URL for the preview image.
  - `ArchivedUri`: Direct link to the full-size image file.
  - `Date`: Date when the photo was taken (useful for sorting).
  - `DateTime`: Date when the photo was uploaded.

#### D. Search User Content
The app uses two distinct image search endpoints:
- **Primary (scoped user search)**: `GET /api/v2/user/{nickname}!imagesearch`
  - **Query Params**: `APIKey={api_key}&Scope={gallery_node_uri}&Text={search_term}&Password={password}`
  - **Usage**: The main search path. Supports the `Scope` parameter to target specific galleries. Required for searching inside password-protected or unsearchable albums.
- **Secondary (global index search)**: `GET /api/v2/image!search`
  - **Query Params**: `APIKey={api_key}&Scope={user_node_uri}&Text={search_term}`
  - **Usage**: Returns photos from SmugMug's global search index. Does **not** return results from albums marked `"Searchable: No"` or password-protected albums, even if unlocked in the session.

> [!IMPORTANT]
> Do not confuse these two endpoints. `image!search` is a global cross-user index search. `user/{nickname}!imagesearch` is user-scoped and supports the `Password` parameter. The scoped endpoint is the correct one to use for the SmugView use case.

#### E. Fetch Image Metadata (EXIF)
- **Endpoint**: `GET /api/v2/image/{image_key}!metadata`
- **Query Params**: `APIKey={api_key}`

### 4. Case-Sensitive API Enums & Layout Values

To prevent parsing or comparison mismatches in the client application, all API response strings must be mapped or compared using their exact, case-sensitive formats returned by the SmugMug API v2.

#### A. GalleryStyle Enums
These determine the layout settings of a gallery. They are camelCase and do NOT contain spaces:
*   `"SmugMug"` - Classic view with thumbnail column and primary image preview.
*   `"Traditional"` - Grid-based standard navigation.
*   `"Journal"` - Storytelling layout with large images stacked vertically.
*   `"Thumbnails"` - Grid of small previews opening to lightboxes.
*   `"Slideshow"` - Automated carousel display.
*   `"FilmStrip"` - Horizontal preview strip below active image.
*   `"Squares"` - Uniform 1:1 square crop grid.
*   `"CollageLandscape"` - Staggered layout favoring landscape ratios.
*   `"CollagePortrait"` - Staggered layout favoring portrait ratios.

> [!IMPORTANT]
> The API returns these strings as camelCase (e.g. `"CollageLandscape"`, `"CollagePortrait"`). Any custom client checks must normalize these strings by stripping spaces and converting to lowercase to prevent matching failures (e.g. `style.lowercase().replace(" ", "")`).

#### B. Node Type Enums
*   `"Folder"` - Structuring container node.
*   `"Album"` - Gallery container node.
*   `"Page"` - Custom web page.

#### C. SecurityType / Access Enums
*   `"Public"` - Viewable by everyone.
*   `"Private"` - Owner-only access.
*   `"Password"` - Requires entry of correct passcode validation.
*   `"Unlisted"` - Accessible only via direct URL.

---

## 🔍 Search & Advanced Tag Filtering Logic

The app must provide a multi-type search and a robust tag-based exclusion/inclusion filtering system:

### 1. Multi-Type Search
The search interface must query folders, galleries, and photos simultaneously, presenting results in categorized lists:
*   **Folders**: Local or API matches for folder names.
*   **Galleries/Albums**: Local or API matches for album titles.
*   **Photos**: Results returned by `/api/v2/image!search` with the scope constrained to the selected user.

> [!IMPORTANT]
> **Sequential Search-Cache Constraint**: To prevent race conditions, the remote gallery search must complete and write results to the local database *before* executing the scoped photo search. If run concurrently, the database query checks local cached directories before the API results are saved, returning 0 image results for unsearchable albums.

*   **Parent Gallery Key Resolution**: Because photos fetched via global search (`image!search`) do not return a direct parent gallery key, the app resolves it by:
    1. Parsing the folder/gallery URL slug segment from the photo's `thumbnailUrl` or `webUri`.
    2. Matching the segment against the local database cache of all visited nodes (`getAllCachedNodes()`).
    3. If the gallery is public, injecting the resolved album key path into `uris.album` to enable navigation.
    4. If the gallery is password-protected, automatically attempting to unlock the gallery using saved passwords in `passwordPrefs` before fetching metadata.



### 2. Interactive Tag Filtering Bottom-Sheet
A bottom sheet swipable UI displays all available keywords/tags from the active folder/album:
*   **Triple-State Tags**: Each tag chip can be cycled through three states:
    1.  **Neutral** (Default - grey chip, no effect).
    2.  **Include** (Tap once - glowing blue chip, photo must contain this tag).
    3.  **Exclude** (Tap twice - soft red chip, photo must not contain this tag).
*   **Clear All Option**: A prominent button to reset all tags to neutral.
*   **Empty State Handler**: If filters result in zero matches, show an informative illustration stating "No photos match your current tag combination" with a direct button to reset filters.

### 3. Local In-Memory Filtering Formula
Because the SmugMug API doesn't support complex logical operations (AND/OR/NOT) directly in queries, filtering is computed in the client-side domain layer using Kotlin Flows:
$$\text{visible} = (\text{includedTags.isEmpty()} \lor \text{photo.keywords.intersects(includedTags)}) \land (\text{excludedTags.isEmpty()} \lor \text{photo.keywords.disjoint(excludedTags)})$$

---

## 🖼️ Photo Detail Actions & Offline Collections

When a photo is viewed in detail/full-screen mode, the application must offer the following features:

### 1. Immersive Full-Screen Viewer
*   **Visual Design**: A pure black backdrop with translucent floating navigation and utility buttons.
*   **Swipe Gestures**: Implements a smooth, gesture-driven pager (using Compose `HorizontalPager`) to swipe between photos, accompanied by subtle page-slide animations.
*   **Quick Actions**: Double-tapping the image triggers a scale-animated heart to automatically add the photo to the "Favorites" collection. Long-pressing the image reveals a quick-info overlay.

### 2. Save to Device
*   Downloads the original high-resolution image (using the `ArchivedUri` or highest available size link) to the user's local device storage.
*   Must handle modern Android scoped storage permissions and display a custom Snackbar notification upon success or failure.
*   **Filename Decoding & Sanitization**: Prior to saving, the image resource name must be decoded using `java.net.URLDecoder.decode(segment, "UTF-8")` to handle special URI characters properly, followed by stripping extension suffixes and replacing OS-illegal file characters (e.g. `\ / : * ? " < > |`) with underscores to ensure compatibility with local storage providers.

### 3. Share Photo Link
*   Extracts the public SmugMug URL and triggers a native Android Share Intent (`Intent.ACTION_SEND`).

### 4. Detailed Metadata Viewer
*   Shows a bottom sheet displaying complete EXIF metadata fetched from `/api/v2/image/{image_key}!metadata` (Camera, exposure settings, shutter speed, aperture, ISO, and keywords).

### 5. Custom App-Local Collections & WorkManager Sync
*   **Room Database**: Local custom collections are persisted in a Room SQLite database.
*   **WorkManager Integration**: When a photo is added to a collection designated for offline access:
    1.  A background worker (`OfflineDownloadWorker`) is scheduled.
    2.  The worker checks available device storage and stops with a notification if storage is low.
    3.  The worker downloads the full-resolution image (`ArchivedUri`) and stores it securely in internal storage.
*   **Offline Indicator**: Thumbnail items and detail views display a custom green "Offline Ready" cloud-check icon to verify the image is downloaded and viewable without a network connection.

---

## 📱 Android Architecture & Tech Stack Guidelines

To build a modern, high-quality, and robust Android application, adhere to the following stack:

1. **Language**: Kotlin
2. **UI Framework**: Jetpack Compose (using Material Design 3)
3. **Architecture**: MVVM (Model-View-ViewModel) with Clean Architecture principles
   - **Data Layer**: Retrofit (for API calls), Paging 3 (for remote pagination), Room (for local caching, folder indexing, and local user collections), WorkManager (for background asset sync), and Coil (for image loading).
   - **Domain Layer**: Core data models and Use Cases.
   - **Presentation Layer**: Compose screens, ViewModels, and Navigation using Jetpack Navigation Compose.
4. **Asynchronous/Flows**: Kotlin Coroutines and StateFlow for reactive UI state propagation.
5. **Dependency Injection**: Hilt / Dagger for clean dependency management.
6. **Palette API**: Compose integration to dynamically extract dominant colors from photos/folders to adapt the UI theme colors dynamically.

---

## 🕸️ System Context & Data Flow Model

To illustrate how the distinct layers and components of the completed SmugView application interact, the following contextual model maps the relationships between the user, the presentation layer, the data layer, and remote/local data sources:

```mermaid
graph TD
    User([User]) <--> |Interacts / Views| UI[Jetpack Compose UI Screens]
    UI <--> |Binds State / Triggers Actions| VM[SmugViewModel]
    VM <--> |Requests / Observes Data Flows| Repo[SmugMugRepository]
    
    Repo <--> |Network requests / Session Unlocks| Api[SmugMugApi - Retrofit]
    Repo <--> |CRUD operations on Cache & Collections| DB[AppDatabase - Room SQLite]
    Repo --> |Dispatches Offline Download Jobs| WM[WorkManager]
    
    Api <--> |HTTPS JSON API v2| SmugMug[(SmugMug Remote Servers)]
    WM --> |Schedules| Worker[OfflineDownloadWorker]
    Worker --> |Downloads Full-Res Assets| Storage[(Local Internal Storage)]
    Worker <--> |Queries status / Marks complete| DB
```

### Key Data Flow Cycles
1. **Explore Site Flow**: User enters nickname ➡️ `SmugViewModel` triggers API request via `SmugMugRepository` ➡️ API returns root node ➡️ UI transitions to standard photo explorer.
2. **Offline Bookmark & Sync Flow**: User toggles offline sync on a collection ➡️ Repository inserts metadata in `AppDatabase` ➡️ Repository schedules `OfflineDownloadWorker` via `WorkManager` ➡️ Worker verifies local device storage ➡️ Worker fetches full-resolution images from `SmugMugApi` and saves files directly to internal directory, updating Room DB metadata status to `downloaded` (rendered as a green cloud checkmark in UI).
3. **Password Unlock Flow**: User accesses a password-marked node ➡️ API query fails with `401/404` ➡️ App prompts user for password ➡️ Repository calls `!unlock` POST endpoint to save the session tokens in Retrofit's OkHttp `CookieJar` ➡️ Subsequent requests succeed.

---

## 📂 Codebase Structure & Component Review

The application is structured into clearly separated packages mirroring Clean Architecture and MVVM patterns. The following review documents the primary components present in the codebase:

### 1. Presentation Layer (`com.smugview.app.ui`)
*   **ViewModels (`ui.viewmodel`)**:
    *   `SmugViewModel.kt`: The single state holder for the app. Retains nickname searches, dynamic folder listings, active loading/splash states, and applies client-side tag inclusion/exclusion formulas on Kotlin Flows.
*   **Screens & Layouts**:
    *   `SiteExplorerScreen.kt` (`ui.explorer`): The initial entry screen allowing the user to search public SmugMug nicknames and view validated profile card summaries.
    *   `BrowserScreen.kt` (`ui.browser`): The tabbed browsing coordinator containing the Folders grid, local Collections, and the tag-filtering Search tab.
    *   `PhotoDetailScreen.kt` & `SearchPhotoDetailScreen.kt` (`ui.detail`): Fully-immersive full-screen image views with horizontal swiping, ExoPlayer streaming support, EXIF metadata bottom sheets, and scoped storage download options.

### 2. Domain & Repository Layer (`com.smugview.app.data.repository`)
*   `SmugMugRepository.kt`: The central repository managing network fallback logic, password authentication caching, Room database transaction forwarding, and WorkManager task dispatching.
*   `PhotoPagingSource.kt`: Paging 3 source that handles loading and paginating list indexes returned from SmugMug image search endpoints.

### 3. Data & Storage Layer (`com.smugview.app.data`)
*   **API Client (`data.api`)**:
    *   `SmugMugApi.kt`: Retrofit client defining GET endpoints for folder/album trees, image lists, and EXIF metadata, as well as POST endpoints for session unlocks.
    *   `ResponseModels.kt`: Serialized data models mapping the JSON payloads returned by the SmugMug REST API.
*   **Room Database (`data.db`)**:
    *   `Entities.kt`: SQLite table structures for offline collections, collection items, and recent search histories.
    *   `CollectionDao.kt`: Core database queries for managing and checking local collections.
    *   `AppDatabase.kt`: Main database initializer registering the DAOs and migration specifications.
*   **Background Jobs (`data.worker`)**:
    *   `OfflineDownloadWorker.kt`: Downloader service validating local space availability, requesting high-resolution files, saving raw files locally, and flagging Room database entities as sync-complete.
