package com.smugview.app.diag

import com.smugview.app.util.AtomicCounter

/** Exceptions that reached a site session's handler (6-6): a job nobody caught. Counted in `report.txt`, never shown. */
object SiteErrors {
    val uncaught = AtomicCounter(0)
}
