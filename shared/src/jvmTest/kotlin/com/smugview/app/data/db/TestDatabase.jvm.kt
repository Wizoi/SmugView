package com.smugview.app.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

actual fun newInMemoryDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Unconfined)
        .build()

actual fun readSchema(version: Int): String = File("schemas/com.smugview.app.data.db.AppDatabase/$version.json").readText()

actual fun tempDbPath(): String = File.createTempFile("smugview-migration", ".db").also { it.delete(); it.deleteOnExit() }.path

actual fun openDatabaseAt(path: String): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(*Migrations.all)
        .setQueryCoroutineContext(Dispatchers.Unconfined)
        .build()
