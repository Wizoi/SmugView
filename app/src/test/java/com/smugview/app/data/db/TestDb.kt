package com.smugview.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

/** Shared in-memory Room database for Robolectric tests (real SQLite, real schema). */
object TestDb {
    fun inMemory(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppDatabase::class.java
    ).allowMainThreadQueries().build()
}
