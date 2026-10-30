package com.smugview.app.ui

import androidx.compose.runtime.Composable

/** Intercepts the system back gesture while [enabled]. Android forwards to the activity's dispatcher; the other targets have no system back. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
