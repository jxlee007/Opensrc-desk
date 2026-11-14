package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.host.DesktopShellBridge
import com.example.model.ProjectionMode
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
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextOnAccent
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
    onSelectProjectionMode: (ProjectionMode) -> Unit = {},
    onToggleMirrorRightClickAsBack: (Boolean) -> Unit = {},
    onToggleMirrorAspectFallback: (Boolean) -> Unit = {},
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onDisconnect: () -> Unit
) {
    val telemetry = state.hostTelemetry
    val cfg = telemetry.config
    val isMirrorMode = telemetry.projectionMode == ProjectionMode.MIRROR_HOST_SCREEN
    val shizukuAuthorized = state.shizukuStatus is ShizukuStatus.Authorized
    val remoteControlsEnabled = !isMirrorMode || shizukuAuthorized
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
                            text = if (isMirrorMode && !shizukuAuthorized) {
                                "Input: View-only Mirror (Shizuku inactive)"
                            } else if (isMirrorMode) {
                                "Input: Display 0 · ${state.inputBackendStatus.label}"
                            } else {
                                "Input: ${state.inputBackendStatus.label}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isMirrorMode && !shizukuAuthorized) AmberWarning else TextSecondary,
                            modifier = Modifier.testTag("host_input_backend_label")
                        )
                    }
                },
                actions = {
                    if (!isMirrorMode) {
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
            val isCompactShortHeight = !isWide && maxHeight < 640.dp

            Column(
                modifier = if (isCompactShortHeight) {
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier.fillMaxSize()
                },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProjectionModeSwitcher(
                    selectedMode = telemetry.projectionMode,
                    onSelectMode = onSelectProjectionMode
                )

                if (isMirrorMode) {
                    MirrorModeStatusBanner(
                        shizukuStatus = state.shizukuStatus,
                        rightClickAsBack = telemetry.mirrorRightClickAsBack,
                        aspectFallbackEnabled = telemetry.mirrorAspectFallbackEnabled,
                        onToggleRightClickAsBack = onToggleMirrorRightClickAsBack,
                        onToggleAspectFallback = onToggleMirrorAspectFallback,
                        onRequestShizukuPermission = onRequestShizukuPermission,
                        onRefreshShizuku = onRefreshShizuku
                    )
                }

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
                            isMirrorMode = isMirrorMode,
                            controlsEnabled = remoteControlsEnabled,
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

                    val tabContentModifier = if (isCompactShortHeight) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.weight(1f)
                    }
                    Box(modifier = tabContentModifier) {
                        when (selectedSubTab) {
                            0 -> TouchpadAndImePane(
                                imeBuffer = imeBuffer,
                                isMirrorMode = isMirrorMode,
                                controlsEnabled = remoteControlsEnabled,
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
                                modifier = if (isCompactShortHeight) {
                                    Modifier.fillMaxWidth().height(320.dp)
                                } else {
                                    Modifier.fillMaxSize()
                                }
                            )
                            1 -> DisplayOverridesPanel(
                                state = state,
                                onUpdateDisplay = onUpdateDisplay
                            )
                            else -> AppDrawerPanel(
                                state = state,
                                onLaunchApp = onLaunchApp,
                                onMoveAppBackToHost = onMoveAppBackToHost,
                                modifier = if (isCompactShortHeight) {
                                    Modifier.fillMaxWidth().height(380.dp)
                                } else {
                                    Modifier.fillMaxSize()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectionModeSwitcher(
    selectedMode: ProjectionMode,
    onSelectMode: (ProjectionMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = SlateSurface,
            border = BorderStroke(1.dp, SlateBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val isExtended = selectedMode == ProjectionMode.EXTENDED_DESKTOP
                Button(
                    onClick = { onSelectMode(ProjectionMode.EXTENDED_DESKTOP) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isExtended) CyanPrimary else SlateElevated,
                        contentColor = if (isExtended) TextOnAccent else TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("mode_toggle_extended")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DesktopWindows,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Extended Desktop", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                val isMirror = selectedMode == ProjectionMode.MIRROR_HOST_SCREEN
                Button(
                    onClick = { onSelectMode(ProjectionMode.MIRROR_HOST_SCREEN) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMirror) CyanPrimary else SlateElevated,
                        contentColor = if (isMirror) TextOnAccent else TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("mode_toggle_mirror")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ScreenShare,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Mirror Host Screen", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            text = "Android 14+ asks for capture each time. Choose Entire screen.",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun MirrorModeStatusBanner(
    shizukuStatus: ShizukuStatus,
    rightClickAsBack: Boolean,
    aspectFallbackEnabled: Boolean,
    onToggleRightClickAsBack: (Boolean) -> Unit,
    onToggleAspectFallback: (Boolean) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit
) {
    val isAuthorized = shizukuStatus is ShizukuStatus.Authorized
    if (isAuthorized) {
        TacticalCard(
            borderColor = EmeraldOnline.copy(alpha = 0.6f),
            modifier = Modifier.testTag("mirror_input_active_banner")
        ) {
            TelemetryChip(
                label = "REMOTE DISPLAY 0 INPUT ACTIVE",
                accentColor = EmeraldOnline
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Right-click sends Back (Mirror)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Maps right-click / long-press to KEYCODE_BACK",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = rightClickAsBack,
                    onCheckedChange = onToggleRightClickAsBack,
                    modifier = Modifier.testTag("mirror_right_click_back_switch")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Host-aspect Mirror fallback",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Explicit host aspect size if OEM stretches Mirror",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = aspectFallbackEnabled,
                    onCheckedChange = onToggleAspectFallback,
                    modifier = Modifier.testTag("mirror_aspect_fallback_switch")
                )
            }
        }
    } else {
        TacticalCard(
            borderColor = AmberWarning.copy(alpha = 0.75f),
            modifier = Modifier.testTag("mirror_view_only_banner")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = "View-only Mirror",
                    tint = AmberWarning
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "View-only Mirror — activate Shizuku for remote Display 0 touch & keyboard control",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmberWarning
                    )
                }
                OutlinedButton(
                    onClick = if (shizukuStatus is ShizukuStatus.PermissionRequired) {
                        onRequestShizukuPermission
                    } else {
                        onRefreshShizuku
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("mirror_activate_shizuku_button")
                ) {
                    Text(
                        if (shizukuStatus is ShizukuStatus.PermissionRequired) "Authorize" else "Refresh"
                    )
                }
            }
        }
    }
}

@Composable
private fun TouchpadAndImePane(
    imeBuffer: String,
    isMirrorMode: Boolean,
    controlsEnabled: Boolean,
    onImeChanged: (String) -> Unit,
    onSendEnter: () -> Unit,
    onTrackpadDelta: (Float, Float, Byte) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(modifier = modifier) {
        val tightHeight = maxHeight < 290.dp
        val scrollModifier = if (tightHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(scrollModifier),
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
                        tint = if (controlsEnabled) CyanPrimary else TextSecondary
                    )
                    OutlinedTextField(
                        value = imeBuffer,
                        onValueChange = onImeChanged,
                        enabled = controlsEnabled,
                        placeholder = {
                            Text(
                                when {
                                    isMirrorMode && !controlsEnabled -> "View-only Mirror (Shizuku required for Display 0 IME)"
                                    isMirrorMode -> "Type with Host keyboard into Display 0…"
                                    else -> "Type with Host keyboard into Display 1…"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("host_ime_input")
                    )
                    Button(
                        onClick = onSendEnter,
                        enabled = controlsEnabled,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("host_ime_enter_button")
                    ) {
                        Text("Enter")
                    }
                }
            }

            // Precision Virtual Trackpad Surface
            val tapModifier = if (controlsEnabled) {
                Modifier.pointerInput(isMirrorMode, controlsEnabled) {
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
            } else {
                Modifier
            }
            val dragModifier = if (controlsEnabled) {
                Modifier.pointerInput(isMirrorMode, controlsEnabled) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dx = dragAmount.x / size.width.coerceAtLeast(1)
                        val dy = dragAmount.y / size.height.coerceAtLeast(1)
                        onTrackpadDelta(dx, dy, TouchSample.ACTION_MOVE)
                    }
                }
            } else {
                Modifier
            }

            val trackpadSizeMod = if (tightHeight) {
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            } else {
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            }

            Surface(
                modifier = trackpadSizeMod
                    .testTag("host_virtual_trackpad")
                    .then(tapModifier)
                    .then(dragModifier),
            shape = MaterialTheme.shapes.large,
            color = SlateElevated,
            border = BorderStroke(
                1.dp,
                if (controlsEnabled) CyanPrimary.copy(alpha = 0.4f) else AmberWarning.copy(alpha = 0.4f)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.align(Alignment.TopStart)) {
                    TelemetryChip(
                        label = when {
                            isMirrorMode && !controlsEnabled -> "VIEW-ONLY MIRROR • INPUT DISABLED"
                            isMirrorMode -> "REMOTE TOUCHPAD • DISPLAY 0"
                            else -> "TRI-MODE TRACKPAD • DISPLAY 1"
                        },
                        accentColor = if (controlsEnabled) CyanPrimary else AmberWarning
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isMirrorMode && !controlsEnabled) {
                            "Remote touch & keyboard controls are disabled while Mirror is view-only.\nActivate Shizuku to control Display 0 from the Display device or Host."
                        } else {
                            "Drag to move cursor • Tap to click • Long-press right-click\nBluetooth mouse/keyboard pair directly to Host."
                        },
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
                        enabled = controlsEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("trackpad_left_click")
                    ) {
                        Text("Left Click", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTrackpadDelta(0f, 0f, TouchSample.ACTION_RIGHT_CLICK)
                        },
                        enabled = controlsEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("trackpad_right_click")
                    ) {
                        Text(
                            text = if (isMirrorMode) "Right / Back" else "Right Click",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredApps = remember(state.launchableApps, searchQuery) {
        DesktopShellBridge.filterLaunchableApps(state.launchableApps, searchQuery)
    }

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
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search apps by name…") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search apps",
                    tint = CyanPrimary
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.testTag("host_app_search_clear")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear search",
                            tint = TextSecondary
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("host_app_search_input")
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SearchOff,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                    Text(
                        text = if (searchQuery.isBlank()) {
                            "No launchable apps found"
                        } else {
                            "No apps match \"$searchQuery\""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("host_app_list_lazy_column")
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = ObsidianBg,
                        border = BorderStroke(
                            1.dp,
                            if (app.isActiveOnVirtual) EmeraldOnline.copy(alpha = 0.55f) else SlateBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = when {
                                            app.isActiveOnVirtual -> "Running on Display 1"
                                            app.isActiveOnHost -> "Open on Host"
                                            else -> app.packageName
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            app.isActiveOnVirtual -> EmeraldOnline
                                            app.isActiveOnHost -> CyanPrimary
                                            else -> TextSecondary
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Button(
                                    onClick = { onLaunchApp(app.packageName) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyanPrimary,
                                        contentColor = TextOnAccent
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("launch_app_${app.packageName}")
                                ) {
                                    Text("Open on Display 1", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    OutlinedButton(
                                        onClick = { onMoveAppBackToHost(app.packageName) },
                                        enabled = app.isActiveOnVirtual,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("move_back_host_${app.packageName}")
                                    ) {
                                        Text("Move to Host", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(
                                        text = "Best-effort",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
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
