package com.smugview.app.ui

import android.content.Context
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android's [DominantColorSampler]: loads the picture through Coil and asks Palette for its dominant colour. */
class PaletteDominantColorSampler(private val context: Context) : DominantColorSampler {
    override suspend fun sample(imageUrl: String, default: Int): Int? = withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false) // required for pixel reading
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        if (result is SuccessResult) Palette.from(result.image.toBitmap()).generate().getDominantColor(default) else null
    }
}
