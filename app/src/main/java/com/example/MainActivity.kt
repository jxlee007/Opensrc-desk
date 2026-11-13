package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.model.DeviceRole
import com.example.ui.WorkstationViewModel
import com.example.ui.screens.DisplayTerminalScreen
import com.example.ui.screens.HostControllerScreen
import com.example.ui.screens.HostDiscoveryScreen
import com.example.ui.theme.ExtendedDesktopTheme
import kotlinx.serialization.Serializable

@Serializable
object HostDiscoveryRoute

@Serializable
object HostControllerRoute

@Serializable
object DisplayTerminalRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExtendedDesktopTheme {
                val viewModel: WorkstationViewModel = viewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }

                val initialRoute: Any = remember {
                    if (state.recommendedRole == DeviceRole.HOST) {
                        HostDiscoveryRoute
                    } else {
                        DisplayTerminalRoute
                    }
                }

                LaunchedEffect(state.bannerMessage) {
                    val msg = state.bannerMessage
                    if (!msg.isNullOrBlank()) {
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearBanner()
                    }
                }

                var previousRole by remember { mutableStateOf(state.activeRole) }

                // Automatic OS-based or Settings testing-override role navigation
                LaunchedEffect(state.activeRole) {
                    if (state.activeRole != previousRole) {
                        previousRole = state.activeRole
                        val target = if (state.activeRole == DeviceRole.HOST) {
                            HostDiscoveryRoute
                        } else {
                            DisplayTerminalRoute
                        }
                        navController.navigate(target) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }

                LaunchedEffect(state.hostTelemetry.connected) {
                    if (state.hostTelemetry.connected && state.activeRole == DeviceRole.HOST) {
                        navController.navigate(HostControllerRoute) {
                            launchSingleTop = true
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = initialRoute
                    ) {
                        composable<HostDiscoveryRoute> {
                            HostDiscoveryScreen(
                                state = state,
                                onRefreshDiscovery = { viewModel.refreshDiscovery() },
                                onConnectTrusted = { viewModel.connectToTrustedPeer(it) },
                                onConnectDiscovered = { viewModel.connectToPeer(it) },
                                onConnectManual = { ip, port -> viewModel.connectManual(ip, port) },
                                onQrDecoded = { viewModel.connectFromQr(it) },
                                onRevokeTrusted = { viewModel.revokeTrustedPeer(it) },
                                onDismissUpgradeNotice = { viewModel.dismissUpgradeNotice() },
                                onStartWifiDirectScan = { viewModel.startWifiDirectScan() },
                                onConnectWifiDirect = { viewModel.connectWifiDirectPeer(it) },
                                onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
                                onRefreshShizuku = { viewModel.refreshDiscovery() },
                                onContinuePreview = { viewModel.continueWithLayoutPreview() },
                                onSetTestingDisplayOverride = { useDisplay ->
                                    viewModel.setTestingRoleOverride(useDisplay)
                                },
                                onRunCryptoSelfCheck = { viewModel.runCryptoAndLatencySelfCheck() },
                                onRunRelocationProbe = { viewModel.runExperimentalRelocationProbe() },
                                onRunInputReceiptGate = { collectBase, clearBuf, collectPost ->
                                    viewModel.runInputReceiptGateDiagnostic(collectBase, clearBuf, collectPost)
                                }
                            )
                        }

                        composable<HostControllerRoute> {
                            HostControllerScreen(
                                state = state,
                                onTrackpadDelta = { dx, dy, action ->
                                    viewModel.hostEngine.sendTrackpadDelta(dx, dy, action)
                                },
                                onImeInput = { text, keyCode ->
                                    viewModel.hostEngine.sendImeInput(text, keyCode)
                                },
                                onUpdateDisplay = { ceiling, forceTablet, dpi, prefer24 ->
                                    viewModel.updateDisplayOverrides(ceiling, forceTablet, dpi, prefer24)
                                },
                                onLaunchApp = { pkg -> viewModel.launchAppOnExtendedDisplay(pkg) },
                                onMoveAppBackToHost = { pkg -> viewModel.moveAppBackToHost(pkg) },
                                onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
                                onRefreshShizuku = { viewModel.refreshDiscovery() },
                                onDisconnect = {
                                    viewModel.hostEngine.disconnect("Session ended by Host")
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable<DisplayTerminalRoute> {
                            DisplayTerminalScreen(
                                state = state,
                                qrContent = viewModel.buildDisplayQrString(),
                                touchDispatcher = viewModel.displayEngine.touchDispatcher,
                                onSurfaceAvailable = { surface ->
                                    viewModel.displayEngine.attachSurface(surface)
                                },
                                onDisconnectStream = {
                                    viewModel.displayEngine.disconnectSession("Disconnected by Display")
                                },
                                onRevokeTrustedHost = { viewModel.revokeTrustedPeer(it) },
                                onDismissUpgradeNotice = { viewModel.dismissUpgradeNotice() },
                                onSwitchToHostTesting = { viewModel.setTestingRoleOverride(false) },
                                onEnsureDisplayStandby = { port ->
                                    viewModel.ensureDisplayStandbyForegroundService(port)
                                }
                            )
                        }
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                    )
                }
            }
        }
    }
}
