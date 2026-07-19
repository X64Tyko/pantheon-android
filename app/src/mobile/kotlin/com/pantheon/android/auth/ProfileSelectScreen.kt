package com.pantheon.android.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pantheon.android.api.dto.AuthUser

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)
private val AdminColor = Color(0xFF7C6BD6)
private val ViewerColor = Color(0xFF3A7CA5)

// Mobile counterpart of hades/src/auth/ProfileSelectPage.tsx — same
// pick()/switchProfile/PIN/password-fallback logic, touch grid instead of a
// desktop button grid. Reached on every cold launch that already has a
// stored session (real feedback item 1: "no way to switch users" — there
// was no picker at all before this), and again on demand via a "Switch
// Profile" entry point elsewhere in the app (Home's own top bar).
@Composable
fun ProfileSelectScreen(viewModel: AuthViewModel, onProfileChosen: () -> Unit, onSignOutCompletely: () -> Unit) {
    var pinFor by remember { mutableStateOf<AuthUser?>(null) }
    var passwordFor by remember { mutableStateOf<AuthUser?>(null) }
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // Nothing to pick when there's only one profile (or the fetch itself
    // failed) — same as AuthContext.tsx's profileChosen treating
    // profiles.length<=1 as already-satisfied, so a single-admin household
    // never sees an empty-feeling one-tile picker.
    LaunchedEffect(Unit) { viewModel.loadProfiles(onLoaded = { if (viewModel.profiles.size <= 1) onProfileChosen() }) }

    fun pick(user: AuthUser, enteredPin: String? = null) {
        viewModel.clearPickerError()
        if (viewModel.isCurrentProfile(user)) {
            viewModel.confirmCurrentProfile(onProfileChosen)
            return
        }
        if (user.role == "admin" && !user.hasPin) {
            password = ""
            passwordFor = user
            return
        }
        if (user.hasPin && enteredPin == null) {
            pin = ""
            pinFor = user
            return
        }
        viewModel.switchProfile(user, enteredPin, onProfileChosen)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Text("Who's watching?", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(bottom = 24.dp))

            when {
                pinFor != null -> {
                    val user = pinFor!!
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        ProfileAvatar(user, size = 72.dp)
                        Text(user.username, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text("····") },
                            singleLine = true,
                            modifier = Modifier.padding(top = 20.dp).size(width = 160.dp, height = 56.dp),
                        )
                        viewModel.pickerError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                        Button(
                            onClick = { pick(user, pin) },
                            enabled = pin.length in 4..6 && !viewModel.pickerBusy,
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            if (viewModel.pickerBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Confirm")
                        }
                        TextButton(onClick = { pinFor = null; viewModel.clearPickerError() }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("← back", color = TextDim)
                        }
                    }
                }
                passwordFor != null -> {
                    val user = passwordFor!!
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        ProfileAvatar(user, size = 72.dp)
                        Text(user.username, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        Text(
                            "Admin profiles need a PIN before they can be switched into — sign in with the password instead.",
                            color = TextDim,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            placeholder = { Text("Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        )
                        viewModel.pickerError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                        Button(
                            onClick = { viewModel.loginAsProfile(user.username, password, onProfileChosen) },
                            enabled = password.isNotBlank() && !viewModel.pickerBusy,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        ) {
                            if (viewModel.pickerBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Sign in")
                        }
                        TextButton(onClick = { passwordFor = null; password = ""; viewModel.clearPickerError() }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("← back", color = TextDim)
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        items(viewModel.profiles, key = { it.userId }) { user ->
                            ProfileTile(user = user, isYou = viewModel.isCurrentProfile(user), busy = viewModel.pickerBusy, onClick = { pick(user) })
                        }
                    }
                    viewModel.pickerError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    TextButton(onClick = onSignOutCompletely, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
                        Text("Sign out completely", color = TextDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatar(user: AuthUser, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(if (user.role == "admin") AdminColor else ViewerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(user.username.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun ProfileTile(user: AuthUser, isYou: Boolean, busy: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick),
    ) {
        Box {
            ProfileAvatar(user, size = 72.dp)
            if (user.hasPin) {
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).size(22.dp).background(BgColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("🔒", style = MaterialTheme.typography.labelSmall) }
            }
        }
        Text(user.username, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        if (isYou) Text("(you)", color = TextDim, style = MaterialTheme.typography.labelSmall)
        if (user.restricted) {
            Text(
                "RESTRICTED",
                color = GoldColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp).background(Color(0x33E0B84E), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}
