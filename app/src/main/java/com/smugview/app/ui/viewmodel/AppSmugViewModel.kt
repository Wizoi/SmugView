package com.smugview.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.smugview.app.BuildConfig
import com.smugview.app.data.cast.CastManager
import com.smugview.app.data.offline.OfflineCollections
import com.smugview.app.data.offline.OfflineReader
import com.smugview.app.data.prefs.SharedPrefsStore
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.security.PasswordStore
import com.smugview.app.diag.SyncReporter
import com.smugview.app.util.AndroidToaster
import com.smugview.app.util.KeyValueStore
import com.smugview.app.util.Toaster
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Inject

/** What the shared [SmugViewModel] needs from Android: the two preference files, a Toast, and a file's `Uri`. */
private fun prefsOf(application: Application, name: String): KeyValueStore =
    SharedPrefsStore(application.getSharedPreferences(name, Context.MODE_PRIVATE))

/**
 * Android's composition root for [SmugViewModel] (Hilt). The class itself lives in the shared module with a plain constructor; iOS builds
 * it its own way.
 */
@HiltViewModel
class AppSmugViewModel @Inject constructor(
    application: Application,
    repository: SmugMugRepository,
    offline: OfflineCollections,
    offlineReader: OfflineReader,
    castManager: CastManager,
    passwordStore: PasswordStore,
    defaultDispatcher: CoroutineDispatcher,
    syncReporter: SyncReporter,
    savedStateHandle: SavedStateHandle
) : SmugViewModel(
    apiKey = BuildConfig.SMUGMUG_API_KEY,
    sharedPrefs = prefsOf(application, "smugview_prefs").also { AndroidToaster.install(application) },
    searchStatusPrefs = prefsOf(application, "smugview_search_status"),
    showMessage = { message -> Toaster.show(message) },
    fileUri = { path -> Uri.fromFile(File(path)).toString() },
    repository = repository,
    offline = offline,
    offlineReader = offlineReader,
    castManager = castManager,
    passwordStore = passwordStore,
    defaultDispatcher = defaultDispatcher,
    syncReporter = syncReporter,
    savedStateHandle = savedStateHandle
)

/** The same wiring without Hilt, for tests and previews. */
fun smugViewModelFor(
    application: Application,
    repository: SmugMugRepository,
    offline: OfflineCollections,
    offlineReader: OfflineReader,
    castManager: CastManager,
    passwordStore: PasswordStore,
    defaultDispatcher: CoroutineDispatcher,
    syncReporter: SyncReporter = SyncReporter.NOOP,
    savedStateHandle: SavedStateHandle = SavedStateHandle()
): SmugViewModel = SmugViewModel(
    apiKey = BuildConfig.SMUGMUG_API_KEY,
    sharedPrefs = prefsOf(application, "smugview_prefs").also { AndroidToaster.install(application) },
    searchStatusPrefs = prefsOf(application, "smugview_search_status"),
    showMessage = { message -> Toaster.show(message) },
    fileUri = { path -> Uri.fromFile(File(path)).toString() },
    repository = repository,
    offline = offline,
    offlineReader = offlineReader,
    castManager = castManager,
    passwordStore = passwordStore,
    defaultDispatcher = defaultDispatcher,
    syncReporter = syncReporter,
    savedStateHandle = savedStateHandle
)
