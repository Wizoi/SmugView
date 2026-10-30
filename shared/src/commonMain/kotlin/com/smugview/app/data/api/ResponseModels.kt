package com.smugview.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Generic container
@Serializable
data class UriContainer(
    @SerialName("Uri") val uri: String,
    @SerialName("Locator") val locator: String? = null
)

@Serializable
data class PagesData(
    @SerialName("Start") val start: Int,
    @SerialName("Count") val count: Int,
    @SerialName("Total") val total: Int,
    @SerialName("Next") private val nextField: String? = null,
    @SerialName("NextPage") private val nextPageField: String? = null
) {
    val next: String?
        get() = nextField ?: nextPageField
}

// User API Response
@Serializable
data class UserResponse(
    @SerialName("Response") val response: UserPayload,
    @SerialName("Expansions") val expansions: Map<String, UserExpansionContainer>? = null
)
@Serializable
data class UserPayload(
    @SerialName("User") val user: UserData
)
@Serializable
data class UserData(
    @SerialName("NickName") val nickName: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Uris") val uris: UserUris,
    @SerialName("WebUri") val webUri: String? = null,
    val bioImageKey: String? = null,
    /** The BioImage's path-bearing `ThumbnailUrl` from the `_expand=BioImage` the profile call already makes (null if none). */
    val bioImageThumbnailUrl: String? = null
) {
    /** The site's root node URI. A profile always has one; a `user!search` hit may not (recorded answer), so only profile code asks for it. */
    val rootNodeUri: String get() = uris.node ?: error("user ${nickName} has no root node in this answer")
}
@Serializable
data class UserUris(
    @SerialName("Node") val node: String? = null
)
@Serializable
data class UserExpansionContainer(
    @SerialName("BioImage") val bioImage: BioImageData? = null
)

// Node List (Children) Response
@Serializable
data class NodeListResponse(
    @SerialName("Response") val response: NodeListPayload,
    @SerialName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)

@Serializable
data class ExpansionContainer(
    @SerialName("Image") val image: ExpansionImage? = null,
    @SerialName("LargestVideo") val largestVideo: ExpansionVideo? = null
)

@Serializable
data class ExpansionVideo(
    @SerialName("Url") val url: String? = null
)

@Serializable
data class ExpansionImage(
    @SerialName("ThumbnailUrl") val thumbnailUrl: String? = null
)
@Serializable
data class NodeListPayload(
    @SerialName("Node") val nodes: List<NodeData>? = emptyList(),
    @SerialName("Pages") val pages: PagesData? = null
)
@Serializable
data class NodeData(
    @SerialName("Uri") val uri: String,
    @SerialName("NodeID") val nodeId: String,
    @SerialName("Type") val type: String, // "Folder" or "Album"
    @SerialName("Name") val name: String? = null,
    @SerialName("Description") val description: String? = null,
    @SerialName("SecurityType") val securityType: String? = null,
    @SerialName("Privacy") val privacy: String? = null,
    @SerialName("PasswordHint") val passwordHint: String? = null,
    @SerialName("WebUri") val webUri: String? = null,
    @SerialName("Uris") val uris: NodeUris,
    @SerialName("DateModified") val dateModified: String? = null,
    /** The site owner's switch for the gallery's cover image: false means it must not be shown. Absent on nodes that never had one. */
    @SerialName("ShowCoverImage") val showCoverImage: Boolean? = null
)
@Serializable
data class NodeUris(
    @SerialName("ChildNodes") val childNodes: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("HighlightImage") val highlightImage: String? = null,
    /** The gallery's cover image (`node/{id}!cover`), a different picture from [highlightImage] on most galleries (checked live 2026-10-29: 8 of 12). */
    @SerialName("NodeCoverImage") val nodeCoverImage: String? = null,
    @SerialName("ParentNode") val parentNode: String? = null,
    /** Present on `user!albums` galleries: `/api/v2/folder/user/{nick}/{path}` of the containing folder. */
    @SerialName("Folder") val folder: String? = null
)

// Node lineage (`node/{id}!parents`): self first, then each ancestor up to the site root.
@Serializable
data class NodeParentsResponse(
    @SerialName("Response") val response: NodeParentsPayload
)
@Serializable
data class NodeParentsPayload(
    @SerialName("Node") val nodes: List<ParentNodeData>? = null
)
@Serializable
data class ParentNodeData(
    @SerialName("Uri") val uri: String? = null,
    @SerialName("NodeID") val nodeId: String,
    @SerialName("Type") val type: String? = null,
    @SerialName("Name") val name: String? = null,
    /** "Password", "None", ... SmugMug never sends "Inherited" (findings #19). */
    @SerialName("SecurityType") val securityType: String? = null,
    @SerialName("EffectiveSecurityType") val effectiveSecurityType: String? = null,
    @SerialName("WebUri") val webUri: String? = null
)

// Album Images Response
@Serializable
data class AlbumImagesResponse(
    @SerialName("Response") val response: AlbumImagesPayload,
    @SerialName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)
