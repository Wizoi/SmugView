package com.smugview.app.data.api

import io.ktor.http.encodeURLPathPart

/**
 * [SmugMugApi] over Ktor. Generated from the former Retrofit interface (path, query, header and form parameters are the same, null
 * query and header values are left out as Retrofit did). Behaviour of the HTTP stack (cache, retry, cookies) belongs to the engine.
 */
class KtorSmugMugApi(private val http: ApiHttp) : SmugMugApi {
    override suspend fun getUserProfile(
        nickname: String,
        apiKey: String,
        expand: String,
        verbosity: Int,
        ignoreErrors: String?
    ): UserResponse =
        http.get<UserResponse>("user/${nickname.encodeURLPathPart()}", listOf("APIKey" to apiKey, "_expand" to expand, "_verbosity" to verbosity), listOf("X-Ignore-Errors" to ignoreErrors))

    override suspend fun getUserBioImage(
        nickname: String,
        apiKey: String,
        verbosity: Int
    ): BioImageResponse =
        http.get<BioImageResponse>("user/${nickname.encodeURLPathPart()}!bioimage", listOf("APIKey" to apiKey, "_verbosity" to verbosity), listOf())

    override suspend fun getNodeChildren(
        nodeId: String,
        apiKey: String,
        count: Int,
        start: Int?,
        expand: String,
        filter: String,
        filterUri: String,
        verbosity: Int,
        ignoreErrors: String?,
        cacheControl: String?
    ): NodeListResponse =
        http.get<NodeListResponse>("node/${nodeId.encodeURLPathPart()}!children", listOf("APIKey" to apiKey, "count" to count, "start" to start, "_expand" to expand, "_filter" to filter, "_filteruri" to filterUri, "_verbosity" to verbosity), listOf("X-Ignore-Errors" to ignoreErrors, "Cache-Control" to cacheControl))

    override suspend fun getNode(
        nodeId: String,
        apiKey: String,
        expand: String?,
        filter: String,
        filterUri: String,
        verbosity: Int,
        ignoreErrors: String?
    ): SingleNodeResponse =
        http.get<SingleNodeResponse>("node/${nodeId.encodeURLPathPart()}", listOf("APIKey" to apiKey, "_expand" to expand, "_filter" to filter, "_filteruri" to filterUri, "_verbosity" to verbosity), listOf("X-Ignore-Errors" to ignoreErrors))

    override suspend fun getNodeParents(
        nodeId: String,
        apiKey: String,
        filter: String,
        verbosity: Int,
        ignoreErrors: String?
    ): NodeParentsResponse =
        http.get<NodeParentsResponse>("node/${nodeId.encodeURLPathPart()}!parents", listOf("APIKey" to apiKey, "_filter" to filter, "_verbosity" to verbosity), listOf("X-Ignore-Errors" to ignoreErrors))

    override suspend fun getAlbum(
        albumKey: String,
        apiKey: String,
        filter: String,
        filterUri: String,
        verbosity: Int,
        ignoreErrors: String?,
        cacheControl: String?
    ): AlbumResponse =
        http.get<AlbumResponse>("album/${albumKey.encodeURLPathPart()}", listOf("APIKey" to apiKey, "_filter" to filter, "_filteruri" to filterUri, "_verbosity" to verbosity), listOf("X-Ignore-Errors" to ignoreErrors, "Cache-Control" to cacheControl))

    override suspend fun getAlbumImages(
        albumKey: String,
        apiKey: String,
        count: Int,
        start: Int?,
        expand: String,
        filter: String,
        filterUri: String,
        verbosity: Int,
        ignoreErrors: String?,
        cacheControl: String?
    ): AlbumImagesResponse =
        http.get<AlbumImagesResponse>("album/${albumKey.encodeURLPathPart()}!images", listOf("APIKey" to apiKey, "count" to count, "start" to start, "_expand" to expand, "_filter" to filter, "_filteruri" to filterUri, "_verbosity" to verbosity), listOf("X-Ignore-Errors" to ignoreErrors, "Cache-Control" to cacheControl))

    override suspend fun getImageSizeDetailsByUri(
        url: String,
        apiKey: String
    ): ImageSizeDetailsResponse =
        http.get<ImageSizeDetailsResponse>(url, listOf("APIKey" to apiKey), listOf())

    override suspend fun searchImages(
        apiKey: String,
        scope: String?,
        text: String?,
        sortMethod: String?,
        sortDirection: String?,
        count: Int,
        start: Int,
        filter: String,
        filterUri: String,
        expand: String?,
        verbosity: Int
    ): ImageSearchResponse =
        http.get<ImageSearchResponse>("image!search", listOf("APIKey" to apiKey, "Scope" to scope, "Text" to text, "SortMethod" to sortMethod, "SortDirection" to sortDirection, "count" to count, "start" to start, "_filter" to filter, "_filteruri" to filterUri, "_expand" to expand, "_verbosity" to verbosity), listOf())

    override suspend fun searchUsers(
        apiKey: String,
        query: String,
        verbosity: Int
    ): UserSearchResponse =
        http.get<UserSearchResponse>("user!search", listOf("APIKey" to apiKey, "q" to query, "_verbosity" to verbosity), listOf())

