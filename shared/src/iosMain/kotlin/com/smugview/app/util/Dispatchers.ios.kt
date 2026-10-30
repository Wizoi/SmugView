package com.smugview.app.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// On Kotlin/Native the member is internal; the public one is the extension `kotlinx.coroutines.IO`.
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
