package com.pantheon.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pantheon.android.ui.PantheonNavHost
import com.pantheon.android.ui.theme.PantheonTheme

// TV flavor's entry point — Android TV / Fire TV, D-pad only, Compose for TV.
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
