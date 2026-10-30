package com.smugview.app.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Room 2.8's `MigrationTestHelper` opens the file by its full path, but its driver wrapper insists the helper's database NAME equals
 * what is requested, so a helper named `migration-test` is refused. This factory names the helper by the full path of that database,
 * which the framework helper accepts, so the app's migration and repair tests keep their `createDatabase("name", version)` shape.
 */
class PathNamedOpenHelperFactory(private val context: Context) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val name = configuration.name?.let { context.getDatabasePath(it).path }
        val renamed = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(name).callback(configuration.callback)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(renamed)
    }
}
