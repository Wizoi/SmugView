package com.smugview.app.data.offline

import com.smugview.app.data.db.GalleryFileRow
import com.smugview.app.data.db.OfflineFile
import com.smugview.app.data.db.OfflineGallery

/**
 * What a collection row says about its saved copy (phase 5 design 3 and 4, step 5-9). Pure: the screens only draw
 * what is computed here, so the words and the buttons are unit-tested without Compose.
 *
 *  - [Kind.SAVED]: the file is on this phone ([note] says so for a video, whose copy is a still picture).
 *  - [Kind.SAVING]: PENDING or DOWNLOADING.
 *  - [Kind.FAILED]: the cause is in [text] ([OfflineMessages.forFailure]). [retryable] means the app tries again by
 *    itself (busy, offline, a lock that may clear); a permanent failure waits for the user.
 *    "Try again" is offered on every failure but GONE (the photo no longer exists, so only Remove makes sense).
 */
data class RowState(
    val kind: Kind,
    val text: String,
    val note: String? = null,
    val retryable: Boolean = false,
    val canTryAgain: Boolean = false,
    val canRemove: Boolean = false
) {
    enum class Kind { SAVED, SAVING, FAILED }
}

/** What a kept gallery's row says: its listing, its progress, and the Wi-Fi rule (Q1, Q3). */
data class GallerySummary(
    val albumKey: String,
    val kind: Kind,
    /** The main line: saved / saving N of M / waiting for Wi-Fi / the gallery's own failure. */
    val text: String,
    /** A second line: what failed in a gallery that is also saving. */
    val detail: String? = null,
    val done: Int,
    val total: Int,
    /** "Keep offline · 103 MB" (size from the listing, or "size unknown" before it). */
    val keepLabel: String,
    /** The user's rule: false = Wi-Fi only (the default), true = mobile data too. */
    val useMobileDataToo: Boolean,
    /** "Use mobile data (103 MB)" while the gallery waits for Wi-Fi; null otherwise. */
    val useMobileDataAction: String? = null
) {
    enum class Kind { LISTING, SAVING, WAITING_WIFI, OFFLINE, SAVED, FAILED }
}

object OfflineRowState {
    private val VIDEO_FORMATS = setOf("MP4", "MOV", "M4V", "AVI", "MPG", "MPEG", "WMV", "MKV", "3GP", "WEBM", "FLV")

    fun isVideoFormat(format: String?): Boolean = format?.uppercase() in VIDEO_FORMATS

    private fun reasonOf(failure: String?): FailureReason? =
        failure?.let { name -> FailureReason.values().firstOrNull { it.name == name } }

    /** [needBytes] and [freeBytes] fill the storage text; nothing else needs the device. */
    fun of(file: OfflineFile, needBytes: Long = 0, freeBytes: Long = 0): RowState = when (file.state) {
        OfflineStore.DONE -> RowState(
            RowState.Kind.SAVED, OfflineMessages.SAVED,
            note = if (isVideoFormat(file.format)) OfflineMessages.VIDEO_STILL else null
        )
        OfflineStore.FAILED -> {
            val reason = reasonOf(file.failure) ?: FailureReason.UNEXPECTED
            RowState(
                RowState.Kind.FAILED,
                OfflineMessages.forFailure(reason, file.httpCode, needBytes, freeBytes),
                retryable = file.retryable,
                canTryAgain = reason != FailureReason.GONE,
                canRemove = true
            )
        }
        else -> RowState(RowState.Kind.SAVING, OfflineMessages.SAVING)
    }

