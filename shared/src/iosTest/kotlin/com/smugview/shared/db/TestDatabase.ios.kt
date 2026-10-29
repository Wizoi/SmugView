package com.smugview.shared.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.stringWithContentsOfFile

// Written without a Mac to compile it: the first macOS run is its test.
actual fun newInMemoryDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Unconfined)
        .build()

/** CI sets `SIMCTL_CHILD_SCHEMA_DIR`, which `simctl spawn` passes on as `SCHEMA_DIR`. */
@OptIn(ExperimentalForeignApi::class)
actual fun readSchema(version: Int): String {
    val dir = NSProcessInfo.processInfo.environment["SCHEMA_DIR"] as? String ?: error("SCHEMA_DIR is not set")
    return NSString.stringWithContentsOfFile("$dir/$version.json", NSUTF8StringEncoding, null) ?: error("cannot read $dir/$version.json")
}

actual fun tempDbPath(): String = NSTemporaryDirectory() + "smugview-migration-" + NSUUID().UUIDString + ".db"

actual fun openDatabaseAt(path: String): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(*Migrations.all)
        .setQueryCoroutineContext(Dispatchers.Unconfined)
        .build()
