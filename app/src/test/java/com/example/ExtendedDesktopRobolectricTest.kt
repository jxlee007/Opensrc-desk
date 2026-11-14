package com.example

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.example.core.DisplayProfiler
import com.example.core.KeystoreCrypto
import com.example.core.TrustedPeerStore
import com.example.display.DisplaySessionEngine
import com.example.model.TrustedPeerRole
import com.example.network.MdnsResolvedPeer
import com.example.protocol.ControlCodec
import com.example.protocol.ControlMessage
import com.example.protocol.PairingCrypto
import com.example.ui.WorkstationViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExtendedDesktopRobolectricTest {

    private class WorkingVault : KeystoreCrypto.TokenVault {
        override val activeHealth = KeystoreCrypto.KeystoreHealth.KEYSTORE_AES_GCM_API23
        override fun encrypt(rawToken: String): String = "ENC:$rawToken"
        override fun decrypt(storedValue: String): String? =
            if (storedValue.startsWith("ENC:")) storedValue.removePrefix("ENC:") else null
        override fun rotate() = Unit
    }

    private class FailingVault : KeystoreCrypto.TokenVault {
        override val activeHealth = KeystoreCrypto.KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING
        override fun encrypt(rawToken: String): String {
            throw IllegalStateException("Simulated broken OEM Keystore")
        }
        override fun decrypt(storedValue: String): String? = null
        override fun rotate() = Unit
    }

    private fun isolatedDataStore(context: Context, name: String): DataStore<Preferences> {
        val file = File(context.filesDir, "$name.preferences_pb").apply { delete() }
        return PreferenceDataStoreFactory.create { file }
    }

    @Test
    fun appNameMatchesAndDisplayProfilerCapturesMetricsOnApi36() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Extended Desktop", context.getString(R.string.app_name))

        val profile = DisplayProfiler(context).captureRealMetrics("test-api36")
        assertTrue(profile.widthPixels > 0)
        assertTrue(profile.heightPixels > 0)
        assertTrue(profile.densityDpi > 0)
    }

    @Test
    fun keystoreFailClosedReturnsNullWithoutLegacyObfAndEntersSymmetricDegradedMode() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failingCrypto = KeystoreCrypto(context, FailingVault())
        val ds = isolatedDataStore(context, "degraded_test_prefs")
        val store = TrustedPeerStore(context, failingCrypto, ds)
        store.ensureSchemaV2Migrated()

        // Fail-closed: both Host and Display trustPeer return null and persist nothing
        assertNull(failingCrypto.encryptToken("secret-token-123"))
        assertNull(
            store.trustPeer(
                peerId = "disp-1",
                deviceName = "Tab 2",
                rawToken = "host-token",
                peerRole = TrustedPeerRole.HOST_SAVED_DISPLAY,
                ip = "192.168.1.44",
                port = 57321,
                profile = null
            )
        )
        assertNull(
            store.trustPeer(
                peerId = "host-1",
                deviceName = "Pixel Host",
                rawToken = "display-token",
                peerRole = TrustedPeerRole.DISPLAY_INBOUND_HOST,
                ip = "192.168.1.10",
                port = null,
                profile = null
            )
        )
        assertTrue(failingCrypto.isSessionOnlyDegraded())
        assertEquals(
            KeystoreCrypto.KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING,
            failingCrypto.healthStatus
        )
        assertTrue(store.trustedPeersFlow.first().isEmpty())
        assertNull(failingCrypto.decryptToken("LEGACY_OBF:c2VjcmV0"))

        val sp: SharedPreferences = context.getSharedPreferences("extdesk_keystore_meta", Context.MODE_PRIVATE)
        assertFalse(sp.all.values.any { it.toString().contains("secret-token-123") })
        assertFalse(ds.data.first().asMap().values.any { it.toString().contains("LEGACY_OBF:") })
    }

    @Test
    fun v2PeerRoleNormalizationAndPortSemanticsAcrossHostAndDisplay() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workingCrypto = KeystoreCrypto(context, WorkingVault())
        val ds = isolatedDataStore(context, "role_norm_prefs")
        val store = TrustedPeerStore(context, workingCrypto, ds)
        store.ensureSchemaV2Migrated()

        // Display saves inbound Host -> lastKnownPort must ALWAYS normalize to null
        val displaySideHost = store.trustPeer(
            peerId = "host-pixel",
            deviceName = "Pixel 8 Host",
            rawToken = "token-inbound-host",
            peerRole = TrustedPeerRole.DISPLAY_INBOUND_HOST,
            ip = "192.168.1.10",
            port = 12345, // intentionally passed non-null to confirm normalization
            profile = null
        )
        assertNotNull(displaySideHost)
        assertNull(displaySideHost!!.lastKnownPort)
        assertEquals(TrustedPeerRole.DISPLAY_INBOUND_HOST, displaySideHost.peerRole)

        // Host saves Display -> preserves valid controlPort (57321)
        val hostSideDisplay = store.trustPeer(
            peerId = "display-tab2",
            deviceName = "Galaxy Tab 2",
            rawToken = "token-saved-display",
            peerRole = TrustedPeerRole.HOST_SAVED_DISPLAY,
            ip = "192.168.1.44",
            port = DisplaySessionEngine.DEFAULT_CONTROL_PORT,
            profile = null
        )
        assertNotNull(hostSideDisplay)
        assertEquals(57321, hostSideDisplay!!.lastKnownPort)
        assertEquals(TrustedPeerRole.HOST_SAVED_DISPLAY, hostSideDisplay.peerRole)

        val persisted = store.trustedPeersFlow.first()
        assertEquals(2, persisted.size)
        assertEquals(
            "token-inbound-host",
            store.getDecryptedToken("host-pixel", TrustedPeerRole.DISPLAY_INBOUND_HOST)
        )
        assertEquals(
            "token-saved-display",
            store.getDecryptedToken("display-tab2", TrustedPeerRole.HOST_SAVED_DISPLAY)
        )
    }

    @Test
    fun schemaV1MixedLegacyRecordsAreClearedWithOneTimeUpgradeBanner() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ds = isolatedDataStore(context, "schema_v1_migration_test")
        val workingCrypto = KeystoreCrypto(context, WorkingVault())
        val store = TrustedPeerStore(context, workingCrypto, ds)
        val peersKey = stringPreferencesKey("trusted_peers_json")
        val versionKey = intPreferencesKey("trusted_peers_schema_version")

        // Write untagged v1 JSON lacking peerRole (typical mixed testing-override device)
        ds.edit { prefs ->
            prefs[versionKey] = 1
            prefs[peersKey] = """[{"peerId":"old-peer","deviceName":"LegacyTab","encryptedPairingToken":"LEGACY_OBF:abc","tokenVersion":1,"lastKnownIp":"192.168.1.5","lastKnownPort":57321}]"""
        }

        // Migration clears legacy records safely and flips one-time upgrade notice flag
        store.ensureSchemaV2Migrated()
        assertTrue(store.trustedPeersFlow.first().isEmpty())
        assertTrue(store.upgradeNoticePendingFlow.first())

        store.dismissUpgradeNotice()
        assertFalse(store.upgradeNoticePendingFlow.first())
    }

    @Test
    fun mdnsPeerIdTakesPriorityOverStoredPortOnReconnectAndFallsBackWhenAbsent() {
        val savedDisplay = com.example.model.TrustedPeer(
            peerId = "tab2-peer-id",
            deviceName = "Galaxy Tab 2",
            encryptedPairingToken = "ENC:tok",
            tokenVersion = 1,
            peerRole = TrustedPeerRole.HOST_SAVED_DISPLAY,
            lastKnownIp = "192.168.1.44",
            lastKnownPort = 57321
        )

        // When mDNS finds a new DHCP IP & ephemeral fallback port, Host dials mDNS address first
        val liveMdns = listOf(
            MdnsResolvedPeer(
                serviceName = "extdesk-tab2",
                peerId = "tab2-peer-id",
                deviceName = "Galaxy Tab 2",
                ipAddress = "192.168.1.99",
                port = 41234,
                displaySummary = "1280x800"
            )
        )
        val (mdnsIp, mdnsPort) = WorkstationViewModel.resolveReconnectEndpoint(savedDisplay, liveMdns)
        assertEquals("192.168.1.99", mdnsIp)
        assertEquals(41234, mdnsPort)

        // When mDNS has no match (e.g. USB / delayed discovery), Host falls back to stored lastKnownIp & lastKnownPort
        val (fallbackIp, fallbackPort) = WorkstationViewModel.resolveReconnectEndpoint(savedDisplay, emptyList())
        assertEquals("192.168.1.44", fallbackIp)
        assertEquals(57321, fallbackPort)

        // If a saved record somehow has null port, falls back to DEFAULT_CONTROL_PORT (57321)
        val (defaultIp, defaultPort) = WorkstationViewModel.resolveReconnectEndpoint(
            savedDisplay.copy(lastKnownPort = null),
            emptyList()
        )
        assertEquals("192.168.1.44", defaultIp)
        assertEquals(DisplaySessionEngine.DEFAULT_CONTROL_PORT, defaultPort)
    }

    @Test
    fun displayEngineRejectsSpoofedHostReconnectOverLoopbackSocket() {
        // Loopback socket test validating wire-level v2 protocol rejection (no silent fallback)
        ServerSocket(0).use { srv ->
            val port = srv.localPort
            val hostThread = Thread {
                Socket().use { client ->
                    client.connect(InetSocketAddress("127.0.0.1", port), 2000)
                    val out = DataOutputStream(client.getOutputStream())
                    val inp = DataInputStream(client.getInputStream())
                    ControlCodec.writeJsonFrame(
                        out,
                        ControlMessage.HandshakeRequest(
                            protocolVersion = PairingCrypto.PROTOCOL_VERSION,
                            minSupportedProtocolVersion = PairingCrypto.MIN_PROTOCOL_VERSION,
                            hostId = "host-1",
                            hostName = "Pixel Host",
                            hostNonce = PairingCrypto.generateNonce(),
                            hostEcdhPubKey = PairingCrypto.encodePublicKey(PairingCrypto.generateEcdhKeyPair()),
                            expectSavedTrust = true
                        )
                    )
                    val resp = (ControlCodec.readNextFrame(inp) as ControlCodec.DecodedFrame.JsonControl).message
                    assertTrue(resp is ControlMessage.HandshakeChallenge)
                    val ch = resp as ControlMessage.HandshakeChallenge
                    val verified = PairingCrypto.verifyDisplayProofHmac(
                        sharedToken = "real-saved-token",
                        candidateHmac = ch.displayProofHmac,
                        negotiatedVersion = ch.negotiatedProtocolVersion,
                        hostNonce = "n1",
                        displayNonce = ch.displayNonce,
                        hostId = "host-1",
                        displayPeerId = ch.peerId
                    )
                    assertFalse(verified)
                }
            }
            hostThread.start()

            srv.accept().use { serverConn ->
                val inp = DataInputStream(serverConn.getInputStream())
                val out = DataOutputStream(serverConn.getOutputStream())
                val req = (ControlCodec.readNextFrame(inp) as ControlCodec.DecodedFrame.JsonControl)
                    .message as ControlMessage.HandshakeRequest
                assertTrue(req.expectSavedTrust)
                ControlCodec.writeJsonFrame(
                    out,
                    ControlMessage.HandshakeChallenge(
                        negotiatedProtocolVersion = 2,
                        peerId = "tab2-saved",
                        displayNonce = PairingCrypto.generateNonce(),
                        displayProofHmac = "forged-hmac-tag",
                        requiresUserApproval = false
                    )
                )
            }
            hostThread.join(2000)
        }
    }

    private class ToggleableVault(
        @Volatile var available: Boolean = true,
        @Volatile var failOnEncrypt: Boolean = false
    ) : KeystoreCrypto.TokenVault {
        override val activeHealth: KeystoreCrypto.KeystoreHealth
            get() = if (available) {
                KeystoreCrypto.KeystoreHealth.KEYSTORE_AES_GCM_API23
            } else {
                KeystoreCrypto.KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING
            }

        override fun encrypt(rawToken: String): String {
            if (!available || (failOnEncrypt && rawToken != "ks-health-probe")) {
                throw IllegalStateException("Simulated Keystore write failure")
            }
            return "ENC:$rawToken"
        }

        override fun decrypt(storedValue: String): String? =
            if (available && storedValue.startsWith("ENC:")) storedValue.removePrefix("ENC:") else null

        override fun rotate() = Unit
    }

    private class ControlRelayProxy(
        private val targetPort: Int,
        private val dropHostToDisplay: (ControlMessage) -> Boolean = { false },
        private val dropDisplayToHost: (ControlMessage) -> Boolean = { false }
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        @Volatile private var stopped = false
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())

        init {
            Thread {
                while (!stopped && !server.isClosed) {
                    val hostSock = runCatching { server.accept() }.getOrNull() ?: break
                    sockets.add(hostSock)
                    val dispSock = runCatching {
                        Socket().apply {
                            tcpNoDelay = true
                            connect(InetSocketAddress("127.0.0.1", targetPort), 2000)
                        }
                    }.getOrNull()
                    if (dispSock == null) {
                        runCatching { hostSock.close() }
                        continue
                    }
                    sockets.add(dispSock)
                    forwardLoop(hostSock, dispSock, dropHostToDisplay)
                    forwardLoop(dispSock, hostSock, dropDisplayToHost)
                }
            }.apply { isDaemon = true }.start()
        }

        private fun forwardLoop(
            from: Socket,
            to: Socket,
            dropPredicate: (ControlMessage) -> Boolean
        ) {
            Thread {
                try {
                    val inp = DataInputStream(from.getInputStream())
                    val out = DataOutputStream(to.getOutputStream())
                    while (!stopped && !from.isClosed && !to.isClosed) {
                        when (val frame = ControlCodec.readNextFrame(inp)) {
                            is ControlCodec.DecodedFrame.JsonControl -> {
                                if (dropPredicate(frame.message)) {
                                    // Swallow frame after sender's socket write succeeded; close downstream to emulate loss
                                    runCatching { to.close() }
                                    break
                                }
                                ControlCodec.writeJsonFrame(out, frame.message)
                            }
                            is ControlCodec.DecodedFrame.BinaryTouch -> {
                                synchronized(out) {
                                    out.writeByte(ControlCodec.FRAME_BINARY_TOUCH.toInt())
                                    out.writeInt(frame.bytes.size)
                                    out.write(frame.bytes)
                                    out.flush()
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() {
            stopped = true
            runCatching { server.close() }
            synchronized(sockets) {
                sockets.forEach { runCatching { it.close() } }
                sockets.clear()
            }
        }
    }

    private fun testDisplayProfile(deviceId: String): com.example.model.DisplayProfile =
        com.example.model.DisplayProfile(
            deviceId = deviceId,
            deviceName = "Test Tab 2",
            sdkInt = 22,
            widthPixels = 1280,
            heightPixels = 800,
            densityDpi = 160,
            xdpi = 160f,
            ydpi = 160f,
            aspectRatioLabel = "16:10",
            aspectRatioValue = 1.6f,
            physicalDiagonalInches = 10.1f,
            smallestWidthDp = 800,
            supports5GhzWifi = false,
            isTabletClass = true
        )

    private fun awaitCondition(timeoutMs: Long = 6_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    @Test
    fun hostPersistsPendingCommitFinalAndClearsOnSubsequentSilentHmac() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hostStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "hmac_host_ds"))
        val dispStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "hmac_disp_ds"))
        val hostId = hostStore.getOrCreateDeviceId()
        val dispId = "disp-local-1"

        val displayEngine = DisplaySessionEngine(context, dispStore, KeystoreCrypto(context, WorkingVault()))
        val hostEngine = com.example.host.HostSessionEngine(
            context,
            hostStore,
            com.example.host.ShizukuController(context),
            KeystoreCrypto(context, WorkingVault())
        )
        try {
            val port = displayEngine.startListening(testDisplayProfile(dispId))
            val peer = com.example.model.DiscoveredPeer(
                peerId = dispId,
                deviceName = "Tab 2",
                ipAddress = "127.0.0.1",
                controlPort = port,
                displaySummary = "1280x800",
                isTrusted = false,
                isOnline = true,
                transportMode = com.example.model.TransportMode.LOCAL_WIFI
            )

            // Session 1: Initial SAS pairing
            hostEngine.connectToPeer(peer, "Pixel Host")
            awaitCondition { hostEngine.pendingSasConfirmation.value != null && displayEngine.pendingTrust.value != null }
            assertEquals(displayEngine.pendingTrust.value!!.sasCode, hostEngine.pendingSasConfirmation.value!!.sasCode)
            hostEngine.pendingSasConfirmation.value!!.onConfirmMatch()
            displayEngine.pendingTrust.value!!.onApprove()

            awaitCondition {
                kotlinx.coroutines.runBlocking {
                    hostStore.getPeer(dispId, TrustedPeerRole.HOST_SAVED_DISPLAY)?.pendingCommitFinal == true &&
                        dispStore.getPeer(hostId, TrustedPeerRole.DISPLAY_INBOUND_HOST) != null
                }
            }
            // Wait until Display actually receives HandshakeCommitFinal and saves host
            val savedOnHost = hostStore.getPeer(dispId, TrustedPeerRole.HOST_SAVED_DISPLAY)
            assertNotNull(savedOnHost)
            // Note: Display saves under hostEngine's localId (stored in hostStore); verify a peer exists on Display
            assertTrue(savedOnHost!!.pendingCommitFinal)
            assertEquals(1, dispStore.trustedPeersFlow.first().size)

            hostEngine.disconnect("End session 1")
            displayEngine.disconnectSession("End session 1")
            Thread.sleep(50)

            // Session 2: Silent mutual HMAC reconnect confirms commit and clears pendingCommitFinal
            hostEngine.connectToPeer(peer.copy(isTrusted = true), "Pixel Host")
            awaitCondition {
                kotlinx.coroutines.runBlocking {
                    hostStore.getPeer(dispId, TrustedPeerRole.HOST_SAVED_DISPLAY)?.pendingCommitFinal == false
                }
            }
            assertNull("Silent HMAC reconnect must not prompt SAS on Host", hostEngine.pendingSasConfirmation.value)
            assertNull("Silent HMAC reconnect must not prompt Trust on Display", displayEngine.pendingTrust.value)
        } finally {
            hostEngine.disconnect("Cleanup")
            displayEngine.stopAll()
        }
    }

    @Test
    fun droppedCommitFinalKeepsPendingFlagAndShowsLostCommitWarningOnReconnect() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hostStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "drop_final_host"))
        val dispStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "drop_final_disp"))
        val dispId = "disp-drop-1"

        val displayEngine = DisplaySessionEngine(context, dispStore, KeystoreCrypto(context, WorkingVault()))
        val hostEngine = com.example.host.HostSessionEngine(
            context,
            hostStore,
            com.example.host.ShizukuController(context),
            KeystoreCrypto(context, WorkingVault())
        )
        val dispPort = displayEngine.startListening(testDisplayProfile(dispId))
        val relay = ControlRelayProxy(
            targetPort = dispPort,
            dropHostToDisplay = { msg -> msg is ControlMessage.HandshakeCommitFinal }
        )
        try {
            val peerViaRelay = com.example.model.DiscoveredPeer(
                peerId = dispId,
                deviceName = "Tab 2 Relay",
                ipAddress = "127.0.0.1",
                controlPort = relay.port,
                displaySummary = "1280x800",
                isTrusted = false,
                isOnline = true,
                transportMode = com.example.model.TransportMode.LOCAL_WIFI
            )
            hostEngine.connectToPeer(peerViaRelay, "Pixel Host")
            awaitCondition { hostEngine.pendingSasConfirmation.value != null && displayEngine.pendingTrust.value != null }
            hostEngine.pendingSasConfirmation.value!!.onConfirmMatch()
            displayEngine.pendingTrust.value!!.onApprove()

            // Host writeJsonFrameBounded succeeded (relay accepted bytes), yet Display never got HandshakeCommitFinal
            awaitCondition {
                kotlinx.coroutines.runBlocking {
                    hostStore.getPeer(dispId, TrustedPeerRole.HOST_SAVED_DISPLAY)?.pendingCommitFinal == true &&
                        displayEngine.sessionState.value.securityNotice?.contains("did not confirm", ignoreCase = true) == true
                }
            }
            assertTrue("Display must NOT save trust when HandshakeCommitFinal drops", dispStore.trustedPeersFlow.first().isEmpty())
            assertTrue(
                "Host must retain pendingCommitFinal=true when commit confirmation only reached socket buffer",
                hostStore.getPeer(dispId, TrustedPeerRole.HOST_SAVED_DISPLAY)?.pendingCommitFinal == true
            )

            hostEngine.disconnect("Reconnect test")
            displayEngine.disconnectSession("Reconnect test")
            Thread.sleep(50)

            // Session 2 direct reconnect: Host sees saved trust + pendingCommitFinal=true vs Display without trust
            val directPeer = peerViaRelay.copy(controlPort = dispPort, isTrusted = true)
            hostEngine.connectToPeer(directPeer, "Pixel Host")
            awaitCondition { hostEngine.pendingSasConfirmation.value != null && displayEngine.pendingTrust.value != null }

            val sas = hostEngine.pendingSasConfirmation.value!!
            assertTrue(
                "Expected lost-commit warning on Host SAS dialog, got: ${sas.mismatchWarning}",
                sas.mismatchWarning?.contains("Previous final confirmation did not reach Display") == true
            )
            sas.onRejectMismatch()
        } finally {
            relay.close()
            hostEngine.disconnect("Cleanup")
            displayEngine.stopAll()
        }
    }

    @Test
    fun clearedDisplayStorageTriggersResetMismatchWarningNotLostCommitCopy() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hostStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "cleared_disp_host"))
        val dispStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "cleared_disp_disp"))
        val dispId = "disp-cleared-1"

        // Pre-seed confirmed Host trust (pendingCommitFinal = false) while Display storage is empty
        hostStore.trustPeer(
            peerId = dispId,
            deviceName = "Cleared Tab 2",
            rawToken = "old-confirmed-token",
            peerRole = TrustedPeerRole.HOST_SAVED_DISPLAY,
            ip = "127.0.0.1",
            port = 57321,
            profile = null,
            pendingCommitFinal = false
        )

        val displayEngine = DisplaySessionEngine(context, dispStore, KeystoreCrypto(context, WorkingVault()))
        val hostEngine = com.example.host.HostSessionEngine(
            context,
            hostStore,
            com.example.host.ShizukuController(context),
            KeystoreCrypto(context, WorkingVault())
        )
        try {
            val port = displayEngine.startListening(testDisplayProfile(dispId))
            val peer = com.example.model.DiscoveredPeer(
                peerId = dispId,
                deviceName = "Cleared Tab 2",
                ipAddress = "127.0.0.1",
                controlPort = port,
                displaySummary = "1280x800",
                isTrusted = true,
                isOnline = true,
                transportMode = com.example.model.TransportMode.LOCAL_WIFI
            )
            hostEngine.connectToPeer(peer, "Pixel Host")
            awaitCondition { hostEngine.pendingSasConfirmation.value != null }
            val warning = hostEngine.pendingSasConfirmation.value!!.mismatchWarning
            assertNotNull(warning)
            assertTrue(warning!!.contains("Display no longer has your saved key"))
            assertFalse(warning.contains("Previous final confirmation"))
            hostEngine.pendingSasConfirmation.value!!.onRejectMismatch()
        } finally {
            hostEngine.disconnect("Cleanup")
            displayEngine.stopAll()
        }
    }

    @Test
    fun displayKeystoreFailureDuringCommitSendsErrorAndClearsFallbackAfterRecovery() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hostStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "ks_fail_host"))
        val dispId = "disp-ks-fail"

        // ToggleableVault passes pre-challenge isAvailable() check, then fails when writing trustPeer during commit
        val displayVault = ToggleableVault(available = true, failOnEncrypt = true)
        val displayCrypto = KeystoreCrypto(context, displayVault)
        val failingDispStore = TrustedPeerStore(context, displayCrypto, isolatedDataStore(context, "ks_fail_disp2"))

        val displayEngine = DisplaySessionEngine(context, failingDispStore, displayCrypto)
        val hostEngine = com.example.host.HostSessionEngine(
            context,
            hostStore,
            com.example.host.ShizukuController(context),
            KeystoreCrypto(context, WorkingVault())
        )
        val dispPort = displayEngine.startListening(testDisplayProfile(dispId))
        // Drop HandshakeCommitPersistError in flight to test next-session challenge fallback recovery too!
        val relay = ControlRelayProxy(
            targetPort = dispPort,
            dropDisplayToHost = { msg -> msg is ControlMessage.HandshakeCommitPersistError }
        )
        try {
            val peerRelay = com.example.model.DiscoveredPeer(
                peerId = dispId,
                deviceName = "Tab 2 KS",
                ipAddress = "127.0.0.1",
                controlPort = relay.port,
                displaySummary = "1280x800",
                isTrusted = false,
                isOnline = true,
                transportMode = com.example.model.TransportMode.LOCAL_WIFI
            )
            hostEngine.connectToPeer(peerRelay, "Pixel Host")
            awaitCondition { hostEngine.pendingSasConfirmation.value != null && displayEngine.pendingTrust.value != null }
            hostEngine.pendingSasConfirmation.value!!.onConfirmMatch()
            displayEngine.pendingTrust.value!!.onApprove()

            // Even if HandshakeCommitPersistError dropped, Display records sessionOnlyFallback = true
            awaitCondition {
                kotlinx.coroutines.runBlocking { failingDispStore.isSessionOnlyFallback() }
            }
            assertTrue(failingDispStore.trustedPeersFlow.first().isEmpty())

            hostEngine.disconnect("End session 1")
            displayEngine.disconnectSession("End session 1")
            Thread.sleep(50)

            // Keystore now recovers before Session 2!
            displayVault.failOnEncrypt = false

            val directPeer = peerRelay.copy(controlPort = dispPort, isTrusted = true)
            hostEngine.connectToPeer(directPeer, "Pixel Host")
            awaitCondition { hostEngine.pendingSasConfirmation.value != null && displayEngine.pendingTrust.value != null }
            val sas2 = hostEngine.pendingSasConfirmation.value!!
            // Because previousPersistFailed was true in HandshakeChallenge, Host cleared unbacked trust and warned user
            assertTrue(
                sas2.mismatchWarning?.contains("Display could not save Keystore trust") == true
            )
            sas2.onConfirmMatch()
            displayEngine.pendingTrust.value!!.onApprove()

            // Wait for recovery pairing to succeed and atomically clear sessionOnlyFallback under Mutex
            awaitCondition {
                kotlinx.coroutines.runBlocking {
                    !failingDispStore.isSessionOnlyFallback() &&
                        failingDispStore.trustedPeersFlow.first().isNotEmpty() &&
                        hostStore.getPeer(dispId, TrustedPeerRole.HOST_SAVED_DISPLAY)?.pendingCommitFinal == true
                }
            }

            hostEngine.disconnect("End session 2")
            displayEngine.disconnectSession("End session 2")
            Thread.sleep(50)

            // Session 3 reconnects silently via mutual HMAC (sessionOnly=false, previousPersistFailed=false)
            hostEngine.connectToPeer(directPeer, "Pixel Host")
            awaitCondition {
                kotlinx.coroutines.runBlocking {
                    hostStore.getPeer(dispId, TrustedPeerRole.HOST_SAVED_DISPLAY)?.pendingCommitFinal == false
                }
            }
            assertNull(hostEngine.pendingSasConfirmation.value)
        } finally {
            relay.close()
            hostEngine.disconnect("Cleanup")
            displayEngine.stopAll()
        }
    }

    @Test
    fun hostClearsPendingCommitFinalWhenCommitAckCommittedIsFalse() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hostStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "abort_commit_host"))
        val dispId = "disp-abort-1"

        // Pre-seed existing trust with pendingCommitFinal = true
        hostStore.trustPeer(
            peerId = dispId,
            deviceName = "Abort Tab 2",
            rawToken = "prior-token",
            peerRole = TrustedPeerRole.HOST_SAVED_DISPLAY,
            ip = "127.0.0.1",
            port = 57321,
            profile = null,
            pendingCommitFinal = true
        )

        val hostEngine = com.example.host.HostSessionEngine(
            context,
            hostStore,
            com.example.host.ShizukuController(context),
            KeystoreCrypto(context, WorkingVault())
        )
        val server = ServerSocket(0)
        val serverJob = Thread {
            server.accept().use { sock ->
                val inp = DataInputStream(sock.getInputStream())
                val out = DataOutputStream(sock.getOutputStream())
                val req = (ControlCodec.readNextFrame(inp) as ControlCodec.DecodedFrame.JsonControl)
                    .message as ControlMessage.HandshakeRequest
                val dispEcdh = PairingCrypto.generateEcdhKeyPair()
                val dispNonce = PairingCrypto.generateNonce()
                ControlCodec.writeJsonFrame(
                    out,
                    ControlMessage.HandshakeChallenge(
                        negotiatedProtocolVersion = 2,
                        peerId = dispId,
                        displayNonce = dispNonce,
                        requiresUserApproval = true,
                        displayEcdhPubKey = PairingCrypto.encodePublicKey(dispEcdh)
                    )
                )
                val auth = (ControlCodec.readNextFrame(inp) as ControlCodec.DecodedFrame.JsonControl)
                    .message as ControlMessage.HandshakeAuthResponse
                assertTrue(auth.sasConfirmed)
                val secret = PairingCrypto.deriveEcdhSharedSecret(dispEcdh.private, req.hostEcdhPubKey!!)
                val sealed = PairingCrypto.sealInitialToken(secret, req.hostNonce, dispNonce, "new-unused-token")
                ControlCodec.writeJsonFrame(
                    out,
                    ControlMessage.TrustApprovalResult(
                        approved = true,
                        sealedPairingToken = sealed.ciphertextB64,
                        sealedPairingTag = sealed.macTagB64,
                        profile = testDisplayProfile(dispId)
                    )
                )
                val ack = (ControlCodec.readNextFrame(inp) as ControlCodec.DecodedFrame.JsonControl).message
                assertTrue(ack is ControlMessage.HandshakeAck)
                // Abort commit explicitly!
                ControlCodec.writeJsonFrame(out, ControlMessage.HandshakeCommitAck(committed = false))
                val finalMsg = (ControlCodec.readNextFrame(inp) as ControlCodec.DecodedFrame.JsonControl)
                    .message as ControlMessage.HandshakeCommitFinal
                assertFalse(finalMsg.accepted)
            }
        }
        serverJob.start()
        try {
            hostEngine.connectToPeer(
                com.example.model.DiscoveredPeer(
                    peerId = dispId,
                    deviceName = "Abort Tab 2",
                    ipAddress = "127.0.0.1",
                    controlPort = server.localPort,
                    displaySummary = "1280x800",
                    isTrusted = true,
                    isOnline = true,
                    transportMode = com.example.model.TransportMode.LOCAL_WIFI
                ),
                "Pixel Host"
            )
            awaitCondition { hostEngine.pendingSasConfirmation.value != null }
            hostEngine.pendingSasConfirmation.value!!.onConfirmMatch()

            awaitCondition {
                kotlinx.coroutines.runBlocking {
                    hostStore.getPeer(dispId, TrustedPeerRole.HOST_SAVED_DISPLAY)?.pendingCommitFinal == false
                }
            }
            assertEquals("prior-token", hostStore.getDecryptedToken(dispId, TrustedPeerRole.HOST_SAVED_DISPLAY))
        } finally {
            hostEngine.disconnect("Cleanup")
            runCatching { server.close() }
            serverJob.join(2000)
        }
    }

    @Test
    fun displayEnforcesPerIpPreAuthCapAndReleasesSlotImmediatelyOnReject() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "dos_slot_disp"))
        val displayEngine = DisplaySessionEngine(context, dispStore, KeystoreCrypto(context, WorkingVault()))
        displayEngine.sasTimeoutOverrideMs = 30_000L
        val port = displayEngine.startListening(testDisplayProfile("disp-dos"))

        fun sendSasRequestSocket(): Socket {
            val s = Socket().apply {
                soTimeout = 3000
                connect(InetSocketAddress("127.0.0.1", port), 2000)
            }
            val out = DataOutputStream(s.getOutputStream())
            ControlCodec.writeJsonFrame(
                out,
                ControlMessage.HandshakeRequest(
                    protocolVersion = PairingCrypto.PROTOCOL_VERSION,
                    minSupportedProtocolVersion = PairingCrypto.MIN_PROTOCOL_VERSION,
                    hostId = "host-dos",
                    hostName = "Host",
                    hostNonce = PairingCrypto.generateNonce(),
                    hostEcdhPubKey = PairingCrypto.encodePublicKey(PairingCrypto.generateEcdhKeyPair()),
                    expectSavedTrust = false
                )
            )
            return s
        }

        val s1 = sendSasRequestSocket()
        var s2: Socket? = null
        var s3: Socket? = null
        try {
            val inp1 = DataInputStream(s1.getInputStream())
            val ch1 = (ControlCodec.readNextFrame(inp1) as ControlCodec.DecodedFrame.JsonControl).message
            assertTrue(ch1 is ControlMessage.HandshakeChallenge)
            awaitCondition { displayEngine.pendingTrust.value != null }

            // 2nd simultaneous socket from same 127.0.0.1 must be rejected immediately by MAX_PER_IP_PRE_AUTH = 1
            s2 = sendSasRequestSocket()
            val inp2 = DataInputStream(s2.getInputStream())
            val rej2 = (ControlCodec.readNextFrame(inp2) as ControlCodec.DecodedFrame.JsonControl)
                .message as ControlMessage.TrustApprovalResult
            assertFalse(rej2.approved)

            // User declines 1st prompt -> slot & queue must release immediately (not wait 30s)
            displayEngine.pendingTrust.value!!.onReject()
            awaitCondition {
                kotlinx.coroutines.runBlocking {
                    displayEngine.pendingTrust.value == null &&
                        displayEngine.preAuthCountsSnapshot().first == 0 &&
                        displayEngine.trustQueueSizeSnapshot() == 0
                }
            }

            // Immediate retry succeeds right away!
            s3 = sendSasRequestSocket()
            val inp3 = DataInputStream(s3.getInputStream())
            val ch3 = (ControlCodec.readNextFrame(inp3) as ControlCodec.DecodedFrame.JsonControl).message
            assertTrue(ch3 is ControlMessage.HandshakeChallenge)
            awaitCondition { displayEngine.pendingTrust.value != null }
        } finally {
            runCatching { s1.close() }
            runCatching { s2?.close() }
            runCatching { s3?.close() }
            displayEngine.stopAll()
        }
    }

    @Test
    fun concurrentTrustedPeerStoreMutationsAreSerializedWithoutLostUpdates() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "mutex_race_ds"))
        store.ensureSchemaV2Migrated()

        val jobs = (1..10).map { idx ->
            async(kotlinx.coroutines.Dispatchers.IO) {
                val id = "peer-$idx"
                store.trustPeer(
                    peerId = id,
                    deviceName = "Display $idx",
                    rawToken = "tok-$idx",
                    peerRole = TrustedPeerRole.HOST_SAVED_DISPLAY,
                    ip = "192.168.1.$idx",
                    port = 57321,
                    profile = null,
                    pendingCommitFinal = true
                )
                store.clearPendingCommitFinal(id, TrustedPeerRole.HOST_SAVED_DISPLAY)
            }
        }
        jobs.forEach { it.await() }

        val peers = store.trustedPeersFlow.first()
        assertEquals(10, peers.size)
        assertTrue(peers.none { it.pendingCommitFinal })
    }

    @Test
    fun displayEngineRejectsSecondHostWhileConnectedAndPreservesStandbyFgsOnDisconnect() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        val dispStore = TrustedPeerStore(app, KeystoreCrypto(app, WorkingVault()), isolatedDataStore(app, "busy_disp_ds"))
        val dispId = "disp-busy-1"
        val displayEngine = DisplaySessionEngine(app, dispStore, KeystoreCrypto(app, WorkingVault()))

        val port = displayEngine.startListening(testDisplayProfile(dispId))
        val primarySocket = Socket().apply {
            soTimeout = 3000
            connect(InetSocketAddress("127.0.0.1", port), 2000)
        }
        try {
            val out1 = DataOutputStream(primarySocket.getOutputStream())
            val inp1 = DataInputStream(primarySocket.getInputStream())
            ControlCodec.writeJsonFrame(
                out1,
                ControlMessage.HandshakeRequest(
                    protocolVersion = PairingCrypto.PROTOCOL_VERSION,
                    minSupportedProtocolVersion = PairingCrypto.MIN_PROTOCOL_VERSION,
                    hostId = "host-primary",
                    hostName = "Primary Host",
                    hostNonce = PairingCrypto.generateNonce(),
                    hostEcdhPubKey = PairingCrypto.encodePublicKey(PairingCrypto.generateEcdhKeyPair()),
                    expectSavedTrust = false
                )
            )
            val challenge = (ControlCodec.readNextFrame(inp1) as ControlCodec.DecodedFrame.JsonControl)
                .message as ControlMessage.HandshakeChallenge
            assertTrue(challenge.requiresUserApproval)
            awaitCondition { displayEngine.pendingTrust.value != null }
            displayEngine.pendingTrust.value!!.onApprove()
            ControlCodec.writeJsonFrame(out1, ControlMessage.HandshakeAuthResponse(sasConfirmed = true))

            val approval = (ControlCodec.readNextFrame(inp1) as ControlCodec.DecodedFrame.JsonControl)
                .message as ControlMessage.TrustApprovalResult
            assertTrue(approval.approved)

            ControlCodec.writeJsonFrame(
                out1,
                ControlMessage.HandshakeAck(
                    config = com.example.core.DisplaySynthesizer.synthesize(testDisplayProfile(dispId)),
                    videoPort = 49999,
                    desktopMode = "LAYOUT_PREVIEW"
                )
            )
            val commitAck = (ControlCodec.readNextFrame(inp1) as ControlCodec.DecodedFrame.JsonControl)
                .message as ControlMessage.HandshakeCommitAck
            assertTrue(commitAck.committed)
            ControlCodec.writeJsonFrame(out1, ControlMessage.HandshakeCommitFinal(accepted = true))

            awaitCondition { displayEngine.sessionState.value.isConnected }
            shadowApp.clearStartedServices()
            while (shadowApp.nextStoppedService != null) { /* drain prior state */ }

            // Second Host tries to connect while Session 1 is active -> must be rejected as busy
            Socket().use { secondSock ->
                secondSock.soTimeout = 3000
                secondSock.connect(InetSocketAddress("127.0.0.1", port), 2000)
                val out2 = DataOutputStream(secondSock.getOutputStream())
                val inp2 = DataInputStream(secondSock.getInputStream())
                ControlCodec.writeJsonFrame(
                    out2,
                    ControlMessage.HandshakeRequest(
                        protocolVersion = PairingCrypto.PROTOCOL_VERSION,
                        minSupportedProtocolVersion = PairingCrypto.MIN_PROTOCOL_VERSION,
                        hostId = "host-second",
                        hostName = "Intruder Host",
                        hostNonce = PairingCrypto.generateNonce(),
                        hostEcdhPubKey = PairingCrypto.encodePublicKey(PairingCrypto.generateEcdhKeyPair()),
                        expectSavedTrust = false
                    )
                )
                val rej = (ControlCodec.readNextFrame(inp2) as ControlCodec.DecodedFrame.JsonControl)
                    .message as ControlMessage.TrustApprovalResult
                assertFalse(rej.approved)
                assertTrue(rej.reason?.contains("Display busy", ignoreCase = true) == true)
            }

            // Primary session must remain undisturbed!
            assertTrue(displayEngine.sessionState.value.isConnected)
            assertEquals("Primary Host", displayEngine.sessionState.value.hostName)

            // Disconnecting active session updates notification to standby and MUST NOT call stopService
            displayEngine.disconnectSession("User disconnect")
            assertFalse(displayEngine.sessionState.value.isConnected)
            assertEquals(port, displayEngine.sessionState.value.listeningPort)
            assertNull("Standby FGS must stay alive on disconnectSession", shadowApp.nextStoppedService)

            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val posted = org.robolectric.Shadows.shadowOf(nm).allNotifications
            assertTrue(
                "Expected updated standby notification containing listening port $port",
                posted.any { n ->
                    n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
                        ?.toString()
                        ?.contains("listening on port $port") == true
                }
            )

            // Primary socket receives DisconnectMsg asynchronously
            var gotDisconnect = false
            val readDeadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < readDeadline) {
                val frame = runCatching { ControlCodec.readNextFrame(inp1) }.getOrNull() ?: break
                val msg = (frame as? ControlCodec.DecodedFrame.JsonControl)?.message
                if (msg is ControlMessage.DisconnectMsg) {
                    gotDisconnect = true
                    break
                }
            }
            assertTrue("Primary peer must receive DisconnectMsg", gotDisconnect)

            // Only stopAll stops the foreground service completely
            displayEngine.stopAll()
            val stopped = shadowApp.nextStoppedService
            assertNotNull("stopAll must stop ExtendedDesktopService", stopped)
        } finally {
            runCatching { primarySocket.close() }
            displayEngine.stopAll()
        }
    }

    @Test
    fun mainThreadDisconnectUnderStrictModeSendsDisconnectMsgAsyncWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hostStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "strict_host_ds"))
        val dispStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "strict_disp_ds"))

        val displayEngine = DisplaySessionEngine(context, dispStore, KeystoreCrypto(context, WorkingVault()))
        val hostEngine = com.example.host.HostSessionEngine(
            context,
            hostStore,
            com.example.host.ShizukuController(context),
            KeystoreCrypto(context, WorkingVault())
        )

        val receivedHostDisconnect = java.util.concurrent.CountDownLatch(1)
        val readyForHostDisconnect = java.util.concurrent.CountDownLatch(1)
        val server = ServerSocket(0)
        val fakeDisplayThread = Thread {
            server.accept().use { sock ->
                val inp = DataInputStream(sock.getInputStream())
                val out = DataOutputStream(sock.getOutputStream())
                val req = (ControlCodec.readNextFrame(inp) as ControlCodec.DecodedFrame.JsonControl)
                    .message as ControlMessage.HandshakeRequest
                val dispEcdh = PairingCrypto.generateEcdhKeyPair()
                val dispNonce = PairingCrypto.generateNonce()
                ControlCodec.writeJsonFrame(
                    out,
                    ControlMessage.HandshakeChallenge(
                        negotiatedProtocolVersion = 2,
                        peerId = "disp-strict",
                        displayNonce = dispNonce,
                        requiresUserApproval = true,
                        displayEcdhPubKey = PairingCrypto.encodePublicKey(dispEcdh)
                    )
                )
                ControlCodec.readNextFrame(inp) // auth
                val secret = PairingCrypto.deriveEcdhSharedSecret(dispEcdh.private, req.hostEcdhPubKey!!)
                val sealed = PairingCrypto.sealInitialToken(secret, req.hostNonce, dispNonce, "tok-strict")
                ControlCodec.writeJsonFrame(
                    out,
                    ControlMessage.TrustApprovalResult(
                        approved = true,
                        sealedPairingToken = sealed.ciphertextB64,
                        sealedPairingTag = sealed.macTagB64,
                        profile = testDisplayProfile("disp-strict")
                    )
                )
                ControlCodec.readNextFrame(inp) // HandshakeAck
                ControlCodec.writeJsonFrame(out, ControlMessage.HandshakeCommitAck(committed = true))
                ControlCodec.readNextFrame(inp) // HandshakeCommitFinal

                // At this point HostEngine holds live controlSocket/controlOut + videoServerSocket before vServer.accept() returns
                readyForHostDisconnect.countDown()
                while (!sock.isClosed) {
                    val frame = runCatching { ControlCodec.readNextFrame(inp) }.getOrNull() ?: break
                    val msg = (frame as? ControlCodec.DecodedFrame.JsonControl)?.message
                    if (msg is ControlMessage.DisconnectMsg) {
                        receivedHostDisconnect.countDown()
                        break
                    }
                }
            }
        }
        fakeDisplayThread.start()

        val dispPort = displayEngine.startListening(testDisplayProfile("disp-strict-2"))
        val fakeHostForDisplay = Socket().apply {
            soTimeout = 3000
            connect(InetSocketAddress("127.0.0.1", dispPort), 2000)
        }
        val receivedDispDisconnect = java.util.concurrent.CountDownLatch(1)

        val originalPolicy = android.os.StrictMode.getThreadPolicy()
        try {
            // 1) Bring HostEngine up to live open controlSocket/out + videoServerSocket
            hostEngine.connectToPeer(
                com.example.model.DiscoveredPeer(
                    peerId = "disp-strict",
                    deviceName = "Strict Tab 2",
                    ipAddress = "127.0.0.1",
                    controlPort = server.localPort,
                    displaySummary = "1280x800",
                    isTrusted = false,
                    isOnline = true,
                    transportMode = com.example.model.TransportMode.LOCAL_WIFI
                ),
                "Pixel Strict"
            )
            awaitCondition { hostEngine.pendingSasConfirmation.value != null }
            hostEngine.pendingSasConfirmation.value!!.onConfirmMatch()
            assertTrue(readyForHostDisconnect.await(4, java.util.concurrent.TimeUnit.SECONDS))

            // 2) Bring DisplaySessionEngine to active connected state
            val outD = DataOutputStream(fakeHostForDisplay.getOutputStream())
            val inpD = DataInputStream(fakeHostForDisplay.getInputStream())
            ControlCodec.writeJsonFrame(
                outD,
                ControlMessage.HandshakeRequest(
                    protocolVersion = PairingCrypto.PROTOCOL_VERSION,
                    minSupportedProtocolVersion = PairingCrypto.MIN_PROTOCOL_VERSION,
                    hostId = "host-strict-2",
                    hostName = "Strict Host",
                    hostNonce = PairingCrypto.generateNonce(),
                    hostEcdhPubKey = PairingCrypto.encodePublicKey(PairingCrypto.generateEcdhKeyPair()),
                    expectSavedTrust = false
                )
            )
            ControlCodec.readNextFrame(inpD)
            awaitCondition { displayEngine.pendingTrust.value != null }
            displayEngine.pendingTrust.value!!.onApprove()
            ControlCodec.writeJsonFrame(outD, ControlMessage.HandshakeAuthResponse(sasConfirmed = true))
            ControlCodec.readNextFrame(inpD)
            ControlCodec.writeJsonFrame(
                outD,
                ControlMessage.HandshakeAck(
                    config = com.example.core.DisplaySynthesizer.synthesize(testDisplayProfile("disp-strict-2")),
                    videoPort = 49998,
                    desktopMode = "LAYOUT_PREVIEW"
                )
            )
            ControlCodec.readNextFrame(inpD) // HandshakeCommitAck
            ControlCodec.writeJsonFrame(outD, ControlMessage.HandshakeCommitFinal(accepted = true))
            awaitCondition { displayEngine.sessionState.value.isConnected }

            Thread {
                while (!fakeHostForDisplay.isClosed) {
                    val frame = runCatching { ControlCodec.readNextFrame(inpD) }.getOrNull() ?: break
                    val msg = (frame as? ControlCodec.DecodedFrame.JsonControl)?.message
                    if (msg is ControlMessage.DisconnectMsg) {
                        receivedDispDisconnect.countDown()
                        break
                    }
                }
            }.apply { isDaemon = true }.start()

            // 3) Enforce StrictMode detectNetwork + penaltyDeath right on the calling Main thread
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .penaltyDeath()
                    .build()
            )
            hostEngine.disconnect("StrictMode UI disconnect")
            displayEngine.disconnectSession("StrictMode UI disconnect")

            assertTrue(
                "Host DisconnectMsg must be sent asynchronously on Dispatchers.IO",
                receivedHostDisconnect.await(3, java.util.concurrent.TimeUnit.SECONDS)
            )
            assertTrue(
                "Display DisconnectMsg must be sent asynchronously on Dispatchers.IO",
                receivedDispDisconnect.await(3, java.util.concurrent.TimeUnit.SECONDS)
            )
        } finally {
            android.os.StrictMode.setThreadPolicy(originalPolicy)
            hostEngine.disconnect("Cleanup")
            displayEngine.stopAll()
            runCatching { fakeHostForDisplay.close() }
            runCatching { server.close() }
            fakeDisplayThread.join(2000)
        }
    }

    @Test
    fun hostEngineMarkAppMovedBackToHostUpdatesActiveDesktopAppsAndAdvisory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hostStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "apps_host_ds"))
        val hostEngine = com.example.host.HostSessionEngine(
            context,
            hostStore,
            com.example.host.ShizukuController(context),
            KeystoreCrypto(context, WorkingVault())
        )
        try {
            hostEngine.seedActiveDesktopAppsForTest(
                pkgs = setOf("com.example.app1", "com.example.app2"),
                connected = true
            )
            assertEquals(2, hostEngine.telemetry.value.activeDesktopApps.size)
            assertEquals(2, hostEngine.telemetry.value.advisory.displayAppsCount)

            hostEngine.markAppMovedBackToHost("com.example.app1")
            assertEquals(setOf("com.example.app2"), hostEngine.telemetry.value.activeDesktopApps)
            assertEquals(1, hostEngine.telemetry.value.advisory.displayAppsCount)

            hostEngine.markAppMovedBackToHost("com.example.app2")
            assertTrue(hostEngine.telemetry.value.activeDesktopApps.isEmpty())
            assertEquals(0, hostEngine.telemetry.value.advisory.displayAppsCount)
        } finally {
            hostEngine.disconnect("Cleanup")
        }
    }

    @Test
    fun displaySessionEngineSurfacesSwFallbackAndFatalDecoderErrorToState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispStore = TrustedPeerStore(context, KeystoreCrypto(context, WorkingVault()), isolatedDataStore(context, "decoder_err_disp_ds"))
        val displayEngine = DisplaySessionEngine(context, dispStore, KeystoreCrypto(context, WorkingVault()))
        try {
            assertEquals(1_000L, com.example.host.DesktopShellActivity.CLOCK_TICK_INTERVAL_MS)

            displayEngine.handleDecoderStatus(
                com.example.display.H264StreamDecoder.DecoderStatus.SoftwareFallbackActive(
                    codecName = "OMX.google.h264.decoder",
                    hwFailureReason = "High profile unsupported"
                )
            )
            assertEquals(
                com.example.model.DisplayStreamOverlayState.Active,
                displayEngine.sessionState.value.overlayState
            )
            assertTrue(
                displayEngine.sessionState.value.decoderNotice?.contains("SW H.264 (OMX.google.h264.decoder)") == true
            )

            displayEngine.handleDecoderStatus(
                com.example.display.H264StreamDecoder.DecoderStatus.FatalError("H.264 decode failed (HW + SW)")
            )
            val overlay = displayEngine.sessionState.value.overlayState
            assertTrue(overlay is com.example.model.DisplayStreamOverlayState.DecoderError)
            assertEquals(
                "H.264 decode failed (HW + SW)",
                (overlay as com.example.model.DisplayStreamOverlayState.DecoderError).message
            )

            displayEngine.handleDecoderStatus(
                com.example.display.H264StreamDecoder.DecoderStatus.HardwareActive("OMX.qcom.video.decoder.avc")
            )
            assertEquals(
                com.example.model.DisplayStreamOverlayState.Active,
                displayEngine.sessionState.value.overlayState
            )
            assertNull(displayEngine.sessionState.value.decoderNotice)

            // Detaching null surface stops cleanly even when idle
            displayEngine.attachSurface(null)
            displayEngine.disconnectSession("Test reset")
            assertNull(displayEngine.sessionState.value.decoderNotice)
        } finally {
            displayEngine.stopAll()
        }
    }

    @Test
    fun workstationViewModelOnClearedPreservesServiceOwnedRuntimeAndDesktopState() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val runtime = com.example.service.WorkstationRuntime.getInstance(app)
        try {
            runtime.hostEngine.seedActiveDesktopAppsForTest(
                pkgs = setOf("com.example.keepalive"),
                connected = true
            )
            val store = androidx.lifecycle.ViewModelStore()
            val factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
            val vm = androidx.lifecycle.ViewModelProvider(store, factory)[com.example.ui.WorkstationViewModel::class.java]
            assertTrue(runtime.hostEngine === vm.hostEngine)

            // Closing MainActivity destroys ViewModelStore -> calls onCleared()
            store.clear()

            // Live session & active apps remain intact inside service-backed WorkstationRuntime
            assertTrue(runtime.hostEngine.telemetry.value.connected)
            assertTrue(runtime.hostEngine.telemetry.value.activeDesktopApps.contains("com.example.keepalive"))

            // Explicit Stop from notification DOES disconnect the session cleanly
            runtime.stopAllFromServiceNotification()
            assertFalse(runtime.hostEngine.telemetry.value.connected)
        } finally {
            runtime.stopAllFromServiceNotification()
        }
    }

    @Test
    fun desktopShellBridgeLaunchVerificationDetectsDisplayMismatchAndExceptions() {
        var rejectedExpected: Int? = null
        var rejectedActual: Int? = -99
        val cfg = com.example.model.VirtualDisplayConfig(
            targetWidth = 1280,
            targetHeight = 800,
            targetDpi = 160,
            refreshRateFps = 60,
            bitrateMbps = 4,
            forceTabletLayout = true,
            resolutionCeiling = com.example.model.ResolutionCeiling.P800,
            is24GhzAdapted = true
        )
        try {
            com.example.host.DesktopShellBridge.prepareSession(
                expectedDisplayId = 3,
                config = cfg,
                modeLabel = "SHIZUKU FULL",
                shizukuAuthorized = true,
                apps = emptyList(),
                diagnosticOnly = false,
                shellCallbacks = object : com.example.host.DesktopShellBridge.ShellCallbacks {
                    override fun onLaunchAppFromTaskbar(packageName: String) = Unit
                    override fun onWorkspaceLayerChanged(webLayerActive: Boolean) = Unit
                    override fun onDrawerVisibilityChanged(visible: Boolean) = Unit
                    override fun onShellDisplayVerified(displayId: Int) = Unit
                    override fun onShellDisplayRejected(expectedDisplayId: Int, actualDisplayId: Int?) {
                        rejectedExpected = expectedDisplayId
                        rejectedActual = actualDisplayId
                    }
                    override fun onVerificationTouchObserved(event: com.example.host.ObservedTouchEvent) = Unit
                }
            )
            assertTrue(com.example.host.DesktopShellBridge.shizukuAuthorized)
            assertFalse(com.example.host.DesktopShellBridge.webLayerActive)

            com.example.host.DesktopShellBridge.reportDisplayMismatch(expectedDisplayId = 3, actualDisplayId = 0)
            assertNull(com.example.host.DesktopShellBridge.verifiedDisplayId)
            assertEquals(3, rejectedExpected)
            assertEquals(0, rejectedActual)
            assertTrue(com.example.host.DesktopShellBridge.launchStatusNote.contains("Display 3"))

            com.example.host.DesktopShellBridge.reportLaunchException(3, SecurityException("blocked display"))
            assertNull(rejectedActual)
            assertTrue(com.example.host.DesktopShellBridge.launchStatusNote.contains("blocked display"))
        } finally {
            com.example.host.DesktopShellBridge.releaseAndCloseShell()
        }
    }
}
