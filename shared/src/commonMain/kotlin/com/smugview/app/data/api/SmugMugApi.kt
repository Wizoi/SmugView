package com.smugview.app.data.api

interface SmugMugApi {

    suspend fun getUserProfile(
        nickname: String,
        apiKey: String,
        expand: String = "BioImage",
        verbosity: Int = 1,
        ignoreErrors: String? = null
    ): UserResponse

    suspend fun getUserBioImage(
        nickname: String,
        apiKey: String,
        verbosity: Int = 1
    ): BioImageResponse

    suspend fun getNodeChildren(
        nodeId: String,
        apiKey: String,
        count: Int = 100,
        start: Int? = null,
        expand: String = "HighlightImage",
        filter: String = "Uri,NodeID,Type,Name,Description,SecurityType,Privacy,PasswordHint,Uris,WebUri,ThumbnailUrl,DateModified",
        filterUri: String = "ChildNodes,Album,HighlightImage",
        verbosity: Int = 1,
        ignoreErrors: String? = null,
        cacheControl: String? = null
    ): NodeListResponse

    suspend fun getNode(
        nodeId: String,
        apiKey: String,
        expand: String? = null,
        filter: String = "Uri,NodeID,Type,Name,Description,SecurityType,Privacy,PasswordHint,Uris,WebUri,ThumbnailUrl,DateModified",
        filterUri: String = "ChildNodes,Album,HighlightImage",
        verbosity: Int = 1,
        ignoreErrors: String? = null
    ): SingleNodeResponse

    /** The node's lineage, SELF FIRST, then its parent, up to the site root (verified live 2026-09-30). */
    suspend fun getNodeParents(
        nodeId: String,
        apiKey: String,
        filter: String = "Uri,NodeID,Type,Name,SecurityType,EffectiveSecurityType,WebUri",
        verbosity: Int = 1,
        ignoreErrors: String? = null
    ): NodeParentsResponse

    suspend fun getAlbum(
        albumKey: String,
        apiKey: String,
        filter: String = "Uri,AlbumKey,NodeID,Name,GalleryStyle,UrlPath,WebUri,SecurityType,Privacy,PasswordHint,ImageCount,Uris,LastUpdated,ImagesLastUpdated",
        filterUri: String = "HighlightImage",
        verbosity: Int = 1,
        ignoreErrors: String? = null,
        // "no-cache" = go to the network (a lit gallery, Retry). Null = the cache policy decides (step 4-6).
        cacheControl: String? = null
    ): AlbumResponse

    suspend fun getAlbumImages(
        albumKey: String,
        apiKey: String,
        count: Int = 500,
        // Every page is this same call with `start`; Pages.NextPage drops _expand (R-27), so it is never followed.
        start: Int? = null,
        expand: String = "LargestVideo",
        filter: String = "ImageKey,Title,Caption,ThumbnailUrl,WebUri,ArchivedUri,Date,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,ArchivedSize,ArchivedMD5,Keywords,KeywordArray,Uris",
        // ImageSizeDetails is only present when listed here (P4, R-29): the zoom reads its real tiers from it.
        filterUri: String = "LargestVideo,Album,ImageSizeDetails",
        verbosity: Int = 1,
        ignoreErrors: String? = null,
        cacheControl: String? = null
    ): AlbumImagesResponse

    suspend fun getImageSizeDetailsByUri(
        url: String,
        apiKey: String
    ): ImageSizeDetailsResponse

    suspend fun searchImages(
        apiKey: String,
        scope: String? = null,
        text: String? = null,
        sortMethod: String? = null,
        sortDirection: String? = null,
        count: Int = 500,
        start: Int = 1,
        filter: String = "ImageKey,Title,Caption,ThumbnailUrl,Date,FileName,Format,Keywords,KeywordArray,Uris",
        // WebUri, DateTime and ImageAlbum are never present on search results (P4): not requested.
        // A video's `ArchivedUri` is a JPEG still: without `LargestVideo` (expanded AND listed in `_filteruri`) a search result cannot play.
        filterUri: String = "ImageSizeDetails,LargestVideo",
        expand: String? = "LargestVideo",
        verbosity: Int = 1
    ): ImageSearchResponse

    suspend fun searchUsers(
        apiKey: String,
        query: String,
        verbosity: Int = 1
    ): UserSearchResponse

    suspend fun getUserRecentImages(
        nickname: String,
        apiKey: String,
        count: Int = 4,
        // WebUri and ImageAlbum are never present on recent images (P4): not requested, and no _filteruri.
        filter: String = "ImageKey,Title,Caption,ThumbnailUrl",
        filterUri: String? = null,
        verbosity: Int = 1
    ): ImageSearchResponse

