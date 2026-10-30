package com.smugview.app.share

/** SmugMug answered, but not with the picture: kept apart from an `IOException` so a 429 or 5xx is never shown as "offline". */
class HttpFailure(val code: Int) : Exception("HTTP $code")

class NotAPictureException : Exception("no picture to share")
