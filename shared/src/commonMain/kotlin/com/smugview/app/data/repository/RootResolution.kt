package com.smugview.app.data.repository

/** Where a node's password lives (design phase-2 section 3.1). */
sealed interface RootResolution {
    /** The nearest node on the lineage (self included) whose SecurityType is "Password". */
    data class Resolved(val nodeId: String) : RootResolution

    /** The lineage was read, and no node on it is password-protected. */
    data object NotProtected : RootResolution

    /** The lineage could not be read (offline, 429, 5xx, parse). Callers must not guess. */
    data object Unknown : RootResolution
}