@Serializable
data class AlbumImagesPayload(
    @SerialName("AlbumImage") val images: List<AlbumImageData>? = emptyList(),
    @SerialName("Pages") val pages: PagesData? = null
)
@Serializable
data class AlbumImageData(
    @SerialName("ImageKey") val imageKey: String,
    @SerialName("Title") val title: String? = null,
    @SerialName("Caption") val caption: String? = null,
    @SerialName("ThumbnailUrl") val thumbnailUrl: String? = null,
    @SerialName("ArchivedUri") val archivedUri: String? = null,
    @SerialName("Date") val date: String? = null,
    @SerialName("DateTime") val dateTime: String? = null,
    @SerialName("FileName") val fileName: String? = null,
    @SerialName("Keywords") val keywords: String? = null,
    @SerialName("KeywordArray") val keywordArray: List<String>? = null,
    @SerialName("WebUri") val webUri: String? = null,
    @SerialName("OriginalWidth") val originalWidth: Int? = null,
    @SerialName("OriginalHeight") val originalHeight: Int? = null,
    @SerialName("OriginalSize") val originalSize: Long? = null,
    /** What the original weighs and its MD5 (5-7): the offline download verifies both. */
    @SerialName("ArchivedSize") val archivedSize: Long? = null,
    @SerialName("ArchivedMD5") val archivedMd5: String? = null,
    @SerialName("Format") val format: String? = null,
    @SerialName("Uris") val uris: AlbumImageUris? = null,
    var videoUrl: String? = null,
    /**
     * The saved copy on this phone (5-9, a `file:` URI), set by the view model for a photo with a DONE offline file. Never
     * serialized (`@Transient`: the serializer skips it). The viewer shows it first, so a saved photo opens with no network.
     */
    @kotlinx.serialization.Transient var localUri: String? = null
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

@Serializable
data class AlbumImageUris(
    @SerialName("LargestVideo") val largestVideo: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("ImageAlbum") val imageAlbum: String? = null,
    @SerialName("ImageSizeDetails") val imageSizeDetails: String? = null
)

// Image Size Details Response
@Serializable
data class ImageSizeDetailsResponse(
    @SerialName("Response") val response: ImageSizeDetailsResponseWrapper
)
@Serializable
data class ImageSizeDetailsResponseWrapper(
    @SerialName("ImageSizeDetails") val details: ImageSizeDetailsPayload
)
@Serializable
data class ImageSizeDetailsPayload(
    @SerialName("ImageSizeMedium") val medium: ImageSizeEntry? = null,
    @SerialName("ImageSizeLarge") val large: ImageSizeEntry? = null,
    @SerialName("ImageSizeXLarge") val xLarge: ImageSizeEntry? = null,
    @SerialName("ImageSizeX2Large") val x2Large: ImageSizeEntry? = null,
    @SerialName("ImageSizeX3Large") val x3Large: ImageSizeEntry? = null,
    @SerialName("ImageSizeOriginal") val original: ImageSizeEntry? = null
) {
    /** Largest non-original rendition SmugMug actually generated for this image. */
    val bestHalfway: ImageSizeEntry?
        get() = x3Large ?: x2Large ?: xLarge ?: large ?: medium
}
@Serializable
data class ImageSizeEntry(
    @SerialName("Url") val url: String,
    @SerialName("Width") val width: Int,
    @SerialName("Height") val height: Int
)

// Image Metadata (EXIF) Response
@Serializable
data class ExifResponse(
    @SerialName("Response") val response: ExifPayload
)
@Serializable
data class ExifPayload(
    @SerialName("ImageMetadata") val exif: ExifData
)
@Serializable
data class ExifData(
    @SerialName("Make") val make: String? = null,
    @SerialName("Model") val model: String? = null,
    @SerialName("Lens") val lens: String? = null,
    @SerialName("Aperture") val aperture: String? = null,
    @SerialName("Exposure") val exposure: String? = null,
    @SerialName("ISO") val iso: Int? = null,
    @SerialName("FocalLength") val focalLength: String? = null,
    @SerialName("DateTimeCreated") val dateTimeCreated: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null
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
@Serializable
data class AlbumResponse(
    @SerialName("Response") val response: AlbumPayload
)
@Serializable
data class AlbumPayload(
    @SerialName("Album") val album: AlbumDetails
)
@Serializable
data class AlbumDetails(
    @SerialName("Uri") val uri: String,
    @SerialName("AlbumKey") val albumKey: String,
    @SerialName("NodeID") val nodeId: String? = null,
    @SerialName("Name") val name: String = "", // a locked album answers without a Name (album-locked.json)
    @SerialName("GalleryStyle") val galleryStyle: String? = null,
    @SerialName("UrlPath") val urlPath: String? = null,
    @SerialName("WebUri") val webUri: String? = null,
    @SerialName("ImageCount") val imageCount: Int? = null,
    @SerialName("SecurityType") val securityType: String? = null,
    @SerialName("PasswordHint") val passwordHint: String? = null,
    @SerialName("Uris") val uris: NodeUris? = null,
    @SerialName("LastUpdated") val dateModified: String? = null,
    /** When the photos in the gallery last changed: the one "is it new?" date (design Q3). */
    @SerialName("ImagesLastUpdated") val imagesLastUpdated: String? = null,
    /**
     * What this caller may see of the gallery: "Public", or "Password" while it is locked for them (the
     * `!images` of a locked gallery is a 200 with no photos, the shape of an empty one). The server sends
     * it whatever `_filter` says (design P7), so it is not in the filter.
     */
    @SerialName("ResponseLevel") val responseLevel: String? = null
) {
    val isLocked: Boolean get() = responseLevel == "Password"
}

@Serializable
data class UserAlbumsResponse(
    @SerialName("Response") val response: UserAlbumsPayload,
    @SerialName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)
@Serializable
data class UserAlbumsPayload(
    @SerialName("Album") val albums: List<AlbumDetails>? = emptyList(),
    @SerialName("Pages") val pages: PagesData? = null
)

val AlbumImageData.isVideo: Boolean
    get() = (format?.lowercase()?.let { it == "mp4" || it == "mov" || it == "avi" || it == "3gp" || it == "mkv" || it == "video" } ?: false) ||
            (archivedUri?.lowercase()?.contains(".mp4") ?: false) ||
            (archivedUri?.lowercase()?.contains(".mov") ?: false)

// User BioImage Response
@Serializable
data class BioImageResponse(
    @SerialName("Response") val response: BioImagePayload
)
@Serializable
data class BioImagePayload(
    @SerialName("BioImage") val bioImage: BioImageData
)
@Serializable
data class BioImageData(
    @SerialName("ImageKey") val imageKey: String,
    @SerialName("ThumbnailUrl") val thumbnailUrl: String? = null
)

// Image Search Response
@Serializable
data class ImageSearchResponse(
    @SerialName("Response") val response: ImageSearchPayload,
    @SerialName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)
@Serializable
data class ImageSearchPayload(
    @SerialName("Image") val images: List<AlbumImageData>? = emptyList(),
    @SerialName("Pages") val pages: PagesData? = null
)

// Single Image Response
@Serializable
data class ImageResponse(
    @SerialName("Response") val response: ImagePayload,
    @SerialName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)
@Serializable
data class ImagePayload(
    @SerialName("Image") val image: AlbumImageData
)

// `image/{key}-0` narrowed to where the original is (phase 5 offline downloads)
@Serializable
data class ImageSourceResponse(
    @SerialName("Response") val response: ImageSourcePayload
)
@Serializable
data class ImageSourcePayload(
    @SerialName("Image") val image: ImageSourceData
)
@Serializable
data class ImageSourceData(
    @SerialName("ImageKey") val imageKey: String? = null,
    @SerialName("ArchivedUri") val archivedUri: String? = null,
    @SerialName("ArchivedSize") val archivedSize: Long? = null,
    @SerialName("ArchivedMD5") val archivedMd5: String? = null,
    @SerialName("Format") val format: String? = null,
    @SerialName("IsVideo") val isVideo: Boolean? = null
)

// User Top Keywords Response
@Serializable
data class TopKeywordsResponse(
    @SerialName("Response") val response: TopKeywordsPayload
)

@Serializable
data class TopKeywordsPayload(
    @SerialName("UserTopKeywords") val userTopKeywords: UserTopKeywordsContainer? = null
)

@Serializable
data class UserTopKeywordsContainer(
    @SerialName("TopKeywords") val keywords: List<String>? = null
)

@Serializable
data class UpdateImageMetadataRequest(
    @SerialName("Keywords") val keywords: String
)

@Serializable
data class AlbumPreview(
    val title: String,
    val thumbnailUrl: String?
)

@Serializable
data class SingleNodeResponse(
    @SerialName("Response") val response: SingleNodePayload,
    @SerialName("Expansions") val expansions: Map<String, ExpansionContainer>? = null
)

@Serializable
data class SingleNodePayload(
    @SerialName("Node") val node: NodeData
)

// User Search Response & Expansions
@Serializable
data class UserSearchResponse(
    @SerialName("Response") val response: UserSearchPayload,
    @SerialName("Expansions") val expansions: Map<String, UserSearchExpansion>? = null
)

@Serializable
data class UserSearchPayload(
    @SerialName("User") val users: List<UserData>? = emptyList(),
    @SerialName("Pages") val pages: PagesData? = null
)

@Serializable
data class UserSearchExpansion(
    @SerialName("UserAlbums") val userAlbums: UserAlbumsExpansion? = null,
    @SerialName("HighlightImage") val highlightImage: AlbumImageData? = null
)

@Serializable
data class UserAlbumsExpansion(
    @SerialName("Album") val albums1: List<AlbumDetails>? = null,
    @SerialName("AlbumDetail") val albums2: List<AlbumDetails>? = null
) {
    val albums: List<AlbumDetails>
        get() = albums1 ?: albums2 ?: emptyList()
}

