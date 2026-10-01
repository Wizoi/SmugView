package com.smugview.app.data.api

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import retrofit2.http.Header
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Field

interface SmugMugApi {

    @GET("user/{nickname}")
    suspend fun getUserProfile(
        @Path("nickname") nickname: String,
        @Query("APIKey") apiKey: String,
        @Query("_expand") expand: String = "BioImage",
        @Query("_verbosity") verbosity: Int = 1,
        @Header("X-Ignore-Errors") ignoreErrors: String? = null
    ): UserResponse

    @GET("user/{nickname}!bioimage")
    suspend fun getUserBioImage(
        @Path("nickname") nickname: String,
        @Query("APIKey") apiKey: String,
        @Query("_verbosity") verbosity: Int = 1
    ): BioImageResponse

    @GET("node/{node_id}!children")
    suspend fun getNodeChildren(
        @Path("node_id") nodeId: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("count") count: Int = 100,
        @Query("start") start: Int? = null,
        @Query("_expand") expand: String = "HighlightImage",
        @Query("_filter") filter: String = "Uri,NodeID,Type,Name,Description,SecurityType,Privacy,PasswordHint,Uris,WebUri,ThumbnailUrl,DateModified",
        @Query("_filteruri") filterUri: String = "ChildNodes,Album,HighlightImage",
        @Query("_verbosity") verbosity: Int = 1,
        @Header("X-Ignore-Errors") ignoreErrors: String? = null,
        @Header("Cache-Control") cacheControl: String? = null
    ): NodeListResponse

    @GET("node/{node_id}")
    suspend fun getNode(
        @Path("node_id") nodeId: String,
        @Query("APIKey") apiKey: String,
        @Query("_expand") expand: String? = null,
        @Query("_filter") filter: String = "Uri,NodeID,Type,Name,Description,SecurityType,Privacy,PasswordHint,Uris,WebUri,ThumbnailUrl,DateModified",
        @Query("_filteruri") filterUri: String = "ChildNodes,Album,HighlightImage",
        @Query("_verbosity") verbosity: Int = 1,
        @Header("X-Ignore-Errors") ignoreErrors: String? = null
    ): SingleNodeResponse

    /** The node's lineage, SELF FIRST, then its parent, up to the site root (verified live 2026-09-30). */
    @GET("node/{node_id}!parents")
    suspend fun getNodeParents(
        @Path("node_id") nodeId: String,
        @Query("APIKey") apiKey: String,
        @Query("_filter") filter: String = "Uri,NodeID,Type,Name,SecurityType,EffectiveSecurityType,WebUri",
        @Query("_verbosity") verbosity: Int = 1,
        @Header("X-Ignore-Errors") ignoreErrors: String? = null
    ): NodeParentsResponse

    @GET("album/{album_key}")
    suspend fun getAlbum(
        @Path("album_key") albumKey: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("_filter") filter: String = "Uri,AlbumKey,NodeID,Name,GalleryStyle,UrlPath,WebUri,SecurityType,Privacy,PasswordHint,ImageCount,Uris,LastUpdated,ImagesLastUpdated",
        @Query("_filteruri") filterUri: String = "HighlightImage",
        @Query("_verbosity") verbosity: Int = 1,
        @Header("X-Ignore-Errors") ignoreErrors: String? = null,
        // "no-cache" = go to the network (a lit gallery, Retry). Null = the cache policy decides (step 4-6).
        @Header("Cache-Control") cacheControl: String? = null
    ): AlbumResponse

    @GET("album/{album_key}!images")
    suspend fun getAlbumImages(
        @Path("album_key") albumKey: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("count") count: Int = 500,
        // Every page is this same call with `start`; Pages.NextPage drops _expand (R-27), so it is never followed.
        @Query("start") start: Int? = null,
        @Query("_expand") expand: String = "LargestVideo",
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,Keywords,KeywordArray,Uris",
        @Query("_filteruri") filterUri: String = "LargestVideo,Album",
        @Query("_verbosity") verbosity: Int = 1,
        @Header("X-Ignore-Errors") ignoreErrors: String? = null,
        @Header("Cache-Control") cacheControl: String? = null
    ): AlbumImagesResponse

    @GET
    suspend fun getImageSizeDetailsByUri(
        @Url url: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null
    ): ImageSizeDetailsResponse

    @GET("image!search")
    suspend fun searchImages(
        @Query("APIKey") apiKey: String,
        @Query("Scope") scope: String? = null,
        @Query("Text") text: String? = null,
        @Query("SortMethod") sortMethod: String? = null,
        @Query("SortDirection") sortDirection: String? = null,
        @Query("count") count: Int = 500,
        @Query("start") start: Int = 1,
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,Date,DateTime,FileName,Format,Keywords,KeywordArray,Uris,WebUri",
        @Query("_filteruri") filterUri: String = "ImageAlbum",
        @Query("_expand") expand: String? = null,
        @Query("_verbosity") verbosity: Int = 1
    ): ImageSearchResponse

