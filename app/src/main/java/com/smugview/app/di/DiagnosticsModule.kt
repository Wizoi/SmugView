package com.smugview.app.di

import android.content.Context
import android.util.Log
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.DoctorDao
import com.smugview.app.BuildConfig
import com.smugview.app.data.security.PasswordStore
import com.smugview.app.diag.CacheDoctor
import com.smugview.app.diag.DiagLog
import com.smugview.app.diag.DiagnosticsFileWriter
import com.smugview.app.diag.FileLogSink
import com.smugview.app.diag.HttpTelemetry
import com.smugview.app.diag.Level
import com.smugview.app.diag.FileSyncReporter
import com.smugview.app.diag.Redactor
import com.smugview.app.diag.SyncReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DiagScope

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {

    @Provides
    @Singleton
    fun provideRedactor(): Redactor = Redactor()

    @Provides
    @Singleton
    fun provideDiagLog(@ApplicationContext context: Context, redactor: Redactor): DiagLog =
        DiagLog(
            sink = FileLogSink(File(context.filesDir, "diagnostics")),
            redactor = redactor,
            // Redacted W/E lines are mirrored to logcat in every build (owner decision, design §0 Q3).
            logcat = { level, cat, msg ->
                Log.println(if (level == Level.E) Log.ERROR else Log.WARN, cat, msg)
            }
        )

    @Provides
    @Singleton
    fun provideHttpTelemetry(log: DiagLog): HttpTelemetry = HttpTelemetry(log)

    @Provides
    @Singleton
    @DiagScope
    fun provideDiagScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideDoctorDao(database: AppDatabase): DoctorDao = database.doctorDao()

    @Provides
    @Singleton
    fun provideSyncReporter(
        @ApplicationContext context: Context,
        log: DiagLog,
        doctorDao: DoctorDao,
        collectionDao: CollectionDao,
        @DiagScope scope: CoroutineScope,
        writer: dagger.Lazy<DiagnosticsFileWriter>
    ): SyncReporter = FileSyncReporter(
        file = File(File(context.filesDir, "diagnostics"), "sync_reports.jsonl"),
        log = log,
        scope = scope,
        // After a gallery sync: how many albums the index calls recent vs how many nodes light the
        // dot. A gap between the two is findings #1/#4, measured.
        postSync = { run ->
            run.newInIndex30d = doctorDao.countRecentIndexAlbums(run.nickname)
            run.litDotNodes = collectionDao.getNodesWithActiveUpdates().first().size
            // Then the doctor (first sync of the process only) and the on-device report.txt.
            writer.get().onSyncFinished()
        }
    )

    @Provides
    @Singleton
    fun provideCacheDoctor(doctorDao: DoctorDao, reporter: dagger.Lazy<SyncReporter>): CacheDoctor =
        CacheDoctor(doctorDao, syncRunning = { reporter.get().hasOpenRun() })

    /**
     * Writes `report.txt` into the app's external files dir for `adb pull` (design §0 Q2). Takes the
     * reporter directly; the reporter reaches back through a `Lazy` in [provideSyncReporter].
     */
    @Provides
    @Singleton
    fun provideDiagnosticsFileWriter(
        @ApplicationContext context: Context,
        log: DiagLog,
        redactor: Redactor,
        reporter: SyncReporter,
        passwordStore: PasswordStore,
        doctor: CacheDoctor,
        telemetry: HttpTelemetry
    ): DiagnosticsFileWriter = DiagnosticsFileWriter(
        dirProvider = { context.getExternalFilesDir("diagnostics") },
        log = log,
        redactor = redactor,
        reporter = reporter,
        secrets = { passwordStore.all().values + BuildConfig.SMUGMUG_API_KEY },
        doctor = doctor,
        header = {
            "app: v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}\n" +
                "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}\n" +
                "local time: ${java.time.ZonedDateTime.now()}\n" +
                "saved password keys: ${passwordStore.all().size}"
        },
        httpStats = { telemetry.stats() }
    )
}
