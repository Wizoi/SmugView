package com.smugview.app.ui.grid

import com.smugview.app.ui.text.Problem
import com.smugview.app.ui.text.Subject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step 6-4 (design 3.2): which state the gallery grid is in, as a pure function of plain numbers. */
class GridViewTest {
    private val offline = Problem.OfflineNothingSaved(Subject.Gallery)
    private val trouble = Problem.SmugMugTrouble(Subject.Gallery, 503)

    private fun of(
        raw: Int = 0, shown: Int = raw, loading: Boolean = false, problem: Problem? = null, complete: Boolean = false,
        notice: String? = null, expected: Int? = null, filtersActive: Boolean = false
    ) = GridView.of(raw, shown, loading, problem, complete, notice, expected, filtersActive)

    @Test fun `no photos and a request running is the spinner, even when a failure is left over`() {
        assertEquals(GridView.Loading, of(loading = true))
    }

    @Test fun `no photos and a problem is a failure with its kind`() {
        assertEquals(GridView.Failed(offline), of(problem = offline))
        assertEquals(GridView.Failed(trouble), of(problem = trouble, loading = false))
    }

    @Test fun `no photos, nothing wrong and the gallery complete is an empty gallery`() {
        assertEquals(GridView.EmptyGallery, of(complete = true))
    }

    @Test fun `no photos, not complete, no problem and no request is blank, never a false empty claim`() {
        assertEquals(GridView.Blank, of(complete = false))
    }

    @Test fun `photos held but every one filtered out is the filter state`() {
        assertEquals(GridView.FilterEmpty, of(raw = 12, shown = 0, complete = true, filtersActive = true))
    }

    @Test fun `photos held, no filter and none shown yet is the spinner (paging has not caught up)`() {
        assertEquals(GridView.Loading, of(raw = 12, shown = 0, complete = true, filtersActive = false))
    }

    @Test fun `photos and a later page that failed gets the partial banner with the known total`() {
        assertEquals(
            GridView.Photos(GridView.Banner.Partial("Showing 100 of 150 photos. SmugMug is having trouble.")),
            of(raw = 100, problem = trouble, expected = 150)
        )
    }

    @Test fun `the partial banner leaves the total out when it is unknown or not larger than what is held`() {
        val unknown = of(raw = 100, problem = trouble, expected = null)
        val same = of(raw = 100, problem = trouble, expected = 100)
        assertEquals(GridView.Photos(GridView.Banner.Partial("Showing 100 photos. SmugMug is having trouble.")), unknown)
        assertEquals(unknown, same)
    }

    @Test fun `the offline notice wins over the partial banner`() {
        assertEquals(
            GridView.Photos(GridView.Banner.Notice("You're offline. Showing the 7 photos saved on this phone.")),
            of(raw = 7, problem = offline, notice = "You're offline. Showing the 7 photos saved on this phone.")
        )
    }

    @Test fun `photos and a finished gallery have no banner`() {
        assertEquals(GridView.Photos(null), of(raw = 12, complete = true))
        assertEquals(GridView.Photos(null), of(raw = 12, loading = true))
    }
}
