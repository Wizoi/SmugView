package com.smugview.app.share

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The shared encoder replaced ZXing's writer; ZXing's reader, which stays here as a test dependency, must read what it draws. */
class QrModulesTest {
    private fun decode(modules: Array<BooleanArray>): String {
        val scale = 8
        val quiet = 4
        val side = (modules.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { i ->
            val row = i / side / scale - quiet
            val col = i % side / scale - quiet
            val dark = row in modules.indices && col in modules[row].indices && modules[row][col]
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test fun `a gallery link reads back as the same address`() {
        val url = "https://site.smugmug.com/Family/Holidays/Summer-2025/n-AbC123/i-XyZ9876"
        val modules = qrModules(url)
        assertNotNull(modules)
        assertEquals("square", modules!!.size, modules[0].size)
        assertEquals(url, decode(modules))
    }

    @Test fun `a long address with a query string reads back`() {
        val url = "https://kidzi.smugmug.com/Family/School/2025-26/Concert-Night/i-Qw3rTy?utm_source=share&lang=en#frag"
        assertEquals(url, decode(qrModules(url)!!))
    }

    @Test fun `a blank address has no code`() {
        assertNull(qrModules(""))
        assertNull(qrModules("   "))
    }
}
