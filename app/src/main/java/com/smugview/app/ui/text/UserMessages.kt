package com.smugview.app.ui.text

import com.smugview.app.data.api.isSyntheticCacheMiss
import com.smugview.app.data.cast.WebCompanionFailure
import com.smugview.app.data.repository.AlbumLockedException
import com.smugview.app.data.repository.TransientReason
import retrofit2.HttpException

/** What the failed thing was. Only the noun in the texts changes with it (design 3.1). */
enum class Subject(val noun: String) {
    Gallery("gallery"), Folder("folder"), Photo("photo"), Site("site"), Search("search"), Tags("tag list")
}

/** What a failure means to the person looking at the screen (design 3.1). Never carries an exception's own text. */
sealed interface Problem {
    val subject: Subject

    /** No network (or OkHttp's synthetic 504 for `only-if-cached`) and nothing saved to show instead. */
    data class OfflineNothingSaved(override val subject: Subject) : Problem

    /** No answer in time. */
    data class Slow(override val subject: Subject) : Problem

    /** 429, after the retries of `RetryingCallFactory`. */
    data class RateLimited(override val subject: Subject) : Problem

    /** 500-599 except the synthetic 504. */
    data class SmugMugTrouble(override val subject: Subject, val code: Int) : Problem

    /** 404 where nothing says it is locked: deleted, moved or made private. */
    data class Gone(override val subject: Subject) : Problem

    /** Needs its password: an [AlbumLockedException] with no pending reason, or a 401/404 under a locked root. */
    data class Locked(override val subject: Subject) : Problem

    /** A saved password could not be tried just now (4-8): the texts of [AlbumLockedException]. */
    data class LockedPending(override val subject: Subject, val reason: TransientReason) : Problem

    /** Anything else. [code] is an HTTP status or the exception's class name without "Exception". */
    data class Unexpected(override val subject: Subject, val code: String) : Problem

    companion object {
        /**
         * Which kind of problem [error] is. [underLockedRoot]: the failing folder or gallery sits under a password
         * root that has no session (`UnlockManager.lockOf`, cache-only), so a 404 or 401 means "locked", not "gone".
         * The caller finds that out; this function stays pure.
         */
        fun from(error: Throwable?, subject: Subject, underLockedRoot: Boolean = false): Problem = when {
            error is AlbumLockedException ->
                error.pendingReason?.let { LockedPending(subject, it) } ?: Locked(subject)
            error is HttpException && error.response()?.raw()?.isSyntheticCacheMiss() == true ->
                OfflineNothingSaved(subject)
            error is java.net.SocketTimeoutException || error is java.io.InterruptedIOException -> Slow(subject)
            error is java.io.IOException -> OfflineNothingSaved(subject)
            error is HttpException -> when (val code = error.code()) {
                429 -> RateLimited(subject)
                in 500..599 -> SmugMugTrouble(subject, code)
                404 -> if (underLockedRoot) Locked(subject) else Gone(subject)
                401 -> if (underLockedRoot) Locked(subject) else Unexpected(subject, code.toString())
                else -> Unexpected(subject, code.toString())
            }
            error == null -> Unexpected(subject, "unknown")
            else -> Unexpected(subject, error.javaClass.simpleName.removeSuffix("Exception").ifEmpty { "unknown" })
        }
    }
}

/** The button a failure offers. A screen with a back button shows [GoBack] as well. */
enum class ProblemAction(val label: String) {
    TryAgain(UserMessages.BUTTON_TRY_AGAIN),
    GoBack(UserMessages.BUTTON_GO_BACK),
    EnterPassword(UserMessages.BUTTON_ENTER_PASSWORD)
}

/**
 * Every word a failure puts on screen (design 5). Nothing here reads an exception's message: a screen that
 * needs a sentence about a failure asks for it here, so the text is the same everywhere and has a test.
 */
object UserMessages {
    const val BUTTON_TRY_AGAIN = "Try again"
    const val BUTTON_GO_BACK = "Go back"
    const val BUTTON_ENTER_PASSWORD = "Enter password"
    const val BUTTON_SHOW_ALL = "Show all"
    const val BUTTON_LOAD_THE_REST = "Load the rest"
    const val REMOVE_FROM_COLLECTIONS = "Remove from collections"

    const val OFFLINE_HEADING = "You're offline"
    const val EMPTY_GALLERY = "This gallery has no photos yet."
    const val FILTER_EMPTY = "No photos match the filters you chose."
    const val HOME_OFFLINE = "You're offline. Galleries show here when you're back online."
    const val HOME_EMPTY = "This site has no public galleries."

    /** `CAST_DIAL_DEVICE` (design 5): a device that answered DIAL and does not say who made it. */
    const val CAST_DIAL_DEVICE = "TV or streaming device"

