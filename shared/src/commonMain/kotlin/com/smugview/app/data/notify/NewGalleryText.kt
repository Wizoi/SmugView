package com.smugview.app.data.notify

/** What the notification says. Specific on purpose: which site and which galleries, never a generic "something changed". */
object NewGalleryText {
    private const val NAMES_SHOWN = 3

    fun followed(site: String) = "Following $site. You will get a notification when it has a new gallery."
    fun unfollowed(site: String) = "No longer following $site."
    const val PERMISSION_DENIED =
        "Notifications are turned off for SmugView, so there is nothing to notify you with. Turn them on in Android settings, then try again."
    const val FOLLOW_DESCRIPTION = "Notify me of new galleries"
    const val UNFOLLOW_DESCRIPTION = "Stop new gallery notifications"

    fun title(site: String, count: Int): String =
        if (count == 1) "New gallery on $site" else "$count new galleries on $site"

    fun body(names: List<String>): String {
        val shown = names.take(NAMES_SHOWN).joinToString(", ")
        val more = names.size - NAMES_SHOWN
        return if (more > 0) "$shown and $more more" else shown
    }
}
