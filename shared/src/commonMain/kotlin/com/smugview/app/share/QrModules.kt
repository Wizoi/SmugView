package com.smugview.app.share

import qrcode.QRCode
import qrcode.raw.ErrorCorrectionLevel

/**
 * The dark/light modules of a QR code for [text] (error correction M, no quiet zone), or null when [text] is blank or cannot be
 * encoded. Row-major: `modules[row][column]` is true for a dark module. The screen draws it, so no bitmap is needed.
 */
fun qrModules(text: String): Array<BooleanArray>? {
    if (text.isBlank()) return null
    return try {
        val raw = QRCode(text, errorCorrectionLevel = ErrorCorrectionLevel.MEDIUM).rawData
        Array(raw.size) { row -> BooleanArray(raw[row].size) { col -> raw[row][col].dark } }
    } catch (e: Exception) {
        null
    }
}