    /** `CAST_COMPANION_FAILED`: no port from 8080 to 8089 could be bound for the screen link. */
    const val CAST_COMPANION_FAILED =
        "Couldn't start the screen link on this phone: ports 8080 to 8089 are in use. Close other apps that share your screen, then try again."

    fun heading(problem: Problem): String = when (problem) {
        is Problem.OfflineNothingSaved -> OFFLINE_HEADING
        is Problem.Slow -> "No answer from SmugMug"
        is Problem.RateLimited -> "SmugMug asked the app to slow down"
        is Problem.SmugMugTrouble -> "SmugMug is having trouble"
        is Problem.Gone -> "Not found on SmugMug"
        is Problem.Locked -> "Password needed"
        is Problem.LockedPending -> when (problem.reason) {
            TransientReason.Offline -> OFFLINE_HEADING
            TransientReason.Busy -> "SmugMug is having trouble"
        }
        is Problem.Unexpected -> "Couldn't load this ${problem.subject.noun}"
    }

    /**
     * The sentence under the heading. [siteName] fills the {site} of the launch text; without one it says "This site".
     */
    fun body(problem: Problem, siteName: String? = null): String = when (problem) {
        is Problem.OfflineNothingSaved -> when (problem.subject) {
            Subject.Site -> "${siteName ?: "This site"} hasn't been opened on this phone recently, so there's " +
                "nothing saved to show. Connect to the internet and tap Try again."
            Subject.Search, Subject.Tags -> shortCause(problem)
            else -> "This ${problem.subject.noun} hasn't been opened on this phone yet, so there's nothing saved " +
                "to show. Connect to the internet and tap Try again."
        }
        is Problem.Slow -> "SmugMug didn't answer in time. The connection may be weak. Tap Try again."
        is Problem.RateLimited -> "Too many requests in a short time. Wait a minute, then tap Try again."
        is Problem.SmugMugTrouble -> "SmugMug answered with error ${problem.code}. This is on SmugMug's side. " +
            "Wait a few minutes, then tap Try again."
        is Problem.Gone ->
            if (problem.subject == Subject.Site) "${siteName ?: "This site"} may have been deleted, moved, or made private. Go back and pick another site."
            else "This ${problem.subject.noun} may have been deleted, moved, or made private. Go back and refresh the folder."
        is Problem.Locked -> "This ${if (problem.subject == Subject.Folder) "folder" else "gallery"} needs its password."
        is Problem.LockedPending -> when (problem.reason) {
            TransientReason.Offline -> AlbumLockedException.OFFLINE_MESSAGE
            TransientReason.Busy -> AlbumLockedException.BUSY_MESSAGE
        }
        is Problem.Unexpected -> "SmugMug answered with error ${problem.code}. Tap Try again. " +
            "If it keeps happening, the diagnostics report has the details."
    }

    /** One short clause for banners, Home, Search and Tags: "You went offline." */
    fun shortCause(problem: Problem): String = when (problem) {
        is Problem.OfflineNothingSaved -> "You went offline."
        is Problem.LockedPending ->
            if (problem.reason == TransientReason.Offline) "You went offline." else "SmugMug is having trouble."
        is Problem.Slow -> "SmugMug didn't answer."
        is Problem.RateLimited -> "SmugMug asked the app to slow down."
        is Problem.SmugMugTrouble -> "SmugMug is having trouble."
        is Problem.Gone -> "Error 404."
        is Problem.Locked -> "Error 401."
        is Problem.Unexpected -> "Error ${problem.code}."
    }

    /** The heading and the sentence as one line, for a screen that has room for a single string. */
    fun line(problem: Problem, siteName: String? = null): String =
        if (problem is Problem.LockedPending) body(problem, siteName)
        else "${heading(problem)}. ${body(problem, siteName)}"

    /** What the main button does. [Problem.Gone] only goes back; a locked gallery asks for its password. */
    fun primaryAction(problem: Problem): ProblemAction = when (problem) {
        is Problem.Gone -> ProblemAction.GoBack
        is Problem.Locked -> ProblemAction.EnterPassword
        else -> ProblemAction.TryAgain
    }

    /** `PARTIAL`: the banner under a grid that stopped early. */
    fun partial(shown: Int, expected: Int?, cause: Problem): String =
        if (expected != null) "Showing $shown of $expected photos. ${shortCause(cause)}"
        else "Showing $shown photos. ${shortCause(cause)}"

    /** True when the cause is "no network": the 5-9 offline notice already says what is shown then. */
    fun isOfflineKind(problem: Problem): Boolean =
        problem is Problem.OfflineNothingSaved || (problem is Problem.LockedPending && problem.reason == TransientReason.Offline)

    /** `CAST_COMPANION`: what to type in the browser of the screen being cast to; [url] is the one the server really bound. */
    fun castCompanion(url: String): String = "On the screen you're casting to, open its web browser and go to $url"

