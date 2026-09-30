package com.smugview.app.data.api

import okhttp3.OkHttpClient

/** A client for bulk file downloads. It shares the connection pool but not the HTTP cache. */
fun OkHttpClient.forFileDownloads(): OkHttpClient = newBuilder().cache(null).build()
