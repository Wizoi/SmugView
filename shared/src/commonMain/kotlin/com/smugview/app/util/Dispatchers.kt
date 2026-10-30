package com.smugview.app.util

import kotlinx.coroutines.CoroutineDispatcher

/** The dispatcher for blocking work (database, file, network reads): `Dispatchers.IO` where it exists. */
expect val ioDispatcher: CoroutineDispatcher
