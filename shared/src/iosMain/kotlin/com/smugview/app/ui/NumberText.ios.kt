package com.smugview.app.ui

import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle

actual fun formatCount(value: Long): String =
    NSNumberFormatter().apply { numberStyle = NSNumberFormatterDecimalStyle; maximumFractionDigits = 0u }
        .stringFromNumber(NSNumber(long = value)) ?: value.toString()
