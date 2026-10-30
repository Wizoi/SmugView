package com.smugview.app.data.db

/**
 * Runs [block] (DAO calls and other work) in one database transaction that rolls back if it throws. `withTransaction` is an Android-only
 * extension; on the other platforms this uses Room's writer connection.
 */
expect suspend fun <R> AppDatabase.inTransaction(block: suspend () -> R): R
