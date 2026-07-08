package com.smugview.app.data.api

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Field

interface SmugMugApi {

    @GET("user/{nickname}")
    suspend fun getUserProfile(
        @Path("nickname") nickname: String,
        @Query("APIKey") apiKey: String,
        @Query("_expand") expand: String = "BioImage",
        @Query("_verbosity") verbosity: Int = 1
    ): UserResponse

    @GET("user/{nickname}!bioimage")
    suspend fun getUserBioImage(
        @Path("nickname") nickname: String,
        @Query("APIKey") apiKey: String,
        @Query("_verbosity") verbosity: Int = 1
    ): BioImageResponse

    @GET("node/{node_id}!children?_expand=HighlightImage")
    suspend fun getNodeChildren(
        @Path("node_id") nodeId: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("_filter") filter: String = "Uri,NodeID,Type,Name,Description,SecurityType,PasswordHint,Uris,WebUri,ThumbnailUrl",
        @Query("_verbosity") verbosity: Int = 1
    ): NodeListResponse

    @GET("album/{album_key}")
    suspend fun getAlbum(
        @Path("album_key") albumKey: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("_verbosity") verbosity: Int = 1
    ): AlbumResponse

    @GET("album/{album_key}!images")
    suspend fun getAlbumImages(
        @Path("album_key") albumKey: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("count") count: Int = 500,
        @Query("_expand") expand: String = "LargestVideo",
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,Keywords,Uris",
        @Query("_verbosity") verbosity: Int = 1
    ): AlbumImagesResponse

    // Used by Paging 3 to fetch pages relative to base URL
    @GET
    suspend fun getAlbumImagesByUri(
        @Url url: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("count") count: Int = 500,
        @Query("_expand") expand: String = "LargestVideo",
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,Keywords,Uris",
        @Query("_verbosity") verbosity: Int = 1
    ): AlbumImagesResponse

    @GET("image!search")
    suspend fun searchImages(
        @Query("APIKey") apiKey: String,
        @Query("Scope") scope: String? = null,
        @Query("Text") text: String? = null,
        @Query("SortMethod") sortMethod: String? = null,
        @Query("SortDirection") sortDirection: String? = null,
        @Query("count") count: Int = 500,
        @Query("start") start: Int = 1,
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,WebUri,Uris",
        @Query("_expand") expand: String = "LargestVideo",
        @Query("_verbosity") verbosity: Int = 1
    ): ImageSearchResponse

    @GET
    suspend fun searchImagesByUri(
        @Url url: String,
        @Query("APIKey") apiKey: String,
        @Query("_verbosity") verbosity: Int = 1
    ): ImageSearchResponse

    @GET("user/{nickname}!imagesearch")
    suspend fun searchImagesUser(
        @Path("nickname") nickname: String,
        @Query("APIKey") apiKey: String,
        @Query("Text") text: String,
        @Query("Scope") scope: String? = null,
        @Query("Password") password: String? = null,
        @Query("count") count: Int = 250,
        @Query("start") start: Int = 1,
        @Query("_expand") expand: String = "LargestVideo",
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,WebUri,Uris",
        @Query("_verbosity") verbosity: Int = 1
    ): ImageSearchResponse

    @GET
    suspend fun searchImagesUserByUri(
        @Url url: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("_expand") expand: String = "LargestVideo",
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,WebUri,Uris",
        @Query("_verbosity") verbosity: Int = 1
    ): ImageSearchResponse

    @GET("node!search")
    suspend fun searchNodes(
        @Query("APIKey") apiKey: String,
        @Query("Scope") scope: String,
        @Query("Text") text: String,
        @Query("Password") password: String? = null,
        @Query("_expand") expand: String = "HighlightImage",
        @Query("_filter") filter: String = "Uri,NodeID,Type,Name,Description,SecurityType,PasswordHint,Uris,WebUri,ThumbnailUrl",
        @Query("_verbosity") verbosity: Int = 1
    ): NodeListResponse

    @GET("image/{image_key}")
    suspend fun getImage(
        @Path("image_key") imageKey: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("_expand") expand: String = "LargestVideo",
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,Uris,WebUri",
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
        @Field("Password") password: String
    ): retrofit2.Response<okhttp3.ResponseBody>

    @FormUrlEncoded
    @POST("album/{album_key}!unlock")
    suspend fun unlockAlbum(
        @Path("album_key") albumKey: String,
        @Query("APIKey") apiKey: String,
        @Field("Password") password: String
    ): retrofit2.Response<okhttp3.ResponseBody>

    @GET("user/{nickname}!albums")
    suspend fun getUserAlbums(
        @Path("nickname") nickname: String,
        @Query("APIKey") apiKey: String,
        @Query("count") count: Int = 500,
        @Query("_expand") expand: String = "HighlightImage",
        @Query("_filter") filter: String = "Uri,AlbumKey,NodeID,Name,GalleryStyle,UrlPath,WebUri,SecurityType,PasswordHint,ImageCount,Uris",
        @Query("_verbosity") verbosity: Int = 1
    ): UserAlbumsResponse

    @GET
    suspend fun getUserAlbumsByUri(
        @Url url: String,
        @Query("APIKey") apiKey: String,
        @Query("count") count: Int = 500,
        @Query("_expand") expand: String = "HighlightImage",
        @Query("_filter") filter: String = "Uri,AlbumKey,NodeID,Name,GalleryStyle,UrlPath,WebUri,SecurityType,PasswordHint,ImageCount,Uris",
        @Query("_verbosity") verbosity: Int = 1
    ): UserAlbumsResponse

    @GET("album/{album_keys}")
    suspend fun getAlbumKeywords(
        @Path("album_keys") albumKeys: String,
        @Query("APIKey") apiKey: String,
        @Query("Password") password: String? = null,
        @Query("_expand") expand: String = "AlbumKeywords",
        @Query("_filter") filter: String = "Uri",
        @Query("_verbosity") verbosity: Int = 1
    ): AlbumKeywordsResponse

    @GET("user/{nickname}!topkeywords")
    suspend fun getUserTopKeywords(
        @Path("nickname") nickname: String,
        @Query("APIKey") apiKey: String,
        @Query("NodeID") nodeId: String? = null,
        @Query("_verbosity") verbosity: Int = 1
    ): TopKeywordsResponse

    @GET("image!search")
    suspend fun getImagesByKeyword(
        @Query("APIKey") apiKey: String,
        @Query("Scope") scope: String? = null,
        @Query("Keywords") keywords: String? = null,
        @Query("_filter") filter: String = "ImageKey,Title,Caption,ThumbnailUrl,ArchivedUri,Date,DateTime,FileName,Format,OriginalWidth,OriginalHeight,OriginalSize,WebUri,Uris,Keywords",
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
