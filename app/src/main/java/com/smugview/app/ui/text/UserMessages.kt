package com.smugview.app.ui.text

import com.smugview.app.data.api.isSyntheticCacheMiss
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

    const val OFFLINE_HEADING = "You're offline"
    const val EMPTY_GALLERY = "This gallery has no photos yet."
    const val FILTER_EMPTY = "No photos match the filters you chose."
    const val HOME_OFFLINE = "You're offline. Galleries show here when you're back online."
    const val HOME_EMPTY = "This site has no public galleries."

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

    /** `PASSWORD_CHECK_FAILED`: the password could not be checked (not "wrong password"). */
    fun passwordCheckFailed(cause: Problem): String = "Couldn't check the password. ${shortCause(cause)}"
}
