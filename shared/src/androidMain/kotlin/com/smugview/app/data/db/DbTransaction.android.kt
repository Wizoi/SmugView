package com.smugview.app.data.db

import androidx.room.withTransaction

actual suspend fun <R> AppDatabase.inTransaction(block: suspend () -> R): R = withTransaction(block)
