package com.smugview.app.data.db

/** The clock for entity defaults: `System.currentTimeMillis()` on the JVM, `NSDate` on iOS. */
expect fun nowMillis(): Long
