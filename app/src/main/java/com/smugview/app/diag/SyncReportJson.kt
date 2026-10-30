package com.smugview.app.diag

import org.json.JSONArray
import org.json.JSONObject

// `org.json` for the sync report (hand-serialised: the `diag` package is not kept by R8). The models live in the shared module.

fun UnlockAttempt.toJson(): JSONObject = JSONObject().apply {
    put("target", target); put("via", via); put("result", result)
    httpCode?.let { put("httpCode", it) }
    exception?.let { put("exception", it) }
    put("ms", ms)
    actionId?.let { put("actionId", it) }
}

fun unlockAttemptFromJson(o: JSONObject) = UnlockAttempt(
    target = o.optString("target"), via = o.optString("via"), result = o.optString("result"),
    httpCode = if (o.has("httpCode")) o.getInt("httpCode") else null,
    exception = if (o.has("exception")) o.getString("exception") else null,
    ms = o.optLong("ms"), actionId = if (o.has("actionId")) o.getString("actionId") else null
)

fun SyncRun.toJson(): JSONObject = JSONObject().apply {
    put("runId", runId); put("kind", kind.name); put("nickname", nickname); put("startedAt", startedAt)
    endedAt?.let { put("endedAt", it) }
    isFirstSync?.let { put("isFirstSync", it) }
    put("persistedCount", persistedCount)
    stopMarker?.let { put("stopMarker", it) }
    put("pagesFetched", pagesFetched); put("albumsSeen", albumsSeen)
    put("albumsNullLastUpdated", albumsNullLastUpdated)
    put("albumsWithParentNode", albumsWithParentNode)
    put("albumsPasswordSecurity", albumsPasswordSecurity)
    put("changedCount", changedCount); put("invalidatedParents", invalidatedParents)
    stop?.let { put("stop", it.name) }
    stopDetail?.let { put("stopDetail", it) }
    put("savedKeys", savedKeys)
    put("skippedAlreadyUnlocked", JSONArray(skippedAlreadyUnlocked))
    notes?.let { put("notes", it) }
    newInIndex30d?.let { put("newInIndex30d", it) }
    litDotNodes?.let { put("litDotNodes", it) }
    put("unlocks", JSONArray(unlocks.map { it.toJson() }))
}



internal fun SyncRun.applyJson(o: JSONObject) {
    if (o.has("endedAt")) endedAt = o.getLong("endedAt")
    if (o.has("isFirstSync")) isFirstSync = o.getBoolean("isFirstSync")
    persistedCount = o.optInt("persistedCount")
    stopMarker = if (o.has("stopMarker")) o.getString("stopMarker") else null
    pagesFetched = o.optInt("pagesFetched"); albumsSeen = o.optInt("albumsSeen")
    albumsNullLastUpdated = o.optInt("albumsNullLastUpdated")
    albumsWithParentNode = o.optInt("albumsWithParentNode")
    albumsPasswordSecurity = o.optInt("albumsPasswordSecurity")
    changedCount = o.optInt("changedCount"); invalidatedParents = o.optInt("invalidatedParents")
    stop = if (o.has("stop")) runCatching { StopReason.valueOf(o.getString("stop")) }.getOrNull() else null
    stopDetail = if (o.has("stopDetail")) o.getString("stopDetail") else null
    savedKeys = o.optInt("savedKeys")
    skippedAlreadyUnlocked = o.optJSONArray("skippedAlreadyUnlocked")?.let { a ->
        (0 until a.length()).map { a.getString(it) }
    } ?: emptyList()
    notes = if (o.has("notes")) o.getString("notes") else null
    newInIndex30d = if (o.has("newInIndex30d")) o.getInt("newInIndex30d") else null
    litDotNodes = if (o.has("litDotNodes")) o.getInt("litDotNodes") else null
    unlocks.clear()
    o.optJSONArray("unlocks")?.let { a ->
        for (i in 0 until a.length()) unlocks.add(unlockAttemptFromJson(a.getJSONObject(i)))
    }
}

fun syncRunFromJson(o: JSONObject): SyncRun {
    val r = SyncRun(
        o.getString("runId"), SyncKind.valueOf(o.getString("kind")),
        o.optString("nickname"), o.optLong("startedAt")
    )
    r.applyJson(o)
    return r
}
