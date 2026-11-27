package com.smugview.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A parent whose children were written by a full listing (`replaceChildren`). Rows under a parent with no row here may be strays
 * (a lineage or search write), so they are not served as the listing (R-06). `virtual:` parents are built locally and never have one.
 */
@Entity(tableName = "node_listings")
data class NodeListing(
    @PrimaryKey val parentNodeId: String,
    val listedAt: Long
)
