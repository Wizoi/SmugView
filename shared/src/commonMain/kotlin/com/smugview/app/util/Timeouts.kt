package com.smugview.app.util

/** True when [error] is a network timeout (a slow connection, not a missing one): the screens say "slow", not "offline". */
expect fun isTimeoutError(error: Throwable): Boolean
