package com.smugview.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * iOS spike: the first models ported from `app/.../data/api/ResponseModels.kt` (Gson) to kotlinx.serialization, which throws on a
 * missing property that has no default. Ported 1:1; the only deliberate change is `UserData.name` and `UserUris.node`, nullable
 * because a user with no gallery (`mdkahn2` in the recorded `user!search`) comes back without them (ModelParityTest).
 */
@Serializable
data class PagesData(
    @SerialName("Start") val start: Int,
    @SerialName("Count") val count: Int,
    @SerialName("Total") val total: Int,
    @SerialName("Next") private val nextField: String? = null,
    @SerialName("NextPage") private val nextPageField: String? = null
) {
    val next: String? get() = nextField ?: nextPageField
}

@Serializable
data class UserResponse(
    @SerialName("Response") val response: UserPayload,
    @SerialName("Expansions") val expansions: Map<String, UserExpansionContainer>? = null
)

@Serializable data class UserPayload(@SerialName("User") val user: UserData)

@Serializable
data class UserData(
    @SerialName("NickName") val nickName: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Uris") val uris: UserUris? = null,
    @SerialName("WebUri") val webUri: String? = null
)

@Serializable data class UserUris(@SerialName("Node") val node: String? = null)

@Serializable
data class UserExpansionContainer(@SerialName("BioImage") val bioImage: BioImageData? = null)

@Serializable
data class BioImageData(
    @SerialName("ImageKey") val imageKey: String? = null,
    @SerialName("ThumbnailUrl") val thumbnailUrl: String? = null
)

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

@Serializable data class ExpansionVideo(@SerialName("Url") val url: String? = null)
@Serializable data class ExpansionImage(@SerialName("ThumbnailUrl") val thumbnailUrl: String? = null)

@Serializable
data class NodeListPayload(
    @SerialName("Node") val nodes: List<NodeData>? = emptyList(),
    @SerialName("Pages") val pages: PagesData? = null
)

@Serializable
data class NodeData(
    @SerialName("Uri") val uri: String,
    @SerialName("NodeID") val nodeId: String,
    @SerialName("Type") val type: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Description") val description: String? = null,
    @SerialName("SecurityType") val securityType: String? = null,
    @SerialName("Privacy") val privacy: String? = null,
    @SerialName("PasswordHint") val passwordHint: String? = null,
    @SerialName("WebUri") val webUri: String? = null,
    @SerialName("Uris") val uris: NodeUris,
    @SerialName("DateModified") val dateModified: String? = null,
    @SerialName("ShowCoverImage") val showCoverImage: Boolean? = null
)

@Serializable
data class NodeUris(
    @SerialName("ChildNodes") val childNodes: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("HighlightImage") val highlightImage: String? = null,
    @SerialName("NodeCoverImage") val nodeCoverImage: String? = null,
    @SerialName("ParentNode") val parentNode: String? = null,
    @SerialName("Folder") val folder: String? = null
)

@Serializable
data class UserSearchResponse(
    @SerialName("Response") val response: UserSearchPayload
)

@Serializable
data class UserSearchPayload(
    @SerialName("User") val users: List<UserData>? = emptyList(),
    @SerialName("Pages") val pages: PagesData? = null
)
