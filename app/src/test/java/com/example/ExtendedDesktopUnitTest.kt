package com.example

import com.example.core.DisplaySynthesizer
import com.example.host.HostPerformanceAdvisor
import com.example.host.ShizukuInputBridge
import com.example.model.DisplayProfile
import com.example.model.InputBackendStatus
import com.example.model.LimitTier
import com.example.model.ResolutionCeiling
import com.example.network.QrPairingCodec
import com.example.network.QrPairingPayload
import com.example.protocol.ControlCodec
import com.example.protocol.ControlMessage
import com.example.protocol.PairingCrypto
import com.example.protocol.TouchSample
import com.example.protocol.TouchSamplePool
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ExtendedDesktopUnitTest {

    @Test
    fun galaxyTab2ProfileSynthesizesZeroLetterbox800pTabletMode() {
        val tab2Profile = DisplayProfile(
            deviceId = "tab2-01",
            deviceName = "Samsung GT-P5110",
            sdkInt = 22,
            widthPixels = 1280,
            heightPixels = 800,
            densityDpi = 160,
            xdpi = 149.4f,
            ydpi = 149.4f,
            aspectRatioLabel = "16:10",
            aspectRatioValue = 1.6f,
            physicalDiagonalInches = 10.1f,
            smallestWidthDp = 800,
            supports5GhzWifi = false,
            isTabletClass = true
        )

        val defaultCeiling = DisplaySynthesizer.defaultCeilingForProfile(tab2Profile)
        assertEquals(ResolutionCeiling.P800, defaultCeiling)

        val config = DisplaySynthesizer.synthesize(tab2Profile)
        assertEquals(1280, config.targetWidth)
        assertEquals(800, config.targetHeight)
        assertEquals(160, config.targetDpi)
        assertTrue(config.forceTabletLayout)
        assertTrue(config.is24GhzAdapted)
        assertEquals(4, config.bitrateMbps)
    }

    @Test
    fun binaryTouchPacketIsExactly20BytesAndRoundTrips() {
        val sample = TouchSample(
            packetType = TouchSample.PACKET_TYPE_TOUCH,
            action = TouchSample.ACTION_MOVE,
            pointerId = 2,
            normX = 0.725f,
            normY = 0.3125f,
            scrollDelta = -1.5f,
            displayTimestampOffsetMs = 4219
        )
        val buffer = ByteBuffer.allocate(TouchSample.PACKET_BYTES)
        sample.writeToBuffer(buffer)

        assertEquals(20, buffer.remaining())
        val bytes = ByteArray(TouchSample.PACKET_BYTES)
        buffer.get(bytes)

        val decoded = TouchSample()
        assertTrue(TouchSample.readFromBytes(bytes, decoded))
        assertEquals(sample.packetType, decoded.packetType)
        assertEquals(sample.action, decoded.action)
        assertEquals(sample.pointerId, decoded.pointerId)
        assertEquals(sample.normX, decoded.normX, 0.0001f)
        assertEquals(sample.normY, decoded.normY, 0.0001f)
        assertEquals(sample.scrollDelta, decoded.scrollDelta, 0.0001f)
        assertEquals(sample.displayTimestampOffsetMs, decoded.displayTimestampOffsetMs)
    }

    @Test
    fun touchSamplePoolReusesInstances() {
        val pool = TouchSamplePool(4)
        val first = pool.acquire()
        pool.release(first)
        val second = pool.acquire()
        assertSame(first, second)
    }

    @Test
    fun qrPayloadRoundTripsCleanly() {
        val payload = QrPairingPayload(
            peerId = "abc123",
            deviceName = "Galaxy Tab 2",
            ipAddress = "192.168.1.44",
            controlPort = 57321,
            displaySummary = "1280x800 • 16:10 • 10.1\""
        )
        val raw = QrPairingCodec.encodePayload(payload)
        val decoded = QrPairingCodec.decodePayload(raw)
        assertNotNull(decoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun performanceAdvisorEvaluatesTiersAndNotificationLines() {
        val light = HostPerformanceAdvisor.evaluate(
            displayAppsCount = 2,
            shizukuAuthorized = true,
            freeRamPercent = 62,
            thermalStatus = 0,
            batteryPercent = 80,
            isCharging = false
        )
        assertEquals(LimitTier.LIGHT, light.suggestedLimitTier)
        assertEquals("Display apps: 2 running", light.notificationLine1())
        assertEquals(
            "Suggested limit: 3 (Light) · RAM 62% free · Thermal normal",
            light.notificationLine2()
        )
        assertEquals(false, light.showCloseAppWarning)

        val previewOnly = HostPerformanceAdvisor.evaluate(
            displayAppsCount = 1,
            shizukuAuthorized = false,
            freeRamPercent = 40,
            thermalStatus = 1,
            batteryPercent = 50,
            isCharging = false
        )
        assertEquals(LimitTier.COMFORTABLE, previewOnly.suggestedLimitTier)
        assertEquals("Display apps: 1 running (launched here)", previewOnly.notificationLine1())
        assertEquals(false, previewOnly.showCloseAppWarning)

        val hotOrLowRam = HostPerformanceAdvisor.evaluate(
            displayAppsCount = 3,
            shizukuAuthorized = true,
            freeRamPercent = 22,
            thermalStatus = 3,
            batteryPercent = 45,
            isCharging = false
        )
        assertEquals(LimitTier.AT_LIMIT, hotOrLowRam.suggestedLimitTier)
        assertTrue(hotOrLowRam.showCloseAppWarning)
    }

    @Test
    fun protocolVersionNegotiationRejectsV1AndNamesOutdatedDevice() {
        // v2 <-> v2 succeeds cleanly
        val ok = PairingCrypto.negotiateForDisplay(
            reqProtocolVersion = 2,
            reqMinSupported = 2,
            hostName = "Pixel 9"
        )
        assertEquals(PairingCrypto.ProtocolNegotiation.Agreed(2), ok)

        // Legacy v1 Host is rejected with explicit Host upgrade prompt
        val v1Host = PairingCrypto.negotiateForDisplay(
            reqProtocolVersion = 1,
            reqMinSupported = 1,
            hostName = "OldHost"
        )
        assertTrue(v1Host is PairingCrypto.ProtocolNegotiation.Incompatible)
        assertTrue((v1Host as PairingCrypto.ProtocolNegotiation.Incompatible).reason.contains("Host"))

        // Legacy v1 Display is rejected with explicit Display upgrade prompt
        val v1Display = PairingCrypto.negotiateForHost(
            negotiatedVersion = 1,
            displayName = "Galaxy Tab 2",
            rejectionReason = null
        )
        assertTrue(v1Display is PairingCrypto.ProtocolNegotiation.Incompatible)
        assertTrue((v1Display as PairingCrypto.ProtocolNegotiation.Incompatible).reason.contains("Display"))

        // Future Host requiring v3+ instructs updating Display
        val futureHost = PairingCrypto.negotiateForDisplay(
            reqProtocolVersion = 3,
            reqMinSupported = 3,
            hostName = "FutureHost"
        )
        assertTrue(futureHost is PairingCrypto.ProtocolNegotiation.Incompatible)
        assertTrue((futureHost as PairingCrypto.ProtocolNegotiation.Incompatible).reason.contains("Display"))
    }

    @Test
    fun mutualNonceHmacChallengeResponseSucceedsAndNeverTransmitsRawToken() {
        val sharedToken = "secret-pairing-token-never-on-wire"
        val hostId = "host-01"
        val displayId = "tab2-01"
        val hostNonce = PairingCrypto.generateNonce()
        val displayNonce = PairingCrypto.generateNonce()

        val req = ControlMessage.HandshakeRequest(
            protocolVersion = PairingCrypto.PROTOCOL_VERSION,
            minSupportedProtocolVersion = PairingCrypto.MIN_PROTOCOL_VERSION,
            hostId = hostId,
            hostName = "Pixel 8",
            hostNonce = hostNonce,
            expectSavedTrust = true,
            transportMode = "WIFI_DIRECT"
        )
        val displayProof = PairingCrypto.computeDisplayProofHmac(
            sharedToken = sharedToken,
            negotiatedVersion = 2,
            hostNonce = hostNonce,
            displayNonce = displayNonce,
            hostId = hostId,
            displayPeerId = displayId
        )
        val challenge = ControlMessage.HandshakeChallenge(
            negotiatedProtocolVersion = 2,
            peerId = displayId,
            displayNonce = displayNonce,
            displayProofHmac = displayProof,
            requiresUserApproval = false
        )
        val hostProof = PairingCrypto.computeHostProofHmac(
            sharedToken = sharedToken,
            negotiatedVersion = 2,
            displayNonce = displayNonce,
            hostNonce = hostNonce,
            hostId = hostId,
            displayPeerId = displayId
        )
        val authResp = ControlMessage.HandshakeAuthResponse(
            hostProofHmac = hostProof,
            sasConfirmed = false
        )

        val wireJson = ControlCodec.json.encodeToString<ControlMessage>(req) +
            ControlCodec.json.encodeToString<ControlMessage>(challenge) +
            ControlCodec.json.encodeToString<ControlMessage>(authResp)

        assertFalse("Raw token must never cross wire", wireJson.contains(sharedToken))
        assertTrue(
            PairingCrypto.verifyDisplayProofHmac(
                sharedToken = sharedToken,
                candidateHmac = challenge.displayProofHmac,
                negotiatedVersion = 2,
                hostNonce = hostNonce,
                displayNonce = displayNonce,
                hostId = hostId,
                displayPeerId = displayId
            )
        )
        assertTrue(
            PairingCrypto.verifyHostProofHmac(
                sharedToken = sharedToken,
                candidateHmac = authResp.hostProofHmac,
                negotiatedVersion = 2,
                displayNonce = displayNonce,
                hostNonce = hostNonce,
                hostId = hostId,
                displayPeerId = displayId
            )
        )
    }

    @Test
    fun spoofedResponderAdvertisingSavedPeerIdWithoutTokenIsRejected() {
        val realToken = PairingCrypto.generateToken()
        val attackerFakeToken = PairingCrypto.generateToken()
        val hostId = "host-pixel"
        val victimDisplayId = "tab2-saved-peer"
        val hostNonce = PairingCrypto.generateNonce()
        val attackerNonce = PairingCrypto.generateNonce()

        // Attacker advertises victimDisplayId but signs challenge with wrong/fake token or leaves null
        val spoofedProof = PairingCrypto.computeDisplayProofHmac(
            sharedToken = attackerFakeToken,
            negotiatedVersion = 2,
            hostNonce = hostNonce,
            displayNonce = attackerNonce,
            hostId = hostId,
            displayPeerId = victimDisplayId
        )
        assertFalse(
            PairingCrypto.verifyDisplayProofHmac(
                sharedToken = realToken,
                candidateHmac = spoofedProof,
                negotiatedVersion = 2,
                hostNonce = hostNonce,
                displayNonce = attackerNonce,
                hostId = hostId,
                displayPeerId = victimDisplayId
            )
        )
        assertFalse(
            PairingCrypto.verifyDisplayProofHmac(
                sharedToken = realToken,
                candidateHmac = null,
                negotiatedVersion = 2,
                hostNonce = hostNonce,
                displayNonce = attackerNonce,
                hostId = hostId,
                displayPeerId = victimDisplayId
            )
        )
    }

    @Test
    fun ecdhMitmDifferentKeyProducesMismatchedSasCodeAndSealsNothing() {
        val hostKey = PairingCrypto.generateEcdhKeyPair()
        val displayKey = PairingCrypto.generateEcdhKeyPair()
        val mitmHostFacingKey = PairingCrypto.generateEcdhKeyPair()
        val mitmDisplayFacingKey = PairingCrypto.generateEcdhKeyPair()

        val hostPubB64 = PairingCrypto.encodePublicKey(hostKey)
        val displayPubB64 = PairingCrypto.encodePublicKey(displayKey)
        val mitmHostPubB64 = PairingCrypto.encodePublicKey(mitmHostFacingKey)
        val mitmDisplayPubB64 = PairingCrypto.encodePublicKey(mitmDisplayFacingKey)

        val hostNonce = PairingCrypto.generateNonce()
        val displayNonce = PairingCrypto.generateNonce()

        // Host thinks it paired with display via mitmDisplayPubB64
        val hostSecret = PairingCrypto.deriveEcdhSharedSecret(hostKey.private, mitmDisplayPubB64)
        val hostSas = PairingCrypto.deriveSasCode(
            ecdhSharedSecret = hostSecret,
            negotiatedVersion = 2,
            hostNonce = hostNonce,
            displayNonce = displayNonce,
            hostPubKeyB64 = hostPubB64,
            displayPubKeyB64 = mitmDisplayPubB64
        )

        // Display thinks it paired with host via mitmHostPubB64
        val displaySecret = PairingCrypto.deriveEcdhSharedSecret(displayKey.private, mitmHostPubB64)
        val displaySas = PairingCrypto.deriveSasCode(
            ecdhSharedSecret = displaySecret,
            negotiatedVersion = 2,
            hostNonce = hostNonce,
            displayNonce = displayNonce,
            hostPubKeyB64 = mitmHostPubB64,
            displayPubKeyB64 = displayPubB64
        )

        assertTrue(hostSas.matches(Regex("\\d{3}-\\d{3}")))
        assertTrue(displaySas.matches(Regex("\\d{3}-\\d{3}")))
        assertNotEquals("MITM key substitution must yield distinct SAS codes", hostSas, displaySas)

        // Honest direct ECDH yields matching 6-digit SAS code and unseals token identically
        val directHostSecret = PairingCrypto.deriveEcdhSharedSecret(hostKey.private, displayPubB64)
        val directDisplaySecret = PairingCrypto.deriveEcdhSharedSecret(displayKey.private, hostPubB64)
        val directHostSas = PairingCrypto.deriveSasCode(
            directHostSecret, 2, hostNonce, displayNonce, hostPubB64, displayPubB64
        )
        val directDisplaySas = PairingCrypto.deriveSasCode(
            directDisplaySecret, 2, hostNonce, displayNonce, hostPubB64, displayPubB64
        )
        assertEquals(directHostSas, directDisplaySas)

        val rawToken = PairingCrypto.generateToken()
        val sealed = PairingCrypto.sealInitialToken(directDisplaySecret, hostNonce, displayNonce, rawToken)
        assertEquals(
            rawToken,
            PairingCrypto.unsealInitialToken(directHostSecret, hostNonce, displayNonce, sealed.ciphertextB64, sealed.macTagB64)
        )
        // Attacker/MITM secret cannot unseal genuine token
        assertNull(
            PairingCrypto.unsealInitialToken(hostSecret, hostNonce, displayNonce, sealed.ciphertextB64, sealed.macTagB64)
        )
    }

    @Test
    fun shizukuInputBridgeSelectsPerOsBackendAcrossAndroid10To15() {
        // Android 10 (29) through 15 (35) prefer IInputManager Binder when available
        for (sdk in 29..35) {
            assertEquals(
                InputBackendStatus.BINDER_IINPUTMANAGER,
                ShizukuInputBridge.selectBackendForCapabilities(
                    sdkInt = sdk,
                    binderInjectionSupported = true,
                    shellDFlagSupported = true
                )
            )
            // Fall back to `input -d` only if verified on that ROM
            assertEquals(
                InputBackendStatus.SHELL_INPUT_D,
                ShizukuInputBridge.selectBackendForCapabilities(
                    sdkInt = sdk,
                    binderInjectionSupported = false,
                    shellDFlagSupported = true
                )
            )
            // Otherwise degrade cleanly to cursor preview only
            assertEquals(
                InputBackendStatus.PREVIEW_CURSOR_ONLY,
                ShizukuInputBridge.selectBackendForCapabilities(
                    sdkInt = sdk,
                    binderInjectionSupported = false,
                    shellDFlagSupported = false
                )
            )
        }
        // Pre-API 29 devices cannot act as Shizuku input injectors
        assertEquals(
            InputBackendStatus.PREVIEW_CURSOR_ONLY,
            ShizukuInputBridge.selectBackendForCapabilities(
                sdkInt = 22,
                binderInjectionSupported = true,
                shellDFlagSupported = true
            )
        )
    }

    @Test
    fun shizukuInputBridgePlansCoarseReleaseSwipeWithoutMoveSubprocesses() {
        val bridge = ShizukuInputBridge()
        // Short tap emits Tap on ACTION_UP only
        assertEquals(ShizukuInputBridge.ShellTouchPlan.None, bridge.planShellTouchDispatch(TouchSample.ACTION_DOWN, 100f, 100f))
        assertEquals(ShizukuInputBridge.ShellTouchPlan.None, bridge.planShellTouchDispatch(TouchSample.ACTION_MOVE, 104f, 104f))
        assertEquals(
            ShizukuInputBridge.ShellTouchPlan.Tap(104, 104),
            bridge.planShellTouchDispatch(TouchSample.ACTION_UP, 104f, 104f)
        )

        // Long drag: 50 ACTION_MOVE events produce zero subprocess commands; 1 coarse swipe on ACTION_UP
        assertEquals(ShizukuInputBridge.ShellTouchPlan.None, bridge.planShellTouchDispatch(TouchSample.ACTION_DOWN, 200f, 200f))
        for (step in 1..50) {
            assertEquals(
                ShizukuInputBridge.ShellTouchPlan.None,
                bridge.planShellTouchDispatch(TouchSample.ACTION_MOVE, 200f + step * 3f, 200f + step * 2f)
            )
        }
        assertEquals(
            ShizukuInputBridge.ShellTouchPlan.CoarseReleaseSwipe(200, 200, 350, 300),
            bridge.planShellTouchDispatch(TouchSample.ACTION_UP, 350f, 300f)
        )
    }

    @Test
    fun pairingCryptoDefinesConsistentCommitWindowAndClampsSasTimeout() {
        assertEquals(3_000L, PairingCrypto.PRE_AUTH_IO_TIMEOUT_MS)
        assertEquals(3_000L, PairingCrypto.COMMIT_WINDOW_MS)
        assertEquals(120_000L, PairingCrypto.DEFAULT_SAS_TIMEOUT_MS)
        assertEquals(PairingCrypto.MIN_SAS_TIMEOUT_MS, PairingCrypto.clampSasTimeoutMs(100L))
        assertEquals(PairingCrypto.MAX_SAS_TIMEOUT_MS, PairingCrypto.clampSasTimeoutMs(999_000L))
        assertEquals(45_000L, PairingCrypto.clampSasTimeoutMs(45_000L))
    }

    @Test
    fun hardwareVerificationRunnerDerivesRuntimeToleranceAndLogsNearestStrayDisplay0() {
        // Small target 36x36px -> radius=18px, tol=9px (not hardcoded ±12px)
        val smallPass = com.example.host.HardwareVerificationRunner.evaluateInputReceiptGate(
            targetWidthPx = 36,
            targetHeightPx = 36,
            injectedXPx = 18f,
            injectedYPx = 18f,
            tInjectMs = 10_000L,
            baselineDisplay0Events = emptyList(),
            virtualDisplayEvents = listOf(com.example.host.ObservedTouchEvent(1, 21f, 22f, 10_120L)),
            display0Events = listOf(com.example.host.ObservedTouchEvent(0, 60f, 18f, 10_200L))
        )
        assertEquals(18f, smallPass.targetRadiusPx, 0.01f)
        assertEquals(9f, smallPass.matchTolerancePx, 0.01f)
        assertTrue(smallPass.passed)
        assertNotNull(smallPass.nearestStrayDisplay0)
        assertEquals(42f, smallPass.nearestStrayDisplay0!!.distancePx, 0.01f)
        assertEquals(200L, smallPass.nearestStrayDisplay0!!.deltaMs)

        // Stray displayId 0 leak within tol fails even if VirtualDisplay also received it
        val leaked = com.example.host.HardwareVerificationRunner.evaluateInputReceiptGate(
            targetWidthPx = 36,
            targetHeightPx = 36,
            injectedXPx = 18f,
            injectedYPx = 18f,
            tInjectMs = 10_000L,
            baselineDisplay0Events = emptyList(),
            virtualDisplayEvents = listOf(com.example.host.ObservedTouchEvent(1, 18f, 18f, 10_050L)),
            display0Events = listOf(com.example.host.ObservedTouchEvent(0, 20f, 19f, 10_060L))
        )
        assertFalse(leaked.passed)
        assertTrue(leaked.display0LeakDetected)

        // Tiny target 4x4px clamps minimum tolerance to 4px
        assertEquals(4f, com.example.host.HardwareVerificationRunner.computeMatchTolerancePx(2f), 0.01f)
    }

    @Test
    fun writeJsonFrameBoundedTimesOutAndClosesStalledSocket() {
        val closedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val blockLatch = java.util.concurrent.CountDownLatch(1)
        val stalledOutStream = object : java.io.OutputStream() {
            override fun write(b: Int) {
                blockLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                throw java.io.IOException("Socket closed on timeout")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                blockLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                throw java.io.IOException("Socket closed on timeout")
            }
        }
        val fakeSocket = object : java.net.Socket() {
            override fun close() {
                closedFlag.set(true)
                blockLatch.countDown()
            }

            override fun isClosed(): Boolean = closedFlag.get()
        }
        val dos = java.io.DataOutputStream(stalledOutStream)
        val ok = ControlCodec.writeJsonFrameBounded(
            socket = fakeSocket,
            out = dos,
            message = ControlMessage.HandshakeCommitFinal(accepted = true),
            timeoutMs = 120L
        )
        assertFalse(ok)
        assertTrue(closedFlag.get())
    }

    @Test
    fun boundedShellExecutorKillsHungCommandWithinTimeoutAndDrainsMergedOutput() {
        // 1) Hung command must be forcibly terminated shortly after timeoutMs, never blocking for full duration
        val t0 = System.currentTimeMillis()
        val hungResult = com.example.host.BoundedShellExecutor.exec(
            arrayOf("sh", "-c", "sleep 10"),
            timeoutMs = 250L
        )
        val elapsedMs = System.currentTimeMillis() - t0
        assertEquals(-2, hungResult.exitCode)
        assertTrue("Hung process should terminate well under 2.5s, took ${elapsedMs}ms", elapsedMs < 2_500L)

        // 2) High-volume stdout + stderr (>128 KB) must drain concurrently without pipe buffer deadlock
        val noisyResult = com.example.host.BoundedShellExecutor.exec(
            arrayOf(
                "sh",
                "-c",
                "i=0; while [ \$i -lt 3000 ]; do echo \"STDOUT_LINE_\$i\"; echo \"STDERR_LINE_\$i\" 1>&2; i=\$((i+1)); done"
            ),
            timeoutMs = 5_000L
        )
        assertEquals(0, noisyResult.exitCode)
        assertTrue(noisyResult.stdout.contains("STDOUT_LINE_2999"))
        assertTrue(noisyResult.stdout.contains("STDERR_LINE_2999"))
    }

    @Test
    fun h264StreamEncoderSelectBitrateModeFallsBackSafelyWhenCbrUnsupported() {
        assertEquals(
            android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            com.example.host.H264StreamEncoder.selectBitrateMode(supportsCbr = true, supportsVbr = true)
        )
        assertEquals(
            android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            com.example.host.H264StreamEncoder.selectBitrateMode(supportsCbr = false, supportsVbr = true)
        )
        assertNull(
            com.example.host.H264StreamEncoder.selectBitrateMode(supportsCbr = false, supportsVbr = false)
        )
    }

    @Test
    fun hardwareVerificationRunnerFailsWhenNoRealVirtualDisplayEventArrives() {
        val eval = com.example.host.HardwareVerificationRunner.evaluateInputReceiptGate(
            targetWidthPx = 144,
            targetHeightPx = 144,
            injectedXPx = 400f,
            injectedYPx = 300f,
            tInjectMs = 20_000L,
            baselineDisplay0Events = emptyList(),
            virtualDisplayEvents = emptyList(),
            display0Events = emptyList()
        )
        assertFalse(eval.passed)
        assertFalse(eval.virtualDisplayMatched)
        assertTrue(eval.summaryLine.contains("vdHit=false"))
    }
}