    /**
     * The summary of one kept gallery. [rows] are its photos' file rows (empty until the first listing).
     * [needBytes] and [freeBytes] fill a storage failure's text. [connected] is false when the phone has no network
     * at all: a gallery that cannot move then says so instead of "Waiting for Wi-Fi".
     */
    fun summary(
        gallery: OfflineGallery,
        rows: List<GalleryFileRow>,
        needBytes: Long = 0,
        freeBytes: Long = 0,
        connected: Boolean = true
    ): GallerySummary {
        val done = rows.count { it.state == OfflineStore.DONE }
        val total = gallery.photoCount ?: rows.size
        val left = rows.filter { it.state != OfflineStore.DONE }
        val remainingBytes = if (rows.isEmpty() || left.any { it.expectedBytes == null }) null else left.sumOf { it.expectedBytes ?: 0L }
        val sizeKnown = rows.isNotEmpty() && rows.all { it.expectedBytes != null }
        val listedBytes = if (sizeKnown) rows.sumOf { it.expectedBytes ?: 0L } else null
        val keep = OfflineMessages.keepOffline(listedBytes)
        val mobileToo = !gallery.wifiOnly

        fun summary(kind: GallerySummary.Kind, text: String, detail: String? = null, action: String? = null) = GallerySummary(
            gallery.albumKey, kind, text, detail, done, total, keep, mobileToo, action
        )

        // The gallery itself failed (locked, offline, busy while listing): its own cause, nothing was listed or the
        // listing is stale.
        if (gallery.state == OfflineStore.FAILED) {
            val reason = reasonOf(gallery.failure) ?: FailureReason.UNEXPECTED
            return summary(GallerySummary.Kind.FAILED, OfflineMessages.forFailure(reason, null, needBytes, freeBytes))
        }
        val unsettled = rows.count { it.state == OfflineStore.PENDING || it.state == OfflineStore.DOWNLOADING }
        val downloading = rows.any { it.state == OfflineStore.DOWNLOADING }
        val failed = rows.filter { it.state == OfflineStore.FAILED }
        val detail = failed.takeIf { it.isNotEmpty() }?.let { failureLine(it, needBytes, freeBytes) }

        val notListed = gallery.state != OfflineStore.LISTED
        if (notListed || unsettled > 0) {
            // 5-11: a pass the 1 GB floor stopped is waiting for space, not for Wi-Fi; mobile data would not help.
            if (!downloading && failed.any { reasonOf(it.failure) == FailureReason.STORAGE_FULL }) {
                return summary(GallerySummary.Kind.FAILED, failureLine(failed.filter { reasonOf(it.failure) == FailureReason.STORAGE_FULL }, needBytes, freeBytes))
            }
            // 5-11: with no network at all it is not "waiting for Wi-Fi", and there is no mobile network to offer.
            if (!connected && !downloading) {
                return summary(
                    GallerySummary.Kind.OFFLINE,
                    OfflineMessages.NO_CONNECTION,
                    detail ?: if (notListed) null else OfflineMessages.noConnectionProgress(done, total)
                )
            }
            // Nothing is on the wire until a Wi-Fi pass for a Wi-Fi-only gallery; the user may widen the rule.
            if (gallery.wifiOnly && !downloading) {
                return summary(
                    GallerySummary.Kind.WAITING_WIFI,
                    OfflineMessages.WAITING_FOR_WIFI,
                    detail ?: if (notListed) null else OfflineMessages.waitingWifi(done, total),
                    OfflineMessages.useMobileData(if (notListed) null else remainingBytes)
                )
            }
            if (notListed && rows.isEmpty()) return summary(GallerySummary.Kind.LISTING, OfflineMessages.SAVING, detail)
            return summary(GallerySummary.Kind.SAVING, OfflineMessages.saving(done, total), detail)
        }
        if (failed.isNotEmpty()) return summary(GallerySummary.Kind.FAILED, detail ?: "")
        return summary(GallerySummary.Kind.SAVED, OfflineMessages.SAVED)
    }

    /** "3 photos can't be saved: removed from SmugMug": the largest group's reason (design section 3). */
    private fun failureLine(failed: List<GalleryFileRow>, needBytes: Long, freeBytes: Long): String {
        val groups = failed.groupBy { reasonOf(it.failure) ?: FailureReason.UNEXPECTED }
        val (reason, rows) = groups.entries.maxByOrNull { it.value.size }!!
        // A full disk is one message for the whole gallery, with the numbers.
        if (reason == FailureReason.STORAGE_FULL) return OfflineMessages.storage(needBytes, freeBytes)
        return OfflineMessages.galleryFailed(failed.size, reason, rows.firstOrNull()?.httpCode)
    }
}
