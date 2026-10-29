package com.smugview.shared.api

import java.io.File

actual fun readFixture(name: String): String = File("../app/src/test/resources/parity/$name").readText()
