package com.smugview.app.ui

import java.text.NumberFormat

actual fun formatCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)
