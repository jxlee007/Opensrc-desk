package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import com.example.core.KeystoreCrypto
import com.example.display.DisplaySessionEngine
import com.example.host.HardwareVerificationRunner
import com.example.host.ObservedTouchEvent
import com.example.model.DeviceRole
import com.example.model.DiscoveredPeer
import com.example.model.TrustedPeer
import com.example.ui.WorkstationUiState
import com.example.ui.components.PresenceDot
import com.example.ui.components.QrScannerDialog
import com.example.ui.components.ShizukuOneLineBanner
import com.example.ui.components.ShizukuStatusCard
import com.example.ui.components.TacticalCard
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextOnAccent
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import androidx.compose.material3.Surface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class HostMenuSheet {
    NONE,
    OTHER_WAYS,
    SHIZUKU_SETUP,
    SETTINGS
}

private sealed interface SelectedTarget {
    data class Trusted(val peer: TrustedPeer) : SelectedTarget
    data class Discovered(val peer: DiscoveredPeer) : SelectedTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDiscoveryScreen(
    state: WorkstationUiState,
    onRefreshDiscovery: () -> Unit,
    onConnectTrusted: (TrustedPeer) -> Unit,
    onConnectDiscovered: (DiscoveredPeer) -> Unit,
    onConnectManual: (String, String) -> Unit,
    onQrDecoded: (String) -> Unit,
    onRevokeTrusted: (String) -> Unit,
    onDismissUpgradeNotice: () -> Unit,
    onStartWifiDirectScan: () -> Unit,
    onConnectWifiDirect: (String) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onContinuePreview: () -> Unit,
    onSetTestingDisplayOverride: (Boolean) -> Unit,
    onRunCryptoSelfCheck: () -> Unit = {},
    onRunRelocationProbe: () -> Unit = {},
    onRunInputReceiptGate: suspend (
        collectBaselineDisplay0: () -> List<ObservedTouchEvent>,
        clearDisplay0Buffer: () -> Unit,
        collectPostDisplay0: (Long) -> List<ObservedTouchEvent>
    ) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    var showOverflowMenu by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf(HostMenuSheet.NONE) }
    var showQrScanner by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("57321") }
    var permissionHint by remember { mutableStateOf<String?>(null) }
    var selectedPeerId by remember { mutableStateOf<String?>(null) }

    // Contextual POST_NOTIFICATIONS launcher invoked only when first tapping Connect on API 33+
    var hasRequestedNotifications by remember { mutableStateOf(false) }
    var pendingConnectAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            hasRequestedNotifications = true
            val action = pendingConnectAction
            pendingConnectAction = null
            action?.invoke()
        }
    )

    val runWithNotificationPermission: (() -> Unit) -> Unit = { connectAction ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasRequestedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingConnectAction = connectAction
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            connectAction()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) showQrScanner = true
            else permissionHint = "Camera permission is required only for QR code scanning."
        }
    )

    val wifiDirectPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) onStartWifiDirectScan()
            else permissionHint = "Nearby Wi-Fi / Location permission is required for Wi-Fi Direct."
        }
    )

    val onlineTrusted = state.savedPeers.filter { it.isOnline }
    val resolvedTarget: SelectedTarget? = remember(
        selectedPeerId,
        state.savedPeers,
        state.nearbyUntrustedPeers
    ) {
        val byIdTrusted = selectedPeerId?.let { id ->
            state.savedPeers.firstOrNull { it.peerId == id && it.isOnline }
        }
        val byIdDiscovered = selectedPeerId?.let { id ->
            state.nearbyUntrustedPeers.firstOrNull { it.peerId == id }
        }
        when {
            byIdTrusted != null -> SelectedTarget.Trusted(byIdTrusted)
            byIdDiscovered != null -> SelectedTarget.Discovered(byIdDiscovered)
            onlineTrusted.isNotEmpty() -> SelectedTarget.Trusted(onlineTrusted.first())
            state.nearbyUntrustedPeers.isNotEmpty() ->
                SelectedTarget.Discovered(state.nearbyUntrustedPeers.first())
            else -> null
        }
    }

    val pendingSas = state.pendingSasConfirmation
    if (pendingSas != null) {
        var remainingSeconds by remember(pendingSas.peerId, pendingSas.sasDeadlineMs) {
            mutableIntStateOf(
                ((pendingSas.sasDeadlineMs - System.currentTimeMillis()) / 1000L)
                    .coerceIn(0L, pendingSas.sasTimeoutMs / 1000L)
                    .toInt()
            )
        }
        LaunchedEffect(pendingSas.peerId, pendingSas.sasDeadlineMs) {
            if (pendingSas.sasDeadlineMs > 0L) {
                while (true) {
                    val left = ((pendingSas.sasDeadlineMs - System.currentTimeMillis()) / 1000L)
                        .coerceAtLeast(0L)
                        .toInt()
                    remainingSeconds = left
                    if (left <= 0) break
                    delay(1000L)
                }
            }
        }
        AlertDialog(
            onDismissRequest = pendingSas.onRejectMismatch,
            containerColor = SlateSurface,
            title = { Text("Confirm Code on ${pendingSas.peerName}", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!pendingSas.mismatchWarning.isNullOrBlank()) {
                        TacticalCard(
                            borderColor = AmberWarning.copy(alpha = 0.8f),
                            modifier = Modifier.testTag("host_sas_mismatch_warning")
                        ) {
                            Text(
                                text = pendingSas.mismatchWarning,
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberWarning
                            )
                        }
                    }
                    Text(
                        text = "Check that the 6-digit security code matches the code shown on ${pendingSas.peerName}:",
                        color = TextSecondary
                    )
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = ObsidianBg,
                        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("host_sas_code_box")
                    ) {
                        Text(
                            text = pendingSas.sasCode,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            color = CyanPrimary,
                            modifier = Modifier
                                .padding(vertical = 14.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    }
                    if (pendingSas.sasDeadlineMs > 0L) {
                        Text(
                            text = "Expires in ${remainingSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (remainingSeconds <= 15) AmberWarning else TextSecondary,
                            modifier = Modifier.testTag("host_sas_countdown")
                        )
                    }
                    if (pendingSas.isSessionOnlyMode) {
                        Text(
                            text = DisplaySessionEngine.SESSION_ONLY_WARNING,
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarning,
                            modifier = Modifier.testTag("host_sas_degraded_notice")
                        )
                    } else {
                        Text(
                            text = "Confirming saves an encrypted pairing token in Keystore for silent reconnects.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = pendingSas.onConfirmMatch,
                    modifier = Modifier.testTag("confirm_sas_match_button")
                ) {
                    Text("Confirm Match")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = pendingSas.onRejectMismatch,
                    modifier = Modifier.testTag("reject_sas_mismatch_button")
                ) {
                    Text("Codes Differ — Abort")
                }
            }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onQrDecoded = { raw ->
                showQrScanner = false
                runWithNotificationPermission { onQrDecoded(raw) }
            },
            onDismiss = { showQrScanner = false }
        )
    }

    when (activeSheet) {
        HostMenuSheet.OTHER_WAYS -> {
            AlertDialog(
                onDismissRequest = { activeSheet = HostMenuSheet.NONE },
                containerColor = SlateSurface,
                title = { Text("Other ways to connect", color = TextPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                activeSheet = HostMenuSheet.NONE
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            border = BorderStroke(1.dp, CyanPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("scan_qr_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.QrCodeScanner,
                                contentDescription = null,
                                tint = CyanPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan Display QR Code", color = TextPrimary)
                        }

                        HorizontalDivider(color = SlateBorder)

                        Text(
                            text = "Manual IP / USB Socket",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = manualIp,
                                onValueChange = { manualIp = it },
                                label = { Text("Display IP") },
                                placeholder = { Text("192.168.x.x or 127.0.0.1") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1.4f)
                                    .testTag("manual_ip_input")
                            )
                            OutlinedTextField(
                                value = manualPort,
                                onValueChange = { manualPort = it },
                                label = { Text("Port") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.8f)
                                    .testTag("manual_port_input")
                            )
                        }
                        Button(
                            onClick = {
                                val targetIp = manualIp.ifBlank { "127.0.0.1" }
                                activeSheet = HostMenuSheet.NONE
                                runWithNotificationPermission { onConnectManual(targetIp, manualPort) }
                            },
                            enabled = manualPort.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("manual_connect_button")
                        ) {
                            Icon(imageVector = Icons.Filled.Usb, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connect via IP / USB")
                        }

                        HorizontalDivider(color = SlateBorder)

                        Text(
                            text = "Wi-Fi Direct (${state.wifiDirectStatus})",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        OutlinedButton(
                            onClick = {
                                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    Manifest.permission.NEARBY_WIFI_DEVICES
                                } else {
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                }
                                wifiDirectPermissionLauncher.launch(perm)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("scan_wifi_direct_button")
                        ) {
                            Text("Scan Wi-Fi Direct", color = TextPrimary)
                        }
                        state.wifiDirectPeers.forEach { p2p ->
                            TacticalCard(
                                modifier = Modifier.clickable {
                                    activeSheet = HostMenuSheet.NONE
                                    onConnectWifiDirect(p2p.deviceAddress)
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(p2p.deviceName, color = TextPrimary)
                                    Text("Link", color = CyanPrimary)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeSheet = HostMenuSheet.NONE }) {
                        Text("Close", color = CyanPrimary)
                    }
                }
            )
        }

        HostMenuSheet.SHIZUKU_SETUP -> {
            AlertDialog(
                onDismissRequest = { activeSheet = HostMenuSheet.NONE },
                containerColor = SlateSurface,
                title = { Text("Host Shizuku & Preview", color = TextPrimary) },
                text = {
                    ShizukuStatusCard(
                        status = state.shizukuStatus,
                        onRequestPermission = onRequestShizukuPermission,
                        onRefresh = onRefreshShizuku,
                        onContinuePreview = {
                            onContinuePreview()
                            activeSheet = HostMenuSheet.NONE
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { activeSheet = HostMenuSheet.NONE }) {
                        Text("Done", color = CyanPrimary)
                    }
                }
            )
        }

        HostMenuSheet.SETTINGS -> {
            val runnerScope = rememberCoroutineScope()
            var targetSizePx by remember { mutableStateOf(IntSize.Zero) }
            var runningGate by remember { mutableStateOf(false) }
            val capturedDisplay0Events = remember { mutableStateListOf<ObservedTouchEvent>() }
            AlertDialog(
                onDismissRequest = { activeSheet = HostMenuSheet.NONE },
                containerColor = SlateSurface,
                title = { Text("Host Settings & Diagnostics", color = TextPrimary) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        if (state.sdkInt >= Build.VERSION_CODES.Q) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Display Mode Override",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Testing only: run this Android 10+ device as a secondary Display",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                                Switch(
                                    checked = state.activeRole == DeviceRole.DISPLAY,
                                    onCheckedChange = { checked ->
                                        activeSheet = HostMenuSheet.NONE
                                        onSetTestingDisplayOverride(checked)
                                    },
                                    modifier = Modifier.testTag("settings_role_override_switch")
                                )
                            }
                        }

                        HorizontalDivider(color = SlateBorder)
                        Text(
                            text = "On-Device Hardware Verification Runner",
                            style = MaterialTheme.typography.titleSmall,
                            color = CyanPrimary
                        )
                        Text(
                            text = state.hardwareDiagnostics.cryptoSelfCheckSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.testTag("diag_crypto_summary")
                        )
                        OutlinedButton(
                            onClick = onRunCryptoSelfCheck,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diag_run_crypto_button")
                        ) {
                            Text("Run API / ECDH / Probe Check")
                        }

                        Text(
                            text = "Experimental Relocation: ${state.hardwareDiagnostics.relocationSummary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.testTag("diag_relocation_summary")
                        )
                        if (state.hardwareDiagnostics.stackSnapshot.isNotBlank()) {
                            Text(
                                text = state.hardwareDiagnostics.stackSnapshot,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = onRunRelocationProbe,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diag_run_relocation_button")
                        ) {
                            Text("Probe Relocation (Experimental)")
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val measuredRadius = HardwareVerificationRunner.computeTargetRadiusPx(
                                targetSizePx.width,
                                targetSizePx.height
                            )
                            val measuredTol = HardwareVerificationRunner.computeMatchTolerancePx(measuredRadius)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(ObsidianBg)
                                    .border(1.dp, CyanPrimary)
                                    .onSizeChanged { targetSizePx = it }
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            capturedDisplay0Events.add(
                                                ObservedTouchEvent(
                                                    displayId = 0,
                                                    xPx = down.position.x,
                                                    yPx = down.position.y,
                                                    timestampMs = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                    }
                                    .testTag("diag_measured_target_box")
                            ) {
                                Text("Target", style = MaterialTheme.typography.labelSmall, color = CyanPrimary)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Target ${targetSizePx.width}x${targetSizePx.height}px · r=${"%.1f".format(measuredRadius)}px · tol=±${"%.1f".format(measuredTol)}px",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimary
                                )
                                OutlinedButton(
                                    enabled = !runningGate,
                                    onClick = {
                                        runningGate = true
                                        capturedDisplay0Events.clear()
                                        runnerScope.launch {
                                            try {
                                                onRunInputReceiptGate(
                                                    { capturedDisplay0Events.toList() },
                                                    { capturedDisplay0Events.clear() },
                                                    { tStart -> capturedDisplay0Events.filter { it.timestampMs >= tStart } }
                                                )
                                            } finally {
                                                runningGate = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("diag_run_input_gate_button")
                                ) {
                                    Text(if (runningGate) "Observing…" else "Run Input Gate (2s baseline)")
                                }
                            }
                        }
                        state.hardwareDiagnostics.lastInputReceipt?.let { eval ->
                            Text(
                                text = eval.summaryLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (eval.passed) EmeraldOnline else AmberWarning,
                                modifier = Modifier.testTag("diag_input_receipt_summary")
                            )
                        }

                        if (state.savedPeers.isNotEmpty()) {
                            HorizontalDivider(color = SlateBorder)
                            Text(
                                text = "Saved & Trusted Displays",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            state.savedPeers.forEach { peer ->
                                val endpoint = peer.lastKnownPort?.let { "${peer.lastKnownIp}:$it" }
                                    ?: peer.lastKnownIp
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(peer.deviceName, color = TextPrimary)
                                        Text(
                                            text = "Token v${peer.tokenVersion} • $endpoint",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRevokeTrusted(peer.peerId) },
                                        modifier = Modifier.testTag("settings_revoke_${peer.peerId}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Revoke Display trust",
                                            tint = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeSheet = HostMenuSheet.NONE }) {
                        Text("Close", color = CyanPrimary)
                    }
                }
            )
        }

        HostMenuSheet.NONE -> Unit
    }

    Scaffold(
        containerColor = ObsidianBg,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.localDeviceName,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Host · ${state.localIp}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.testTag("host_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Host menu",
                                tint = TextPrimary
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Other ways (QR / IP / USB / P2P)") },
                                onClick = {
                                    showOverflowMenu = false
                                    activeSheet = HostMenuSheet.OTHER_WAYS
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Shizuku Setup & Preview") },
                                onClick = {
                                    showOverflowMenu = false
                                    activeSheet = HostMenuSheet.SHIZUKU_SETUP
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh Same Wi-Fi") },
                                onClick = {
                                    showOverflowMenu = false
                                    onRefreshDiscovery()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showOverflowMenu = false
                                    activeSheet = HostMenuSheet.SETTINGS
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ObsidianBg)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // One-line Shizuku status banner with status dot
                ShizukuOneLineBanner(
                    status = state.shizukuStatus,
                    onClick = { activeSheet = HostMenuSheet.SHIZUKU_SETUP }
                )

                val isHostKeystoreDegraded =
                    state.keystoreHealth == KeystoreCrypto.KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING ||
                        state.hostTelemetry.sessionOnlyTrustMode
                if (isHostKeystoreDegraded) {
                    TacticalCard(
                        borderColor = AmberWarning.copy(alpha = 0.7f),
                        modifier = Modifier.testTag("host_degraded_keystore_banner")
                    ) {
                        Text(
                            text = DisplaySessionEngine.SESSION_ONLY_WARNING,
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberWarning
                        )
                    }
                }

                if (state.upgradeNoticePending) {
                    TacticalCard(
                        borderColor = AmberWarning.copy(alpha = 0.7f),
                        modifier = Modifier.testTag("host_v2_upgrade_banner")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Security upgrade applied: previous v1 pairings were cleared. Re-verify Displays once using 6-digit code confirmation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = onDismissUpgradeNotice,
                                modifier = Modifier.testTag("host_dismiss_upgrade_banner")
                            ) {
                                Text("Dismiss", color = CyanPrimary)
                            }
                        }
                    }
                }

                if (permissionHint != null) {
                    TacticalCard(borderColor = CyanPrimary.copy(alpha = 0.5f)) {
                        Text(
                            text = permissionHint!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }

                if (!state.hostTelemetry.connected && !state.hostTelemetry.securityNotice.isNullOrBlank()) {
                    TacticalCard(
                        borderColor = AmberWarning.copy(alpha = 0.7f),
                        modifier = Modifier.testTag("host_security_notice_banner")
                    ) {
                        Text(
                            text = state.hostTelemetry.securityNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberWarning
                        )
                    }
                }

                // Same Wi-Fi Nearby + Conditional Saved Displays
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.savedPeers.isNotEmpty()) {
                        item {
                            Text(
                                text = "Saved & Trusted Displays",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextSecondary
                            )
                        }
                        items(state.savedPeers, key = { "saved_${it.peerId}" }) { peer ->
                            val isSelected = (resolvedTarget as? SelectedTarget.Trusted)?.peer?.peerId == peer.peerId
                            val endpoint = peer.lastKnownPort?.let { "${peer.lastKnownIp}:$it" }
                                ?: peer.lastKnownIp
                            TacticalCard(
                                borderColor = when {
                                    isSelected -> CyanPrimary
                                    peer.isOnline -> EmeraldOnline.copy(alpha = 0.5f)
                                    else -> SlateBorder
                                },
                                modifier = Modifier
                                    .clickable(enabled = peer.isOnline) {
                                        selectedPeerId = peer.peerId
                                    }
                                    .testTag("trusted_peer_card_${peer.peerId}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    PresenceDot(isOnline = peer.isOnline)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = peer.deviceName,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = if (peer.isOnline) {
                                                "Online · $endpoint"
                                            } else {
                                                "Offline · Last seen $endpoint"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (peer.isOnline) EmeraldOnline else TextSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRevokeTrusted(peer.peerId) },
                                        modifier = Modifier.testTag("revoke_peer_${peer.peerId}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Revoke saved Display",
                                            tint = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = "Same Wi-Fi Displays",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary
                        )
                    }

                    if (state.nearbyUntrustedPeers.isEmpty() && onlineTrusted.isEmpty()) {
                        item {
                            TacticalCard {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Wifi,
                                        contentDescription = null,
                                        tint = CyanPrimary
                                    )
                                    Text(
                                        text = "Scanning Same Wi-Fi for Displays… Use ⋮ → Other ways for QR, IP, USB, or Wi-Fi Direct.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        items(state.nearbyUntrustedPeers, key = { "nearby_${it.peerId}" }) { peer ->
                            val isSelected =
                                (resolvedTarget as? SelectedTarget.Discovered)?.peer?.peerId == peer.peerId
                            TacticalCard(
                                borderColor = if (isSelected) CyanPrimary else SlateBorder,
                                modifier = Modifier
                                    .clickable { selectedPeerId = peer.peerId }
                                    .testTag("discovered_peer_card_${peer.peerId}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    PresenceDot(isOnline = true)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = peer.deviceName,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "${peer.displaySummary} · ${peer.ipAddress}:${peer.controlPort}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.hostTelemetry.statusNote != "Idle") {
                        item {
                            Text(
                                text = state.hostTelemetry.statusNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanPrimary
                            )
                        }
                    }
                }

                // Single primary Connect CTA (Decision 8)
                Button(
                    onClick = {
                        when (val target = resolvedTarget) {
                            is SelectedTarget.Trusted ->
                                runWithNotificationPermission { onConnectTrusted(target.peer) }
                            is SelectedTarget.Discovered ->
                                runWithNotificationPermission { onConnectDiscovered(target.peer) }
                            null -> Unit
                        }
                    },
                    enabled = resolvedTarget != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = TextOnAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("host_primary_connect_button")
                ) {
                    val label = when (resolvedTarget) {
                        is SelectedTarget.Trusted -> "Connect · ${resolvedTarget.peer.deviceName}"
                        is SelectedTarget.Discovered -> "Connect · ${resolvedTarget.peer.deviceName}"
                        null -> "Connect"
                    }
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
