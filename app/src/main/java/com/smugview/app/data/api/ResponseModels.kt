package com.smugview.app.data.api

import com.google.gson.annotations.SerializedName

// Generic container
data class UriContainer(
    @SerializedName("Uri") val uri: String,
    @SerializedName("Locator") val locator: String? = null
)

data class PagesData(
    @SerializedName("Start") val start: Int,
    @SerializedName("Count") val count: Int,
    @SerializedName("Total") val total: Int,
    @SerializedName("Next") private val nextField: String? = null,
    @SerializedName("NextPage") private val nextPageField: String? = null
) {
    val next: String?
        get() = nextField ?: nextPageField
}

// User API Response
data class UserResponse(
    @SerializedName("Response") val response: UserPayload,
    @SerializedName("Expansions") val expansions: Map<String, UserExpansionContainer>? = null
)
data class UserPayload(
    @SerializedName("User") val user: UserData
)
data class UserData(
    @SerializedName("NickName") val nickName: String,
    @SerializedName("Name") val name: String,
    @SerializedName("Uris") val uris: UserUris,
    @SerializedName("WebUri") val webUri: String? = null,
    val bioImageKey: String? = null
)
data class UserUris(
    @SerializedName("Node") val node: String
)
data class UserExpansionContainer(
    @SerializedName("BioImage") val bioImage: BioImageData? = null
)

// Node List (Children) Response
data class NodeListResponse(
    @SerializedName("Response") val response: NodeListPayload,
    @SerializedName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)

data class ExpansionContainer(
    @SerializedName("Image") val image: ExpansionImage? = null,
    @SerializedName("LargestVideo") val largestVideo: ExpansionVideo? = null
)

data class ExpansionVideo(
    @SerializedName("Url") val url: String? = null
)

data class ExpansionImage(
    @SerializedName("ThumbnailUrl") val thumbnailUrl: String? = null
)
data class NodeListPayload(
    @SerializedName("Node") val nodes: List<NodeData>? = emptyList(),
    @SerializedName("Pages") val pages: PagesData? = null
)
data class NodeData(
    @SerializedName("Uri") val uri: String,
    @SerializedName("NodeID") val nodeId: String,
    @SerializedName("Type") val type: String, // "Folder" or "Album"
    @SerializedName("Name") val name: String? = null,
    @SerializedName("Description") val description: String? = null,
    @SerializedName("SecurityType") val securityType: String? = null,
    @SerializedName("Privacy") val privacy: String? = null,
    @SerializedName("PasswordHint") val passwordHint: String? = null,
    @SerializedName("WebUri") val webUri: String? = null,
    @SerializedName("Uris") val uris: NodeUris,
    @SerializedName("DateModified") val dateModified: String? = null
)
data class NodeUris(
    @SerializedName("ChildNodes") val childNodes: String? = null,
    @SerializedName("Album") val album: String? = null,
    @SerializedName("HighlightImage") val highlightImage: String? = null,
    @SerializedName("ParentNode") val parentNode: String? = null,
    /** Present on `user!albums` galleries: `/api/v2/folder/user/{nick}/{path}` of the containing folder. */
    @SerializedName("Folder") val folder: String? = null
)

// Node lineage (`node/{id}!parents`): self first, then each ancestor up to the site root.
data class NodeParentsResponse(
    @SerializedName("Response") val response: NodeParentsPayload
)
data class NodeParentsPayload(
    @SerializedName("Node") val nodes: List<ParentNodeData>? = null
)
data class ParentNodeData(
    @SerializedName("Uri") val uri: String? = null,
    @SerializedName("NodeID") val nodeId: String,
    @SerializedName("Type") val type: String? = null,
    @SerializedName("Name") val name: String? = null,
    /** "Password", "None", ... SmugMug never sends "Inherited" (findings #19). */
    @SerializedName("SecurityType") val securityType: String? = null,
    @SerializedName("EffectiveSecurityType") val effectiveSecurityType: String? = null,
    @SerializedName("WebUri") val webUri: String? = null
)

// Album Images Response
data class AlbumImagesResponse(
    @SerializedName("Response") val response: AlbumImagesPayload,
    @SerializedName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)
