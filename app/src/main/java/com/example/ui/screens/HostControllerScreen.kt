package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.ResolutionCeiling
import com.example.model.ShizukuStatus
import com.example.protocol.TouchSample
import com.example.ui.WorkstationUiState
import com.example.ui.components.ShizukuStatusCard
import com.example.ui.components.TacticalCard
import com.example.ui.components.TelemetryChip
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HostControllerScreen(
    state: WorkstationUiState,
    onTrackpadDelta: (Float, Float, Byte) -> Unit,
    onImeInput: (String?, Int) -> Unit,
    onUpdateDisplay: (ResolutionCeiling, Boolean, Int, Boolean) -> Unit,
    onLaunchApp: (String) -> Unit,
    onMoveAppBackToHost: (String) -> Unit = {},
    onFocusDesktopShell: () -> Unit = {},
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onDisconnect: () -> Unit
) {
    val telemetry = state.hostTelemetry
    val cfg = telemetry.config
    var selectedSubTab by remember { mutableIntStateOf(0) } // 0 = Touchpad & IME, 1 = Display, 2 = Apps
    var imeBuffer by remember { mutableStateOf("") }

    Scaffold(
        containerColor = ObsidianBg,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = telemetry.peerName.ifBlank { "Active Workstation" },
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        val spec = if (cfg != null) {
                            "${cfg.targetWidth}x${cfg.targetHeight} • ${cfg.targetDpi} dpi • ${telemetry.fps} FPS • ${telemetry.kbps} kbps"
                        } else {
                            telemetry.statusNote
                        }
                        Text(
                            text = spec,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanPrimary
                        )
                        Text(
                            text = "Input: ${state.inputBackendStatus.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.testTag("host_input_backend_label")
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onFocusDesktopShell,
                        modifier = Modifier.testTag("focus_desktop_shell_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "Focus Desktop Shell on Display 1",
                            tint = CyanPrimary
                        )
                    }
                    IconButton(
                        onClick = onDisconnect,
                        modifier = Modifier.testTag("host_disconnect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Disconnect extended display",
                            tint = CrimsonError
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ObsidianBg)
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val isWide = maxWidth >= 720.dp

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (telemetry.sessionOnlyTrustMode) {
                    TacticalCard(
                        borderColor = AmberWarning.copy(alpha = 0.7f),
                        modifier = Modifier.testTag("host_controller_degraded_banner")
                    ) {
                        Text(
                            text = com.example.display.DisplaySessionEngine.SESSION_ONLY_WARNING,
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarning
                        )
                    }
                }

                val advisory = telemetry.advisory
                if (advisory.showCloseAppWarning) {
                    TacticalCard(
                        borderColor = AmberWarning.copy(alpha = 0.7f),
                        modifier = Modifier.testTag("performance_advisor_warning_card")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Close one app for smoother touch",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AmberWarning
                                )
                                Text(
                                    text = advisory.notificationLine2(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            if (!isWide) {
                                OutlinedButton(
                                    onClick = { selectedSubTab = 2 },
                                    modifier = Modifier.testTag("advisor_open_apps_button")
                                ) {
                                    Text("Apps")
                                }
                            }
                        }
                    }
                }

                if (state.shizukuStatus is ShizukuStatus.RecoveredToPreviewAfterBinderLoss) {
                    ShizukuStatusCard(
                        status = state.shizukuStatus,
                        onRequestPermission = onRequestShizukuPermission,
                        onRefresh = onRefreshShizuku
                    )
                }

                if (isWide) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        TouchpadAndImePane(
                            imeBuffer = imeBuffer,
                            onImeChanged = { newText ->
                                val diff = when {
                                    newText.length > imeBuffer.length -> newText.substring(imeBuffer.length)
                                    newText.length < imeBuffer.length -> {
                                        onImeInput(null, android.view.KeyEvent.KEYCODE_DEL)
                                        ""
                                    }
                                    else -> ""
                                }
                                if (diff.isNotEmpty()) onImeInput(diff, 0)
                                imeBuffer = newText
                            },
                            onSendEnter = {
                                onImeInput("\n", android.view.KeyEvent.KEYCODE_ENTER)
                                imeBuffer = ""
                            },
                            onTrackpadDelta = onTrackpadDelta,
                            modifier = Modifier
                                .weight(1.1f)
                                .fillMaxHeight()
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            DisplayOverridesPanel(
                                state = state,
                                onUpdateDisplay = onUpdateDisplay
                            )
                            AppDrawerPanel(
                                state = state,
                                onLaunchApp = onLaunchApp,
                                onMoveAppBackToHost = onMoveAppBackToHost,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SubTabButton(
                            selected = selectedSubTab == 0,
                            label = "Trackpad & IME",
                            icon = Icons.Filled.Mouse,
                            onClick = { selectedSubTab = 0 },
                            modifier = Modifier.weight(1f).testTag("subtab_trackpad")
                        )
                        SubTabButton(
                            selected = selectedSubTab == 1,
                            label = "Display",
                            icon = Icons.Filled.Tune,
                            onClick = { selectedSubTab = 1 },
                            modifier = Modifier.weight(1f).testTag("subtab_display")
                        )
                        SubTabButton(
                            selected = selectedSubTab == 2,
                            label = "Apps",
                            icon = Icons.Filled.Apps,
                            onClick = { selectedSubTab = 2 },
                            modifier = Modifier.weight(1f).testTag("subtab_apps")
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedSubTab) {
                            0 -> TouchpadAndImePane(
                                imeBuffer = imeBuffer,
                                onImeChanged = { newText ->
                                    val diff = when {
                                        newText.length > imeBuffer.length -> newText.substring(imeBuffer.length)
                                        newText.length < imeBuffer.length -> {
                                            onImeInput(null, android.view.KeyEvent.KEYCODE_DEL)
                                            ""
                                        }
                                        else -> ""
                                    }
                                    if (diff.isNotEmpty()) onImeInput(diff, 0)
                                    imeBuffer = newText
                                },
                                onSendEnter = {
                                    onImeInput("\n", android.view.KeyEvent.KEYCODE_ENTER)
                                    imeBuffer = ""
                                },
                                onTrackpadDelta = onTrackpadDelta,
                                modifier = Modifier.fillMaxSize()
                            )
                            1 -> DisplayOverridesPanel(
                                state = state,
                                onUpdateDisplay = onUpdateDisplay
                            )
                            else -> AppDrawerPanel(
                                state = state,
                                onLaunchApp = onLaunchApp,
                                onMoveAppBackToHost = onMoveAppBackToHost,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchpadAndImePane(
    imeBuffer: String,
    onImeChanged: (String) -> Unit,
    onSendEnter: () -> Unit,
    onTrackpadDelta: (Float, Float, Byte) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Native Host Android Keyboard (IME) Router
        TacticalCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Keyboard,
                    contentDescription = "Host IME routing",
                    tint = CyanPrimary
                )
                OutlinedTextField(
                    value = imeBuffer,
                    onValueChange = onImeChanged,
                    placeholder = { Text("Type with Host keyboard into Display 1…") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("host_ime_input")
                )
                Button(
                    onClick = onSendEnter,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("host_ime_enter_button")
                ) {
                    Text("Enter")
                }
            }
        }

        // Precision Virtual Trackpad Surface
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("host_virtual_trackpad")
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTrackpadDelta(0f, 0f, TouchSample.ACTION_DOWN)
                            onTrackpadDelta(0f, 0f, TouchSample.ACTION_UP)
                        },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTrackpadDelta(0f, 0f, TouchSample.ACTION_RIGHT_CLICK)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dx = dragAmount.x / size.width.coerceAtLeast(1)
                        val dy = dragAmount.y / size.height.coerceAtLeast(1)
                        onTrackpadDelta(dx, dy, TouchSample.ACTION_MOVE)
                    }
                },
            shape = MaterialTheme.shapes.large,
            color = SlateElevated,
            border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.4f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.align(Alignment.TopStart)) {
                    TelemetryChip(label = "TRI-MODE TRACKPAD • DISPLAY 1", accentColor = CyanPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Drag to move cursor • Tap to click • Long-press right-click\nBluetooth mouse/keyboard pair directly to Host.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTrackpadDelta(0f, 0f, TouchSample.ACTION_DOWN)
                            onTrackpadDelta(0f, 0f, TouchSample.ACTION_UP)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("trackpad_left_click")
                    ) {
                        Text("Left Click")
                    }
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTrackpadDelta(0f, 0f, TouchSample.ACTION_RIGHT_CLICK)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("trackpad_right_click")
                    ) {
                        Text("Right Click")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplayOverridesPanel(
    state: WorkstationUiState,
    onUpdateDisplay: (ResolutionCeiling, Boolean, Int, Boolean) -> Unit
) {
    TacticalCard {
        Text(
            text = "Virtual Display Synthesis & Overrides",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ResolutionCeiling.entries.forEach { ceiling ->
                FilterChip(
                    selected = state.resolutionCeiling == ceiling,
                    onClick = {
                        onUpdateDisplay(
                            ceiling,
                            state.forceTabletLayout,
                            state.manualDpi,
                            state.prefer24GhzBitrate
                        )
                    },
                    label = { Text(ceiling.label) },
                    modifier = Modifier.testTag("ceiling_chip_${ceiling.name}")
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Force Tablet / Desktop Layout", color = TextPrimary)
                Text(
                    "Triggers sw600dp+ tablet multi-pane UIs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            Switch(
                checked = state.forceTabletLayout,
                onCheckedChange = { checked ->
                    onUpdateDisplay(
                        state.resolutionCeiling,
                        checked,
                        if (checked) 160 else 280,
                        state.prefer24GhzBitrate
                    )
                },
                modifier = Modifier.testTag("force_tablet_switch")
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "DPI Override: ${state.manualDpi} dpi",
            style = MaterialTheme.typography.labelSmall,
            color = CyanPrimary
        )
        Slider(
            value = state.manualDpi.toFloat(),
            onValueChange = { value ->
                onUpdateDisplay(
                    state.resolutionCeiling,
                    state.forceTabletLayout,
                    value.toInt(),
                    state.prefer24GhzBitrate
                )
            },
            valueRange = 120f..320f,
            modifier = Modifier.testTag("dpi_override_slider")
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("2.4 GHz CBR Bitrate Profile", color = TextPrimary)
                Text(
                    "Caps encode at 4–6 Mbps CBR for legacy tablets",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            Switch(
                checked = state.prefer24GhzBitrate,
                onCheckedChange = { checked ->
                    onUpdateDisplay(
                        state.resolutionCeiling,
                        state.forceTabletLayout,
                        state.manualDpi,
                        checked
                    )
                },
                modifier = Modifier.testTag("cbr_24ghz_switch")
            )
        }
    }
}

@Composable
private fun AppDrawerPanel(
    state: WorkstationUiState,
    onLaunchApp: (String) -> Unit,
    onMoveAppBackToHost: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TacticalCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Display 1 App Launcher",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            TelemetryChip(
                label = if (state.shizukuStatus is ShizukuStatus.Authorized) "SHIZUKU ACTIVE" else "SHIZUKU REQUIRED",
                accentColor = if (state.shizukuStatus is ShizukuStatus.Authorized) EmeraldOnline else AmberWarning
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.launchableApps, key = { it.packageName }) { app ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = ObsidianBg,
                    border = BorderStroke(1.dp, SlateBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLaunchApp(app.packageName) }
                        .testTag("launch_app_${app.packageName}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, color = TextPrimary)
                            Text(
                                text = when {
                                    app.isActiveOnVirtual -> "Running on Display 1"
                                    app.isActiveOnHost -> "Open on Host — tap to move"
                                    else -> app.packageName
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (app.isActiveOnVirtual) EmeraldOnline else TextSecondary
                            )
                        }
                        if (app.isActiveOnVirtual) {
                            OutlinedButton(
                                onClick = { onMoveAppBackToHost(app.packageName) },
                                modifier = Modifier.testTag("move_back_host_${app.packageName}")
                            ) {
                                Text("Move to Host (Exp.)", maxLines = 1)
                            }
                        } else {
                            Text("Launch", style = MaterialTheme.typography.labelLarge, color = CyanPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubTabButton(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) CyanPrimary else SlateElevated,
            contentColor = if (selected) ObsidianBg else TextPrimary
        ),
        modifier = modifier.height(48.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}
