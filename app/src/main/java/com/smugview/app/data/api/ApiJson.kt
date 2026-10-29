package com.smugview.app.data.api

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Converter

/**
 * How the app reads SmugMug's JSON. Unknown fields are ignored (the API keeps adding them) and a null for a field with a default takes the
 * default. A missing field with no default throws, where Gson used to leave a null in a non-null property (see `KotlinxModelParityTest`).
 */
val ApiJson: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun apiConverterFactory(): Converter.Factory = ApiJson.asConverterFactory("application/json".toMediaType())