    override suspend fun getUserRecentImages(
        nickname: String,
        apiKey: String,
        count: Int,
        filter: String,
        filterUri: String?,
        verbosity: Int
    ): ImageSearchResponse =
        http.get<ImageSearchResponse>("user/${nickname.encodeURLPathPart()}!recentimages", listOf("APIKey" to apiKey, "count" to count, "_filter" to filter, "_filteruri" to filterUri, "_verbosity" to verbosity), listOf())

    override suspend fun searchNodes(
        apiKey: String,
        scope: String,
        text: String,
        expand: String,
        filter: String,
        filterUri: String,
        verbosity: Int
    ): NodeListResponse =
        http.get<NodeListResponse>("node!search", listOf("APIKey" to apiKey, "Scope" to scope, "Text" to text, "_expand" to expand, "_filter" to filter, "_filteruri" to filterUri, "_verbosity" to verbosity), listOf())

    override suspend fun getImage(
        imageKey: String,
        apiKey: String,
        expand: String,
        filter: String,
        filterUri: String,
        verbosity: Int
    ): ImageResponse =
        http.get<ImageResponse>("image/${imageKey.encodeURLPathPart()}", listOf("APIKey" to apiKey, "_expand" to expand, "_filter" to filter, "_filteruri" to filterUri, "_verbosity" to verbosity), listOf())

    override suspend fun getImageSource(
        imageKey: String,
        apiKey: String,
        filter: String,
        verbosity: Int,
        cacheControl: String?
    ): ImageSourceResponse =
        http.get<ImageSourceResponse>("image/${imageKey.encodeURLPathPart()}", listOf("APIKey" to apiKey, "_filter" to filter, "_verbosity" to verbosity), listOf("Cache-Control" to cacheControl))

    override suspend fun getImageExif(
        imageKey: String,
        apiKey: String,
        verbosity: Int
    ): ExifResponse =
        http.get<ExifResponse>("image/${imageKey.encodeURLPathPart()}!metadata", listOf("APIKey" to apiKey, "_verbosity" to verbosity), listOf())

    override suspend fun unlockNode(
        nodeId: String,
        apiKey: String,
        password: String,
        ignoreErrors: String?
    ): ApiStatus =
        http.postForm("node/${nodeId.encodeURLPathPart()}!unlock", listOf("APIKey" to apiKey), listOf("X-Ignore-Errors" to ignoreErrors), listOf("Password" to password))

    override suspend fun unlockAlbum(
        albumKey: String,
        apiKey: String,
        password: String,
        ignoreErrors: String?
    ): ApiStatus =
        http.postForm("album/${albumKey.encodeURLPathPart()}!unlock", listOf("APIKey" to apiKey), listOf("X-Ignore-Errors" to ignoreErrors), listOf("Password" to password))

    override suspend fun getNewestAlbums(
        nickname: String,
        apiKey: String,
        order: String,
        count: Int,
        filter: String,
        verbosity: Int,
        cacheControl: String?
    ): UserAlbumsResponse =
        http.get<UserAlbumsResponse>("user/${nickname.encodeURLPathPart()}!albums", listOf("APIKey" to apiKey, "Order" to order, "count" to count, "_filter" to filter, "_verbosity" to verbosity), listOf("Cache-Control" to cacheControl))

    override suspend fun getUserAlbums(
        nickname: String,
        apiKey: String,
        count: Int,
        start: Int?,
        expand: String,
        filter: String,
        filterUri: String,
        verbosity: Int,
        cacheControl: String?
    ): UserAlbumsResponse =
        http.get<UserAlbumsResponse>("user/${nickname.encodeURLPathPart()}!albums", listOf("APIKey" to apiKey, "count" to count, "start" to start, "_expand" to expand, "_filter" to filter, "_filteruri" to filterUri, "_verbosity" to verbosity), listOf("Cache-Control" to cacheControl))

    override suspend fun getUserTopKeywords(
        nickname: String,
        apiKey: String,
        nodeUri: String?,
        verbosity: Int
    ): TopKeywordsResponse =
        http.get<TopKeywordsResponse>("user/${nickname.encodeURLPathPart()}!topkeywords", listOf("APIKey" to apiKey, "NodeURI" to nodeUri, "_verbosity" to verbosity), listOf())

    override suspend fun getImagesByKeyword(
        apiKey: String,
        scope: String?,
        text: String?,
        count: Int,
        start: Int,
        filter: String,
        filterUri: String,
        expand: String?,
        verbosity: Int
    ): ImageSearchResponse =
        http.get<ImageSearchResponse>("image!search", listOf("APIKey" to apiKey, "Scope" to scope, "Text" to text, "count" to count, "start" to start, "_filter" to filter, "_filteruri" to filterUri, "_expand" to expand, "_verbosity" to verbosity), listOf())

    override suspend fun updateImageMetadata(
        imageKey: String,
        apiKey: String,
        body: UpdateImageMetadataRequest
    ): ApiStatus =
        http.patchJson("image/${imageKey.encodeURLPathPart()}", listOf("APIKey" to apiKey), body, UpdateImageMetadataRequest.serializer())

}
