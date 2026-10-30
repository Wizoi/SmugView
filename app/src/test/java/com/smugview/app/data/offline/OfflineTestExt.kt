package com.smugview.app.data.offline

import java.io.File

/** The store's paths are strings (the shared module has no `File`); the tests that read and write real files wrap them. */
val OfflineStore.Write.partFile: File get() = File(part)