    /** `CAST_COMPANION_NO_WIFI`: the phone has no Wi-Fi address, so there is nothing to type on the screen (6-11c). */
    const val CAST_COMPANION_NO_WIFI =
        "SmugView can't find a Wi-Fi connection on this phone, so the screen link can't start. Connect to the same Wi-Fi as the screen, then try again."

    /** The line under "Web Companion Setup": the URL when bound, else the reason it is not (no Wi-Fi, or ports in use). */
    fun castCompanionLine(url: String?, failure: WebCompanionFailure?): String = when {
        url != null -> castCompanion(url)
        failure == WebCompanionFailure.NoWifi -> CAST_COMPANION_NO_WIFI
        else -> CAST_COMPANION_FAILED
    }

    /** `HOME_FAILED`: the featured-galleries row when the request failed and nothing saved could stand in. */
    fun homeFailed(problem: Problem): String = "Couldn't load the galleries. ${shortCause(problem)}"

    /**
     * What Home's featured row says when it has no galleries to show: [HOME_EMPTY] only when the request succeeded
     * ([problem] null); a failure says so, with the offline kind in the offline words.
     */
    fun homeAlbums(problem: Problem?): String = when {
        problem == null -> HOME_EMPTY
        isOfflineKind(problem) -> HOME_OFFLINE
        else -> homeFailed(problem)
    }

    /** `SEARCH_NO_MATCH`: the search finished everywhere and found nothing. */
    fun searchNoMatch(siteName: String, query: String): String = "Nothing on $siteName matches “$query”."

    /** `SEARCH_PHOTOS_FAILED`: the photo search failed; the galleries and folders come from the index on this phone. */
    fun searchPhotosFailed(cause: Problem): String =
        "Couldn't search photos. ${shortCause(cause)} Galleries and folders below come from this phone."

    /** `TAGS_FAILED`: the tag list or the tag scan failed. */
    fun tagsFailed(cause: Problem): String = "Couldn't load the tags. ${shortCause(cause)}"

    /** `DOWNLOAD_FAILED`: a photo could not be saved to this phone (the toast of the download button). */
    fun downloadFailed(cause: Problem): String = "Couldn't save the photo. ${shortCause(cause)}"

    /** `DOWNLOAD_SAVED`, `DOWNLOAD_OFFLINE`, `DOWNLOAD_PERMISSION`, `DOWNLOAD_NO_SPACE`: the other download toasts (design 5). */
    const val DOWNLOAD_SAVED = "Saved to Pictures/SmugView"
    const val DOWNLOAD_OFFLINE = "You're offline, and this photo isn't saved on this phone."
    const val DOWNLOAD_PERMISSION = "To save photos on this Android version, SmugView needs permission to use storage. You can allow it in Settings."
    const val DOWNLOAD_NO_SPACE = "Not enough space on this phone to save this photo."

    /** `SHARE_CAPTION`: under the QR code of the share dialog. */
    const val SHARE_CAPTION = "Opens the SmugMug page. Password galleries ask for their password there."

    /** `SHARE_LINK` / `SHARE_PICTURE`: the buttons of the share dialog. */
    const val SHARE_LINK = "Share link"
    const val SHARE_PICTURE = "Share picture"

    /** `SHARE_NO_LINK`: nothing to show a QR code for, or to copy or send. */
    const val SHARE_NO_LINK = "No web link for this photo yet. Open its gallery once while online."

    /** `SHARE_PREPARING`: toast while the picture is made ready. */
    const val SHARE_PREPARING = "Preparing the picture…"

    /** `SHARE_STRIPPED`: small print under "Share picture". */
    const val SHARE_STRIPPED = "Camera details and location are removed from shared pictures."

    /** `SHARE_FAILED`: the picture could not be made ready (toast). */
    fun shareFailed(cause: Problem): String = "Couldn't prepare the picture. ${shortCause(cause)}"

    /** `PASSWORD_NOT_KEPT` (design 5, Q5 (a)): the password prompt, once, when this phone's secure storage isn't working. */
    const val PASSWORD_NOT_KEPT =
        "This phone's secure storage isn't working, so this password will be asked for again next time SmugView starts."

    /** `PASSWORD_CHECK_FAILED`: the password could not be checked (not "wrong password"). */
    fun passwordCheckFailed(cause: Problem): String = "Couldn't check the password. ${shortCause(cause)}"
}

/** The same failure about another thing: the viewer shows its album's [Problem] with the noun "photo" (design 3.5). */
fun Problem.forSubject(subject: Subject): Problem = when (this) {
    is Problem.OfflineNothingSaved -> copy(subject = subject)
    is Problem.Slow -> copy(subject = subject)
    is Problem.RateLimited -> copy(subject = subject)
    is Problem.SmugMugTrouble -> copy(subject = subject)
    is Problem.Gone -> copy(subject = subject)
    is Problem.Locked -> copy(subject = subject)
    is Problem.LockedPending -> copy(subject = subject)
    is Problem.Unexpected -> copy(subject = subject)
}