data class AlbumImagesPayload(
    @SerializedName("AlbumImage") val images: List<AlbumImageData>? = emptyList(),
    @SerializedName("Pages") val pages: PagesData? = null
)
data class AlbumImageData(
    @SerializedName("ImageKey") val imageKey: String,
    @SerializedName("Title") val title: String? = null,
    @SerializedName("Caption") val caption: String? = null,
    @SerializedName("ThumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("ArchivedUri") val archivedUri: String? = null,
    @SerializedName("Date") val date: String? = null,
    @SerializedName("DateTime") val dateTime: String? = null,
    @SerializedName("FileName") val fileName: String? = null,
    @SerializedName("Keywords") val keywords: String? = null,
    @SerializedName("KeywordArray") val keywordArray: List<String>? = null,
    @SerializedName("WebUri") val webUri: String? = null,
    @SerializedName("OriginalWidth") val originalWidth: Int? = null,
    @SerializedName("OriginalHeight") val originalHeight: Int? = null,
    @SerializedName("OriginalSize") val originalSize: Long? = null,
    @SerializedName("Format") val format: String? = null,
    @SerializedName("Uris") val uris: AlbumImageUris? = null,
    var videoUrl: String? = null
) {
    val keywordsString: String?
        get() {
            if (!keywordArray.isNullOrEmpty()) {
                return keywordArray.joinToString(", ")
            }
            if (keywords != null && !keywords.startsWith("/api/v2/")) {
                return keywords.replace(";", ",")
            }
            return null
        }
}

data class AlbumImageUris(
    @SerializedName("LargestVideo") val largestVideo: String? = null,
    @SerializedName("Album") val album: String? = null,
    @SerializedName("ImageAlbum") val imageAlbum: String? = null,
    @SerializedName("ImageSizeDetails") val imageSizeDetails: String? = null
)

// Image Size Details Response
data class ImageSizeDetailsResponse(
    @SerializedName("Response") val response: ImageSizeDetailsResponseWrapper
)
data class ImageSizeDetailsResponseWrapper(
    @SerializedName("ImageSizeDetails") val details: ImageSizeDetailsPayload
)
data class ImageSizeDetailsPayload(
    @SerializedName("ImageSizeMedium") val medium: ImageSizeEntry? = null,
    @SerializedName("ImageSizeLarge") val large: ImageSizeEntry? = null,
    @SerializedName("ImageSizeXLarge") val xLarge: ImageSizeEntry? = null,
    @SerializedName("ImageSizeX2Large") val x2Large: ImageSizeEntry? = null,
    @SerializedName("ImageSizeX3Large") val x3Large: ImageSizeEntry? = null,
    @SerializedName("ImageSizeOriginal") val original: ImageSizeEntry? = null
) {
    /** Largest non-original rendition SmugMug actually generated for this image. */
    val bestHalfway: ImageSizeEntry?
        get() = x3Large ?: x2Large ?: xLarge ?: large ?: medium
}
data class ImageSizeEntry(
    @SerializedName("Url") val url: String,
    @SerializedName("Width") val width: Int,
    @SerializedName("Height") val height: Int
)

// Image Metadata (EXIF) Response
data class ExifResponse(
    @SerializedName("Response") val response: ExifPayload
)
data class ExifPayload(
    @SerializedName("ImageMetadata") val exif: ExifData
)
data class ExifData(
    @SerializedName("Make") val make: String? = null,
    @SerializedName("Model") val model: String? = null,
    @SerializedName("Lens") val lens: String? = null,
    @SerializedName("Aperture") val aperture: String? = null,
    @SerializedName("Exposure") val exposure: String? = null,
    @SerializedName("ISO") val iso: Int? = null,
    @SerializedName("FocalLength") val focalLength: String? = null,
    @SerializedName("DateTimeCreated") val dateTimeCreated: String? = null,
    @SerializedName("DateCreated") val dateCreated: String? = null
) {
    val camera: String?
        get() {
            val maker = make?.trim() ?: ""
            val mdl = model?.trim() ?: ""
            return when {
                mdl.isNotEmpty() -> {
                    if (maker.isNotEmpty() && mdl.contains(maker, ignoreCase = true)) {
                        mdl.replace(Regex("(?i)$maker\\s*"), "").trim()
                    } else {
                        mdl
                    }
                }
                maker.isNotEmpty() -> maker
                else -> null
            }
        }
}

// Album Details Response
data class AlbumResponse(
    @SerializedName("Response") val response: AlbumPayload
)
data class AlbumPayload(
    @SerializedName("Album") val album: AlbumDetails
)
data class AlbumDetails(
    @SerializedName("Uri") val uri: String,
    @SerializedName("AlbumKey") val albumKey: String,
    @SerializedName("NodeID") val nodeId: String? = null,
    @SerializedName("Name") val name: String,
    @SerializedName("GalleryStyle") val galleryStyle: String? = null,
    @SerializedName("UrlPath") val urlPath: String? = null,
    @SerializedName("WebUri") val webUri: String? = null,
    @SerializedName("ImageCount") val imageCount: Int? = null,
    @SerializedName("SecurityType") val securityType: String? = null,
    @SerializedName("PasswordHint") val passwordHint: String? = null,
    @SerializedName("Uris") val uris: NodeUris? = null,
    @SerializedName("LastUpdated") val dateModified: String? = null,
    /** When the photos in the gallery last changed: the one "is it new?" date (design Q3). */
    @SerializedName("ImagesLastUpdated") val imagesLastUpdated: String? = null
)

data class UserAlbumsResponse(
    @SerializedName("Response") val response: UserAlbumsPayload,
    @SerializedName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)
data class UserAlbumsPayload(
    @SerializedName("Album") val albums: List<AlbumDetails>? = emptyList(),
    @SerializedName("Pages") val pages: PagesData? = null
)

val AlbumImageData.isVideo: Boolean
    get() = (format?.lowercase()?.let { it == "mp4" || it == "mov" || it == "avi" || it == "3gp" || it == "mkv" || it == "video" } ?: false) ||
            (archivedUri?.lowercase()?.contains(".mp4") ?: false) ||
            (archivedUri?.lowercase()?.contains(".mov") ?: false)

// User BioImage Response
data class BioImageResponse(
    @SerializedName("Response") val response: BioImagePayload
)
data class BioImagePayload(
    @SerializedName("BioImage") val bioImage: BioImageData
)
data class BioImageData(
    @SerializedName("ImageKey") val imageKey: String
)

// Image Search Response
data class ImageSearchResponse(
    @SerializedName("Response") val response: ImageSearchPayload,
    @SerializedName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)
data class ImageSearchPayload(
    @SerializedName("Image") val images: List<AlbumImageData>? = emptyList(),
    @SerializedName("Pages") val pages: PagesData? = null
)

// Single Image Response
data class ImageResponse(
    @SerializedName("Response") val response: ImagePayload,
    @SerializedName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)
data class ImagePayload(
    @SerializedName("Image") val image: AlbumImageData
)

// Album Keywords Response (optimized tag scanning)
data class AlbumKeywordsResponse(
    @SerializedName("Expansions") val expansions: Map<String, AlbumExpansionContainer>? = null
)

data class AlbumExpansionContainer(
    @SerializedName("AlbumKeywords") val albumKeywords: AlbumKeywordsContainer? = null
)

data class AlbumKeywordsContainer(
    @SerializedName("Keywords") val keywords: List<String>? = null
)

// User Top Keywords Response
data class TopKeywordsResponse(
    @SerializedName("Response") val response: TopKeywordsPayload
)

data class TopKeywordsPayload(
    @SerializedName("UserTopKeywords") val userTopKeywords: UserTopKeywordsContainer? = null
)

data class UserTopKeywordsContainer(
    @SerializedName("TopKeywords") val keywords: List<String>? = null
)

data class UpdateImageMetadataRequest(
    @SerializedName("Keywords") val keywords: String
)

data class AlbumPreview(
    val title: String,
    val thumbnailUrl: String?
)

data class SingleNodeResponse(
    @SerializedName("Response") val response: SingleNodePayload,
    @SerializedName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)

data class SingleNodePayload(
    @SerializedName("Node") val node: NodeData
)

// User Search Response & Expansions
data class UserSearchResponse(
    @SerializedName("Response") val response: UserSearchPayload,
    @SerializedName("Expansions") val expansions: Map<String, UserSearchExpansion>? = null
)

data class UserSearchPayload(
    @SerializedName("User") val users: List<UserData>? = emptyList(),
    @SerializedName("Pages") val pages: PagesData? = null
)

data class UserSearchExpansion(
    @SerializedName("UserAlbums") val userAlbums: UserAlbumsExpansion? = null,
    @SerializedName("HighlightImage") val highlightImage: AlbumImageData? = null
)

data class UserAlbumsExpansion(
    @SerializedName("Album") val albums1: List<AlbumDetails>? = null,
    @SerializedName("AlbumDetail") val albums2: List<AlbumDetails>? = null
) {
    val albums: List<AlbumDetails>
        get() = albums1 ?: albums2 ?: emptyList()
}

