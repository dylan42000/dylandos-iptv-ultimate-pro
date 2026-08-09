package com.dylandos.iptv.ultimate.ui.screens.login

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.theme.*

/**
 * DYLANDOS IPTV ULTIMATE — Login Screen
 *
 * Supports:
 *   Tab 1 — Xtream Codes (server URL + username + password)
 *   Tab 2 — M3U playlist URL
 *
 * D-pad navigation:
 *   Each input field chains to the next via FocusRequester.
 *   Tab toggle buttons are D-pad selectable.
 *   Connect button is last in the focus chain.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(
    navController: NavController,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-navigate to Home if already logged in
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) {
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    // FocusRequesters for D-pad field chain
    val frXtreamTab  = remember { FocusRequester() }
    val frM3uTab     = remember { FocusRequester() }
    val frServer     = remember { FocusRequester() }
    val frUsername   = remember { FocusRequester() }
    val frPassword   = remember { FocusRequester() }
    val frM3uUrl     = remember { FocusRequester() }
    val frConnect    = remember { FocusRequester() }

    // Request initial focus on the first tab button
    LaunchedEffect(Unit) {
        frXtreamTab.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(BgSurface2, BgBase, BgBase))
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── Logo / Title ───────────────────────────────────────────────
            Text(
                text = "DYLANDOS",
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = Accent,
                letterSpacing = 4.sp
            )
            Text(
                text = "IPTV ULTIMATE",
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                color = TextSecondary,
                letterSpacing = 6.sp
            )

            Spacer(Modifier.height(32.dp))

            // ── Mode Tabs ─────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TabButton(
                    text = "Xtream / Extreme",
                    selected = state.mode == LoginMode.XTREAM,
                    focusRequester = frXtreamTab,
                    nextFocus = frM3uTab,
                    downFocus = frServer,
                    onClick = {
                        viewModel.setMode(LoginMode.XTREAM)
                        frServer.requestFocus()
                    },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    text = "M3U Playlist",
                    selected = state.mode == LoginMode.M3U,
                    focusRequester = frM3uTab,
                    nextFocus = if (state.mode == LoginMode.M3U) frM3uUrl else frServer,
                    downFocus = if (state.mode == LoginMode.M3U) frM3uUrl else frServer,
                    onClick = {
                        viewModel.setMode(LoginMode.M3U)
                        frM3uUrl.requestFocus()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Form Fields ───────────────────────────────────────────────
            AnimatedContent(
                targetState = state.mode,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "loginForm"
            ) { mode ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (mode) {
                        LoginMode.XTREAM -> {
                            TvTextField(
                                value = state.serverUrl,
                                onValueChange = viewModel::setServerUrl,
                                label = "Server URL",
                                placeholder = "http://yourserver.com:8080",
                                focusRequester = frServer,
                                prevFocusRequester = frXtreamTab,
                                nextFocusRequester = frUsername,
                                imeAction = ImeAction.Next,
                                keyboardType = KeyboardType.Uri
                            )
                            TvTextField(
                                value = state.username,
                                onValueChange = viewModel::setUsername,
                                label = "Username",
                                placeholder = "your_username",
                                focusRequester = frUsername,
                                prevFocusRequester = frServer,
                                nextFocusRequester = frPassword,
                                imeAction = ImeAction.Next
                            )
                            TvTextField(
                                value = state.password,
                                onValueChange = viewModel::setPassword,
                                label = "Password",
                                placeholder = "••••••••",
                                focusRequester = frPassword,
                                prevFocusRequester = frUsername,
                                nextFocusRequester = frConnect,
                                imeAction = ImeAction.Done,
                                isPassword = true,
                                onDone = {
                                    keyboardController?.hide()
                                    frConnect.requestFocus()
                                }
                            )
                        }
                        LoginMode.M3U -> {
                            TvTextField(
                                value = state.m3uUrl,
                                onValueChange = viewModel::setM3uUrl,
                                label = "M3U Playlist URL",
                                placeholder = "http://yourserver.com/playlist.m3u",
                                focusRequester = frM3uUrl,
                                prevFocusRequester = frM3uTab,
                                nextFocusRequester = frConnect,
                                imeAction = ImeAction.Done,
                                keyboardType = KeyboardType.Uri,
                                onDone = {
                                    keyboardController?.hide()
                                    frConnect.requestFocus()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Error message ─────────────────────────────────────────────
            AnimatedVisibility(visible = state.error != null) {
                state.error?.let { err ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x33FF4444), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, "Error", tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                        Text(err, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Connect Button ────────────────────────────────────────────
            ConnectButton(
                isLoading = state.isLoading,
                focusRequester = frConnect,                upFocus = if (state.mode == LoginMode.XTREAM) frPassword else frM3uUrl,                onClick = {
                    keyboardController?.hide()
                    if (state.mode == LoginMode.XTREAM) viewModel.connectXtream()
                    else viewModel.connectM3U()
                }
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "DYLANDOS IPTV ULTIMATE v1.5.0",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Reusable components ────────────────────────────────────────────────────────

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    nextFocus: FocusRequester,
    downFocus: FocusRequester? = null,   // D-pad DOWN destination
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Button(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .then(
                if (isFocused) Modifier.border(2.dp, AccentBright, RoundedCornerShape(8.dp))
                else Modifier
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> { nextFocus.requestFocus(); true }
                    Key.DirectionDown  -> { downFocus?.requestFocus(); downFocus != null }
                    else -> false
                }
            },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Accent else Color.Transparent,
            contentColor = if (selected) Color.Black else TextSecondary
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
    }
}

@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    focusRequester: FocusRequester,
    nextFocusRequester: FocusRequester? = null,
    prevFocusRequester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Next,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    onDone: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = TextSecondary.copy(alpha = 0.4f)) },
        visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation()
                               else VisualTransformation.None,
        trailingIcon = if (isPassword) ({
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showPassword) "Hide" else "Show",
                    tint = if (isFocused) Accent else TextSecondary
                )
            }
        }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(
            onNext = { nextFocusRequester?.requestFocus() ?: focusManager.moveFocus(FocusDirection.Down) },
            onDone = { onDone?.invoke() ?: focusManager.clearFocus() }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = BorderDefault,
            focusedLabelColor = Accent,
            unfocusedLabelColor = TextSecondary,
            cursorColor = Accent,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            // D-pad UP/DOWN navigate between fields on Firestick
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        nextFocusRequester?.requestFocus()
                            ?: focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    Key.DirectionUp -> {
                        prevFocusRequester?.requestFocus()
                            ?: focusManager.moveFocus(FocusDirection.Up)
                        true
                    }
                    else -> false
                }
            }
            .then(
                if (isFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(4.dp))
                else Modifier
            )
    )
}

@Composable
private fun ConnectButton(
    isLoading: Boolean,
    focusRequester: FocusRequester,
    upFocus: FocusRequester? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Button(
        onClick = onClick,
        enabled = !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .then(
                if (isFocused) Modifier.border(3.dp, AccentBright, RoundedCornerShape(12.dp))
                else Modifier
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { upFocus?.requestFocus(); upFocus != null }
                    else -> false
                }
            },
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = Color.Black,
            disabledContainerColor = Accent.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.Black,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(12.dp))
            Text("Connecting...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Connect", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
