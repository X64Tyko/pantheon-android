package com.pantheon.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pantheon.android.auth.AuthViewModel

// Direct-connection entry point — a plain Hermes URL (e.g.
// "http://192.168.1.50:8000"), no self-hosted/managed relay modes yet
// (project plan §7). Jellyfin-style "just type the address," not buried
// behind an "advanced" toggle.
@Composable
fun ConnectScreen(viewModel: AuthViewModel, onConnected: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Connect to Pantheon", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Enter your server's address",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
            OutlinedTextField(
                value = viewModel.serverUrlInput,
                onValueChange = viewModel::onServerUrlChange,
                label = { Text("Server address") },
                placeholder = { Text("192.168.1.50:8000") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            viewModel.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = { if (viewModel.confirmServerUrl()) onConnected() },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Continue") }
        }
    }
}
