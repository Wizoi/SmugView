package com.smugview.app

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color

/**
 * Draw behind the system bars on every Android version (Android 15 forces it at targetSdk 35+, and Play flagged the app for
 * not handling it). Every screen paints [DeepDarkBackground], in the light system theme too, so the bars stay transparent and
 * keep light icons. The scrim is transparent too: the app is dark under the 3-button bar as well.
 */
internal fun ComponentActivity.enableDarkEdgeToEdge() {
    val dark = SystemBarStyle.dark(Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
}