    suspend fun searchNodes(
        apiKey: String,
        scope: String,
        text: String,
        expand: String = "HighlightImage",
        filter: String = "Uri,NodeID,Type,Name,Description,SecurityType,Privacy,PasswordHint,Uris,WebUri,ThumbnailUrl,DateModified",
        filterUri: String = "ChildNodes,Album,HighlightImage",
        verbosity: Int = 1
    ): NodeListResponse

    // `image_key` is the canonical `{key}-0` (see [canonicalImageKey]): `image/{key}` 301s to it with `no-store`
    // (P5, R-30), and the 200 is what the HTTP cache stores, so details work offline after one online view.
    suspend fun getImage(
        imageKey: String,
        apiKey: String,
        expand: String = "LargestVideo",
        filter: String = "ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,Keywords,KeywordArray,Uris",
        filterUri: String = "LargestVideo,ImageSizeDetails",
        verbosity: Int = 1
    ): ImageResponse

    /**
     * Where the original of one photo or video is (phase 5, P1/P8): `ArchivedUri` plus the size and MD5 the CDN
     * will deliver. `image_key` is the canonical `{key}-0`. [cacheControl] `no-cache` re-asks the server (a source
     * that stopped working is looked up again, not read back from the HTTP cache).
     */
    suspend fun getImageSource(
        imageKey: String,
        apiKey: String,
        filter: String = "ImageKey,ArchivedUri,ArchivedSize,ArchivedMD5,Format,IsVideo",
        verbosity: Int = 1,
        cacheControl: String? = null
    ): ImageSourceResponse

    suspend fun getImageExif(
        imageKey: String,
        apiKey: String,
        verbosity: Int = 1
    ): ExifResponse

    suspend fun unlockNode(
        nodeId: String,
        apiKey: String,
        password: String,
        ignoreErrors: String?
    ): ApiStatus

    suspend fun unlockAlbum(
        albumKey: String,
        apiKey: String,
        password: String,
        ignoreErrors: String?
    ): ApiStatus

    /**
     * The newest galleries by date added, newest first: one page is the whole new-gallery poll. `Order` sorts by the NODE's
     * DateAdded, which the album payload never returns, so it looked unsorted to findings #14 (checked 2026-10-03: 2,633
     * albums with the Family session, 2,631 comparisons, no violation). No `start`: the first page is all it reads.
     */
    suspend fun getNewestAlbums(
        nickname: String,
        apiKey: String,
        order: String = "Date Added (Descending)",
        count: Int = 100,
        filter: String = "Uri,AlbumKey,NodeID,Name,UrlPath,WebUri",
        verbosity: Int = 1,
        cacheControl: String? = "no-cache"
    ): UserAlbumsResponse

    suspend fun getUserAlbums(
        nickname: String,
        apiKey: String,
        count: Int = 100,
        // The crawl builds start=1,101,... itself: Pages.NextPage drops _expand and _verbosity (V3).
        // There is deliberately no SortMethod/SortDirection: user!albums does not sort by any date
        // it returns, so no early stop is safe (findings #14).
        start: Int? = null,
        expand: String = "HighlightImage",
        filter: String = "Uri,AlbumKey,NodeID,Name,GalleryStyle,UrlPath,WebUri,SecurityType,Privacy,PasswordHint,ImageCount,Uris,LastUpdated,ImagesLastUpdated",
        filterUri: String = "HighlightImage,Folder",
        verbosity: Int = 1,
        cacheControl: String? = null
    ): UserAlbumsResponse

    suspend fun getUserTopKeywords(
        nickname: String,
        apiKey: String,
        nodeUri: String? = null,
        verbosity: Int = 1
    ): TopKeywordsResponse

    suspend fun getImagesByKeyword(
        apiKey: String,
        scope: String? = null,
        text: String? = null,
        count: Int = 500,
        start: Int = 1,
        filter: String = "ImageKey,Title,Caption,ThumbnailUrl,Date,FileName,Format,Keywords,KeywordArray,Uris",
        // WebUri, DateTime and ImageAlbum are never present on search results (P4): not requested.
        // The video address, as for `searchImages`: the Tags tab lists videos too.
        filterUri: String = "ImageSizeDetails,LargestVideo",
        expand: String? = "LargestVideo",
        verbosity: Int = 1
    ): ImageSearchResponse

    suspend fun updateImageMetadata(
        imageKey: String,
        apiKey: String,
        body: UpdateImageMetadataRequest
    ): ApiStatus
}

/**
 * The key an `image/{key}` request should use. SmugMug answers `image/XVRvVTM` with a `301` to `XVRvVTM-0`
 * and `Cache-Control: no-store` (P5, R-30), so the redirect is never cacheable and the details were not
 * available offline. A key that already has a serial (`-0`, `-1`) is left alone: a non-zero serial is
 * followed as before.
 */
fun canonicalImageKey(imageKey: String): String =
    if (SERIAL.containsMatchIn(imageKey)) imageKey else "$imageKey-0"

private val SERIAL = Regex("""-\d+$""")
