package com.smugview.app.ui.grid

import com.smugview.app.ui.text.Problem
import com.smugview.app.ui.text.UserMessages

/**
 * Phase 6 (design 3.2, R-46/R-47): what the gallery screen shows, decided in one pure place so it can be tested
 * without a screen. [GridView.of] reads only plain numbers and the album's [Problem]; the screen draws the answer.
 *
 * "Raw" is the count the album holds before the type and tag filters; "shown" is what is left after them.
 */
sealed interface GridView {
    /** No photo yet and a request is running. */
    data object Loading : GridView

    /** Nothing to show and the reason is a failure: the real cause and the way out. */
    data class Failed(val problem: Problem) : GridView

    /** The request succeeded and the gallery has no photos. */
    data object EmptyGallery : GridView

    /** The gallery has photos but the type or tag filter hides every one: offer "Show all". */
    data object FilterEmpty : GridView

    /**
     * Nothing to show and nothing to claim: a password prompt is open (or about to be) for this gallery, or the
     * load was interrupted. Saying "no photos" here would be false.
     */
    data object Blank : GridView

    /** The grid, with an optional line at the bottom of the screen. */
    data class Photos(val banner: Banner? = null) : GridView

    sealed interface Banner {
        val text: String

        /** The 5-9 offline notice: what is shown and why. It already says everything, so it wins over [Partial]. */
        data class Notice(override val text: String) : Banner

        /** A page after the first failed: the pages that arrived are shown; "Load the rest" tries again. */
        data class Partial(override val text: String) : Banner
    }

    companion object {
        fun of(
            rawCount: Int,
            shownCount: Int,
            loading: Boolean,
            problem: Problem?,
            complete: Boolean,
            notice: String?,
            expected: Int?,
            filtersActive: Boolean
        ): GridView {
            if (shownCount > 0) {
                val banner = when {
                    notice != null -> Banner.Notice(notice)
                    problem != null && !complete ->
                        Banner.Partial(UserMessages.partial(rawCount, expected?.takeIf { it > rawCount }, problem))
                    else -> null
                }
                return Photos(banner)
            }
            return when {
                // Photos exist but the filters hide them all: that is a filter state, not a failure and not "empty".
                rawCount > 0 -> if (filtersActive) FilterEmpty else Loading // no filter and photos held: paging has not caught up yet
                problem != null -> Failed(problem)
                loading -> Loading
                complete -> EmptyGallery
                else -> Blank
            }
        }
    }
}
