package com.pantheon.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pantheon.android.auth.AuthViewModel

// Same POST /api/auth/login username+password flow hades/src/auth/LoginPage.tsx
// uses — no profile-picker step yet (ProfileSelectPage.tsx's equivalent),
// that's real follow-up scope, not an oversight (see AuthViewModel's comment).
@Composable
fun LoginScreen(viewModel: AuthViewModel, onLoggedIn: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Sign in", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = viewModel.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            OutlinedTextField(
                value = viewModel.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            viewModel.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = { viewModel.login(onSuccess = onLoggedIn) },
                enabled = !viewModel.isLoading,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                if (viewModel.isLoading) CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                else Text("Sign in")
            }
        }
    }
}
