package com.smugview.app.data.offline

import java.nio.file.Files

actual fun newTempDir(): String = Files.createTempDirectory("smugview-fileops").toFile().also { it.deleteOnExit() }.path.replace("\\", "/")
