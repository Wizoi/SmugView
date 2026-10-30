package com.smugview.app.util

import java.io.InterruptedIOException
import java.net.SocketTimeoutException

actual fun isTimeoutError(error: Throwable): Boolean = error is SocketTimeoutException || error is InterruptedIOException
