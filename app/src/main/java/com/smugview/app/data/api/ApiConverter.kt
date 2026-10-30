package com.smugview.app.data.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Converter
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Retrofit's reader for [ApiJson] (the JSON setting itself lives in the shared module). */
fun apiConverterFactory(): Converter.Factory = ApiJson.asConverterFactory("application/json".toMediaType())