    @GET("user!search")
    suspend fun searchUsers(
        @Query("APIKey") apiKey: String,
        @Query("q") query: String,
        @Query("_verbosity") verbosity: Int = 1
    ): UserSearchResponse

    @GET("user/{nickname}!recentimages")
    suspend fun getUserRecentImages(
        @Path("nickname") nickname: String,
        @Query("APIKey") apiKey: String,
        @Query("count") count: Int = 4,
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,WebUri,Uris",
        @Query("_filteruri") filterUri: String = "ImageAlbum",
        @Query("_verbosity") verbosity: Int = 1,
        @Query("Password") password: String? = null
    ): ImageSearchResponse

    @GET("node!search")
    suspend fun searchNodes(
        @Query("APIKey") apiKey: String,
        @Query("Scope") scope: String,
        @Query("Text") text: String,
        @Query("Password") password: String? = null,
        @Query("_expand") expand: String = "HighlightImage",
        @Query("_filter") filter: String = "Uri,NodeID,Type,Name,Description,SecurityType,Privacy,PasswordHint,Uris,WebUri,ThumbnailUrl,DateModified",
        @Query("_filteruri") filterUri: String = "ChildNodes,Album,HighlightImage",
        @Query("_verbosity") verbosity: Int = 1
    ): NodeListResponse

    @GET("image/{image_key}")
    suspend fun getImage(
        @Path("image_key") imageKey: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("_expand") expand: String = "LargestVideo",
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,Keywords,KeywordArray,Uris,WebUri",
        @Query("_filteruri") filterUri: String = "LargestVideo,ImageAlbum",
        @Query("_verbosity") verbosity: Int = 1
    ): ImageResponse

    @GET("image/{image_key}!metadata")
    suspend fun getImageExif(
        @Path("image_key") imageKey: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("_verbosity") verbosity: Int = 1
    ): ExifResponse

    @FormUrlEncoded
    @POST("node/{node_id}!unlock")
    suspend fun unlockNode(
        @Path("node_id") nodeId: String,
        @Query("APIKey") apiKey: String,
        @Field("Password") password: String,
        @Header("X-Ignore-Errors") ignoreErrors: String?
    ): retrofit2.Response<okhttp3.ResponseBody>

    @FormUrlEncoded
    @POST("album/{album_key}!unlock")
    suspend fun unlockAlbum(
        @Path("album_key") albumKey: String,
        @Query("APIKey") apiKey: String,
        @Field("Password") password: String,
        @Header("X-Ignore-Errors") ignoreErrors: String?
    ): retrofit2.Response<okhttp3.ResponseBody>

    @GET("user/{nickname}!albums")
    suspend fun getUserAlbums(
        @Path("nickname") nickname: String,
        @Query("APIKey") apiKey: String,
        @Query("count") count: Int = 100,
        // The crawl builds start=1,101,... itself: Pages.NextPage drops _expand and _verbosity (V3).
        // There is deliberately no SortMethod/SortDirection: user!albums does not sort by any date
        // it returns, so no early stop is safe (findings #14).
        @Query("start") start: Int? = null,
        @Query("_expand") expand: String = "HighlightImage",
        @Query("_filter") filter: String = "Uri,AlbumKey,NodeID,Name,GalleryStyle,UrlPath,WebUri,SecurityType,Privacy,PasswordHint,ImageCount,Uris,LastUpdated,ImagesLastUpdated",
        @Query("_filteruri") filterUri: String = "HighlightImage,Folder",
        @Query("_verbosity") verbosity: Int = 1,
        @Query("Password") password: String? = null,
        @Header("Cache-Control") cacheControl: String? = null
    ): UserAlbumsResponse

    @GET("user/{nickname}!topkeywords")
    suspend fun getUserTopKeywords(
        @Path("nickname") nickname: String,
        @Query("APIKey") apiKey: String,
        @Query("NodeURI") nodeUri: String? = null,
        @Query("_verbosity") verbosity: Int = 1,
        @Query("Password") password: String? = null
    ): TopKeywordsResponse

    @GET("image!search")
    suspend fun getImagesByKeyword(
        @Query("APIKey") apiKey: String,
        @Query("Scope") scope: String? = null,
        @Query("Text") text: String? = null,
        @Query("count") count: Int = 500,
        @Query("start") start: Int = 1,
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,Date,DateTime,FileName,Format,Keywords,KeywordArray,Uris,WebUri",
        @Query("_filteruri") filterUri: String = "ImageAlbum",
        @Query("_verbosity") verbosity: Int = 1
    ): ImageSearchResponse

    @Headers("Content-Type: application/json", "Accept: application/json")
    @PATCH("image/{image_key}")
    suspend fun updateImageMetadata(
        @Path("image_key") imageKey: String,
        @Query("APIKey") apiKey: String,
        @Body body: UpdateImageMetadataRequest
    ): retrofit2.Response<okhttp3.ResponseBody>
}
