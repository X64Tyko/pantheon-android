package com.pantheon.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pantheon.android.ui.PantheonNavHost
import com.pantheon.android.ui.theme.PantheonTheme

// Mobile flavor's entry point — touch phone/tablet, plain Jetpack Compose.
// enableEdgeToEdge() is effectively already forced by targetSdk 37 (Android
// mandates edge-to-edge from API 35 on), so this call is about getting
// correct status-bar icon contrast rather than opting in — the actual safe-
// area padding for each screen's top toolbar/back button lives in that
// screen itself (statusBarsPadding()), not here, so hero/backdrop imagery
// can still bleed to the true top edge while buttons/text stay inset-safe.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PantheonApplication
        setContent {
            PantheonTheme {
                PantheonNavHost(tokenStore = app.tokenStore, apiClient = app.apiClient)
            }
        }
    }
}
