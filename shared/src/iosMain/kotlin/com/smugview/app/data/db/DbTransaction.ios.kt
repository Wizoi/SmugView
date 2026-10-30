package com.smugview.app.data.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

actual suspend fun <R> AppDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
