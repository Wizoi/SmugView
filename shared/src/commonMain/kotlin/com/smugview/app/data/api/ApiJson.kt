package com.smugview.app.data.api

import kotlinx.serialization.json.Json

/**
 * How the app reads SmugMug's JSON. Unknown fields are ignored (the API keeps adding them) and a null for a field with a default takes the
 * default. A missing field with no default throws, where Gson used to leave a null in a non-null property (see `KotlinxModelParityTest`).
 */
val ApiJson: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
