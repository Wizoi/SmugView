package com.smugview.app.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.smugview.app.data.cast.CAST_EXPLANATION
import com.smugview.app.data.cast.CastExplainer

/** Null everywhere except where a platform provides one (iOS). With null, [CastStartGate.request] starts at once: Android is unchanged. */
val LocalCastExplainer = staticCompositionLocalOf<CastExplainer?> { null }

/**
 * Sits between a cast button and "start discovery, open the sheet". Without an explainer, or once it was accepted, [request] runs the
 * start at once. Otherwise it holds the start, shows the explanation, and runs it only on Continue ("Not now" drops it, no scan).
 */
class CastStartGate(private val explainer: CastExplainer?) {
    var showing by mutableStateOf(false)
        private set
    private var pending: (() -> Unit)? = null

    fun request(start: () -> Unit) {
        if (explainer != null && explainer.shouldExplain()) {
            pending = start
            showing = true
        } else {
            start()
        }
    }

    fun onContinue() {
        val start = pending
        pending = null
        showing = false
        explainer?.markExplained()
        start?.invoke()
    }

    fun onNotNow() {
        pending = null
        showing = false
    }
}

/** The gate for this screen; call [CastStartGate.request] from the cast button. It draws its own dialog. */
@Composable
fun rememberCastStartGate(): CastStartGate {
    val explainer = LocalCastExplainer.current
    val gate = remember(explainer) { CastStartGate(explainer) }
    if (gate.showing) {
        AlertDialog(
            onDismissRequest = gate::onNotNow,
            containerColor = Color(0xFF121418),
            title = { Text("Find cast devices", color = Color.White) },
            text = { Text(CAST_EXPLANATION, color = Color.White.copy(alpha = 0.8f)) },
            confirmButton = { TextButton(onClick = gate::onContinue) { Text("Continue", color = Color(0xFF00E5FF)) } },
            dismissButton = { TextButton(onClick = gate::onNotNow) { Text("Not now", color = Color.White.copy(alpha = 0.7f)) } }
        )
    }
    return gate
}
