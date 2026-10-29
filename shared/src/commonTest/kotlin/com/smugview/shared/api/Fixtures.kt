package com.smugview.shared.api

/** The text of a recorded real answer (`app/src/test/resources/parity/<name>`). Each platform reads it its own way. */
expect fun readFixture(name: String): String
