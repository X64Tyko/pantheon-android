package com.pantheon.android.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.pantheon.android.api.dto.AuthUser
import com.pantheon.android.library.BgColor
import com.pantheon.android.library.GoldColor
import com.pantheon.android.library.TextDim
import com.pantheon.android.library.TvTextButton

private val AdminColor = Color(0xFF7C6BD6)
private val ViewerColor = Color(0xFF3A7CA5)

// TV counterpart of the mobile flavor's ProfileSelectScreen.kt — same
// AuthViewModel picker state/logic (pick/PIN/password-fallback), D-pad-
// focusable androidx.tv.material3 Surfaces instead of touch tiles. A plain
// LazyRow rather than a grid — tv-foundation's LazyRow/LazyColumn can't
// auto-scroll to reveal an off-screen focus target (see DetailScreen's own
// Play-button bug from earlier in this project), and profile counts are
// small enough a single scrollable row never needs that.
@Composable
fun ProfileSelectScreen(viewModel: AuthViewModel, onProfileChosen: () -> Unit, onSignOutCompletely: () -> Unit) {
    var pinFor by remember { mutableStateOf<AuthUser?>(null) }
    var passwordFor by remember { mutableStateOf<AuthUser?>(null) }
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // See the mobile flavor's own comment — nothing to pick when there's
    // only one profile (or the fetch itself failed).
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

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        Column(modifier = Modifier.fillMaxSize().padding(40.dp)) {
            Text("Who's watching?", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(bottom = 32.dp))

            when {
                pinFor != null -> {
                    val user = pinFor!!
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        TvProfileAvatar(user, size = 96.dp)
                        Text(user.username, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { androidx.compose.material3.Text("····") },
                            singleLine = true,
                            modifier = Modifier.padding(top = 24.dp).size(width = 200.dp, height = 60.dp),
                        )
                        viewModel.pickerError?.let { Text(it, color = Color(0xFFE05A5A), modifier = Modifier.padding(top = 8.dp)) }
                        TvTextButton(text = if (viewModel.pickerBusy) "…" else "Confirm", onClick = { pick(user, pin) })
                        TvTextButton(text = "← back", onClick = { pinFor = null; viewModel.clearPickerError() })
                    }
                }
                passwordFor != null -> {
                    val user = passwordFor!!
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        TvProfileAvatar(user, size = 96.dp)
                        Text(user.username, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        Text(
                            "Admin profiles need a PIN before they can be switched into — sign in with the password instead.",
                            color = TextDim,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            placeholder = { androidx.compose.material3.Text("Password") },
                            singleLine = true,
                            modifier = Modifier.padding(top = 24.dp).size(width = 320.dp, height = 60.dp),
                        )
                        viewModel.pickerError?.let { Text(it, color = Color(0xFFE05A5A), modifier = Modifier.padding(top = 8.dp)) }
                        TvTextButton(text = if (viewModel.pickerBusy) "…" else "Sign in", onClick = { viewModel.loginAsProfile(user.username, password, onProfileChosen) })
                        TvTextButton(text = "← back", onClick = { passwordFor = null; password = ""; viewModel.clearPickerError() })
                    }
                }
                else -> {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(viewModel.profiles, key = { it.userId }) { user ->
                            TvProfileTile(user = user, isYou = viewModel.isCurrentProfile(user), onClick = { pick(user) })
                        }
                    }
                    viewModel.pickerError?.let { Text(it, color = Color(0xFFE05A5A), modifier = Modifier.padding(top = 16.dp)) }
                    TvTextButton(text = "Sign out completely", onClick = onSignOutCompletely)
                }
            }
        }
    }
}

@Composable
private fun TvProfileAvatar(user: AuthUser, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(if (user.role == "admin") AdminColor else ViewerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(user.username.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun TvProfileTile(user: AuthUser, isYou: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF2E2F45)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
            Box {
                TvProfileAvatar(user, size = 96.dp)
                if (user.hasPin) {
                    Box(
                        modifier = Modifier.align(Alignment.BottomEnd).size(26.dp).background(BgColor, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("🔒", style = MaterialTheme.typography.labelSmall) }
                }
            }
            Text(user.username, color = if (focused) GoldColor else Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            if (isYou) Text("(you)", color = TextDim, style = MaterialTheme.typography.labelSmall)
            if (user.restricted) Text("RESTRICTED", color = GoldColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
