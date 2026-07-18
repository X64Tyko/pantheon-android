package com.pantheon.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pantheon.android.ui.PantheonNavHost
import com.pantheon.android.ui.theme.PantheonTheme

// Mobile flavor's entry point — touch phone/tablet, plain Jetpack Compose.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PantheonApplication
        setContent {
            PantheonTheme {
                PantheonNavHost(tokenStore = app.tokenStore, apiClient = app.apiClient)
            }
        }
    }
}
