package com.example.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.core.KeystoreCrypto
import com.example.display.DisplaySessionEngine
import com.example.display.DisplaySurfaceContainerView
import com.example.display.TouchCoalescingDispatcher
import com.example.model.DisplayStreamOverlayState
import com.example.network.QrPairingCodec
import com.example.ui.WorkstationUiState
import com.example.ui.components.StatusDot
import com.example.ui.components.TacticalCard
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private enum class DisplayDialogState {
    NONE,
    MENU,
    INFO,
    QR
}

@Composable
fun DisplayTerminalScreen(
    state: WorkstationUiState,
    qrContent: String?,
    touchDispatcher: TouchCoalescingDispatcher,
    onSurfaceAvailable: (android.view.Surface?) -> Unit,
    onDisconnectStream: () -> Unit,
    onRevokeTrustedHost: (String) -> Unit,
    onDismissUpgradeNotice: () -> Unit,
    onSwitchToHostTesting: () -> Unit,
    onEnsureDisplayStandby: (Int) -> Unit = {}
) {
    val session = state.displaySession
    val pendingTrust = state.pendingTrustRequest
    val isDegradedMode = state.keystoreHealth == KeystoreCrypto.KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING ||
        session.sessionOnlyTrustMode
    var activeDialog by remember { mutableStateOf(DisplayDialogState.NONE) }
    var pillRevealTick by remember { mutableIntStateOf(0) }
    var pillVisible by remember { mutableStateOf(true) }

    val overlay = session.overlayState
    val isWarningOverlay = overlay is DisplayStreamOverlayState.SwitchingMode ||
        overlay is DisplayStreamOverlayState.ReconnectingVideo ||
        isDegradedMode

    LaunchedEffect(session.listeningPort) {
        if (session.listeningPort > 0) {
            onEnsureDisplayStandby(session.listeningPort)
        }
    }

    // Auto-hide status pill after 3.5 seconds when not showing a transition/reconnect/security warning
    LaunchedEffect(session.isConnected, pillRevealTick, isWarningOverlay) {
        if (isWarningOverlay) {
            pillVisible = true
        } else {
            pillVisible = true
            delay(3500L)
            pillVisible = false
        }
    }

    if (pendingTrust != null) {
        var remainingSeconds by remember(pendingTrust.hostId, pendingTrust.sasDeadlineMs) {
            mutableIntStateOf(
                ((pendingTrust.sasDeadlineMs - System.currentTimeMillis()) / 1000L)
                    .coerceIn(0L, pendingTrust.sasTimeoutMs / 1000L)
                    .toInt()
            )
        }
        LaunchedEffect(pendingTrust.hostId, pendingTrust.sasDeadlineMs) {
            if (pendingTrust.sasDeadlineMs > 0L) {
                while (true) {
                    val left = ((pendingTrust.sasDeadlineMs - System.currentTimeMillis()) / 1000L)
                        .coerceAtLeast(0L)
                        .toInt()
                    remainingSeconds = left
                    if (left <= 0) break
                    delay(1000L)
                }
            }
        }
        AlertDialog(
            onDismissRequest = pendingTrust.onReject,
            containerColor = SlateSurface,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Pair with ${pendingTrust.hostName}?", color = TextPrimary)
                    if (!pendingTrust.queuePositionLabel.isNullOrBlank()) {
                        Text(
                            text = pendingTrust.queuePositionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanPrimary,
                            modifier = Modifier.testTag("display_trust_queue_label")
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!pendingTrust.mismatchWarning.isNullOrBlank()) {
                        TacticalCard(
                            borderColor = AmberWarning.copy(alpha = 0.8f),
                            modifier = Modifier.testTag("display_trust_mismatch_warning")
                        ) {
                            Text(
                                text = pendingTrust.mismatchWarning,
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberWarning
                            )
                        }
                    }
                    Text(
                        text = "Verify this 6-digit code matches the Host screen (${pendingTrust.transportLabel}) before tapping Trust:",
                        color = TextSecondary
                    )
                    if (!pendingTrust.sasCode.isNullOrBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = ObsidianBg,
                            border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("display_sas_code_box")
                        ) {
                            Text(
                                text = pendingTrust.sasCode,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 26.sp,
                                color = CyanPrimary,
                                modifier = Modifier
                                    .padding(vertical = 14.dp)
                                    .align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                    if (pendingTrust.sasDeadlineMs > 0L) {
                        Text(
                            text = "Expires in ${remainingSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (remainingSeconds <= 15) AmberWarning else TextSecondary,
                            modifier = Modifier.testTag("display_sas_countdown")
                        )
                    }
                    if (pendingTrust.isSessionOnlyMode) {
                        Text(
                            text = DisplaySessionEngine.SESSION_ONLY_WARNING,
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarning,
                            modifier = Modifier.testTag("display_trust_degraded_notice")
                        )
                    } else {
                        Text(
                            text = "Confirming seals the token via ECDH and saves this Host for silent reconnect.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = pendingTrust.onApprove,
                    modifier = Modifier.testTag("approve_trust_button")
                ) {
                    Text(
                        if (!pendingTrust.sasCode.isNullOrBlank()) {
                            "Match ${pendingTrust.sasCode} · Trust"
                        } else {
                            "Trust & Connect"
                        }
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = pendingTrust.onReject,
                    modifier = Modifier.testTag("reject_trust_button")
                ) {
                    Text("Decline")
                }
            }
        )
    }

    when (activeDialog) {
        DisplayDialogState.MENU -> {
            AlertDialog(
                onDismissRequest = { activeDialog = DisplayDialogState.NONE },
                containerColor = SlateSurface,
                title = { Text("Display Options", color = TextPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (session.isConnected) {
                            Button(
                                onClick = {
                                    activeDialog = DisplayDialogState.NONE
                                    onDisconnectStream()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CrimsonError),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("display_disconnect_stream_button")
                            ) {
                                Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Disconnect")
                            }
                        }

                        OutlinedButton(
                            onClick = { activeDialog = DisplayDialogState.INFO },
                            border = BorderStroke(1.dp, SlateBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("display_menu_info_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = CyanPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Display Info", color = TextPrimary)
                        }

                        if (!qrContent.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = { activeDialog = DisplayDialogState.QR },
                                border = BorderStroke(1.dp, SlateBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("display_menu_qr_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.QrCode2,
                                    contentDescription = null,
                                    tint = CyanPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Show QR Code", color = TextPrimary)
                            }
                        }

                        if (state.sdkInt >= Build.VERSION_CODES.Q) {
                            HorizontalDivider(color = SlateBorder)
                            OutlinedButton(
                                onClick = {
                                    activeDialog = DisplayDialogState.NONE
                                    onSwitchToHostTesting()
                                },
                                border = BorderStroke(1.dp, SlateBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("display_switch_to_host_testing")
                            ) {
                                Text("Return to Host Mode (Testing)", color = TextSecondary)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = DisplayDialogState.NONE }) {
                        Text("Close", color = CyanPrimary)
                    }
                }
            )
        }

        DisplayDialogState.INFO -> {
            AlertDialog(
                onDismissRequest = { activeDialog = DisplayDialogState.NONE },
                containerColor = SlateSurface,
                title = { Text("Display Info", color = TextPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val profile = state.localProfile
                        Text(
                            text = if (profile != null) {
                                "${profile.deviceName}\n${profile.summaryTag()} · ${profile.densityDpi} dpi"
                            } else {
                                state.localDeviceName
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Wi-Fi: ${state.localIp}:${session.listeningPort} (${if (profile?.supports5GhzWifi == true) "5 GHz + 2.4 GHz" else "2.4 GHz"})",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanPrimary
                        )
                        if (isDegradedMode) {
                            Text(
                                text = DisplaySessionEngine.SESSION_ONLY_WARNING,
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberWarning
                            )
                        } else if (!session.securityNotice.isNullOrBlank()) {
                            Text(
                                text = session.securityNotice,
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberWarning
                            )
                        }
                        if (session.isConnected) {
                            val latency = session.latencyStats
                            Text(
                                text = "Stream: ${session.fps} FPS · ${latency.medianTouchToFrameMs} ms touch-to-frame",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldOnline
                            )
                        }
                        if (state.savedPeers.isNotEmpty()) {
                            HorizontalDivider(color = SlateBorder)
                            Text(
                                text = "Trusted Inbound Hosts",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            state.savedPeers.forEach { host ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(host.deviceName, color = TextPrimary)
                                        Text(
                                            text = "Token v${host.tokenVersion} · Inbound Host · ${host.lastKnownIp}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRevokeTrustedHost(host.peerId) },
                                        modifier = Modifier.testTag("display_revoke_host_${host.peerId}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Revoke Host trust",
                                            tint = CrimsonError
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = DisplayDialogState.NONE }) {
                        Text("Close", color = CyanPrimary)
                    }
                }
            )
        }

        DisplayDialogState.QR -> {
            AlertDialog(
                onDismissRequest = { activeDialog = DisplayDialogState.NONE },
                containerColor = SlateSurface,
                title = { Text("Display Pairing QR", color = TextPrimary) },
                text = {
                    val qrBitmap = remember(qrContent) {
                        qrContent?.let { QrPairingCodec.generateQrBitmap(it, 440)?.asImageBitmap() }
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (qrBitmap != null) {
                            Surface(color = Color.White, shape = MaterialTheme.shapes.small) {
                                Image(
                                    bitmap = qrBitmap,
                                    contentDescription = "Scan with Host to pair",
                                    modifier = Modifier
                                        .size(220.dp)
                                        .padding(10.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "${state.localIp}:${session.listeningPort}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = DisplayDialogState.NONE }) {
                        Text("Done", color = CyanPrimary)
                    }
                }
            )
        }

        DisplayDialogState.NONE -> Unit
    }

    // Full-screen minimal Display canvas (standby or live video)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (session.isConnected) Color.Black else ObsidianBg)
            .pointerInput(session.isConnected) {
                detectTapGestures(
                    onTap = { pillRevealTick++ },
                    onLongPress = { activeDialog = DisplayDialogState.MENU }
                )
            }
            .testTag("display_fullscreen_canvas")
    ) {
        if (session.isConnected) {
            AndroidView(
                factory = { ctx ->
                    DisplaySurfaceContainerView(
                        context = ctx,
                        touchDispatcher = touchDispatcher,
                        onSurfaceAvailable = onSurfaceAvailable
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("display_stream_canvas")
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = state.localDeviceName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )
                Text(
                    text = "Display ready · Same Wi-Fi",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyanPrimary
                )
                Text(
                    text = "Long-press screen for Disconnect, Info & QR",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                if (state.upgradeNoticePending) {
                    TacticalCard(
                        borderColor = AmberWarning.copy(alpha = 0.7f),
                        modifier = Modifier.testTag("display_v2_upgrade_banner")
                    ) {
                        Text(
                            text = "Security upgrade applied: previous v1 pairings were cleared. Re-approve Hosts once using 6-digit code verification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = onDismissUpgradeNotice,
                            modifier = Modifier.testTag("display_dismiss_upgrade_banner")
                        ) {
                            Text("Dismiss", color = CyanPrimary)
                        }
                    }
                }
                if (isDegradedMode) {
                    TacticalCard(
                        borderColor = AmberWarning.copy(alpha = 0.7f),
                        modifier = Modifier.testTag("display_degraded_keystore_banner")
                    ) {
                        Text(
                            text = DisplaySessionEngine.SESSION_ONLY_WARNING,
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberWarning
                        )
                    }
                } else if (!session.securityNotice.isNullOrBlank()) {
                    TacticalCard(
                        borderColor = AmberWarning.copy(alpha = 0.7f),
                        modifier = Modifier.testTag("display_security_notice_banner")
                    ) {
                        Text(
                            text = session.securityNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberWarning
                        )
                    }
                }
            }
        }

        // Auto-hiding status pill (always visible on mode switch / reconnect / Keystore warning)
        AnimatedVisibility(
            visible = pillVisible || isWarningOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 12.dp)
        ) {
            val pillColor = if (isWarningOverlay) AmberWarning else SlateSurface.copy(alpha = 0.88f)
            val textColor = if (isWarningOverlay) ObsidianBg else TextPrimary
            val pillText = when (overlay) {
                is DisplayStreamOverlayState.SwitchingMode -> overlay.message
                is DisplayStreamOverlayState.ReconnectingVideo -> overlay.message
                else -> when {
                    isDegradedMode && !session.isConnected ->
                        DisplaySessionEngine.SESSION_ONLY_WARNING
                    session.isConnected -> {
                        val latency = session.latencyStats
                        "${session.fps} FPS · ${latency.medianTouchToFrameMs} ms · Long-press pill for menu"
                    }
                    else -> "${state.localIp}:${session.listeningPort} · Long-press for menu"
                }
            }

            Surface(
                shape = MaterialTheme.shapes.large,
                color = pillColor,
                border = if (isWarningOverlay) null else BorderStroke(1.dp, SlateBorder),
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { pillRevealTick++ },
                            onLongPress = { activeDialog = DisplayDialogState.MENU }
                        )
                    }
                    .testTag("display_mode_switch_pill")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (!isWarningOverlay) {
                        StatusDot(color = if (session.isConnected) EmeraldOnline else CyanPrimary)
                    }
                    Text(
                        text = pillText,
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor
                    )
                }
            }
        }
    }
}
