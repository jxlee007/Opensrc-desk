package com.example.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DisplayProfile
import com.example.model.TrustedPeer
import com.example.model.TrustedPeerRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

private val Context.workstationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "extended_desktop_prefs"
)

class TrustedPeerStore(
    private val context: Context,
    private val crypto: KeystoreCrypto,
    private val dataStoreOverride: DataStore<Preferences>? = null
) {
    private val store: DataStore<Preferences>
        get() = dataStoreOverride ?: context.workstationDataStore

    private val mutationMutex = Mutex()

    data class DisplayPreChallengeGate(
        val keystoreAvailable: Boolean,
        val sessionOnly: Boolean,
        val previousPersistFailed: Boolean
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    val trustedPeersFlow: Flow<List<TrustedPeer>> = store.data.map { prefs ->
        val schemaVersion = prefs[KEY_SCHEMA_VERSION] ?: 1
        val raw = prefs[KEY_TRUSTED_PEERS] ?: return@map emptyList()
        if (schemaVersion < SCHEMA_VERSION) {
            return@map emptyList()
        }
        decodeAndNormalizePeers(raw)
    }

    val upgradeNoticePendingFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_UPGRADE_NOTICE_PENDING] ?: false
    }

    val cachedProfileFlow: Flow<DisplayProfile?> = store.data.map { prefs ->
        val raw = prefs[KEY_DISPLAY_PROFILE] ?: prefs[KEY_LEGACY_CLIENT_PROFILE] ?: return@map null
        try {
            json.decodeFromString<DisplayProfile>(raw)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Schema v1 -> v2 clean upgrade:
     * Because devices using the testing role-override can contain untagged mixed Host/Display v1 records,
     * pre-v2 records lacking explicit peerRole are cleared safely with Keystore rotation and a visible notice banner.
     */
    suspend fun ensureSchemaV2Migrated() = mutationMutex.withLock {
        ensureSchemaV2MigratedLocked()
    }

    private suspend fun ensureSchemaV2MigratedLocked() {
        val prefs = store.data.first()
        val currentVersion = prefs[KEY_SCHEMA_VERSION] ?: 1
        if (currentVersion >= SCHEMA_VERSION) return

        val raw = prefs[KEY_TRUSTED_PEERS]
        val hadLegacyRecords = !raw.isNullOrBlank() && raw.trim() != "[]"
        val hasAllRoles = if (hadLegacyRecords) {
            try {
                val arr = json.parseToJsonElement(raw!!) as? JsonArray
                arr != null && arr.isNotEmpty() && arr.all { elem ->
                    elem.jsonObject.containsKey("peerRole")
                }
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }

        if (hadLegacyRecords && !hasAllRoles) {
            crypto.rotateWrappingKeysOnRevoke()
            store.edit { mutablePrefs ->
                mutablePrefs.remove(KEY_TRUSTED_PEERS)
                mutablePrefs[KEY_SCHEMA_VERSION] = SCHEMA_VERSION
                mutablePrefs[KEY_UPGRADE_NOTICE_PENDING] = true
            }
        } else {
            store.edit { mutablePrefs ->
                mutablePrefs[KEY_SCHEMA_VERSION] = SCHEMA_VERSION
            }
        }
    }

    suspend fun dismissUpgradeNotice() = mutationMutex.withLock {
        store.edit { prefs ->
            prefs[KEY_UPGRADE_NOTICE_PENDING] = false
        }
    }

    suspend fun getOrCreateDeviceId(): String = mutationMutex.withLock {
        val current = store.data.first()[KEY_DEVICE_ID]
        if (!current.isNullOrBlank()) return@withLock current
        val created = UUID.randomUUID().toString().take(12)
        store.edit { it[KEY_DEVICE_ID] = created }
        created
    }

    suspend fun saveDisplayProfile(profile: DisplayProfile) = mutationMutex.withLock {
        store.edit { prefs ->
            prefs[KEY_DISPLAY_PROFILE] = json.encodeToString(profile)
            prefs.remove(KEY_LEGACY_CLIENT_PROFILE)
        }
    }

    suspend fun evaluateDisplayPreChallengeGate(): DisplayPreChallengeGate = mutationMutex.withLock {
        ensureSchemaV2MigratedLocked()
        val ksAvailable = crypto.isAvailable()
        val prevFailed = store.data.first()[KEY_SESSION_ONLY_FALLBACK] ?: false
        DisplayPreChallengeGate(
            keystoreAvailable = ksAvailable,
            sessionOnly = !ksAvailable,
            previousPersistFailed = prevFailed
        )
    }

    suspend fun recordSessionOnlyFallback(failed: Boolean) = mutationMutex.withLock {
        store.edit { prefs ->
            prefs[KEY_SESSION_ONLY_FALLBACK] = failed
        }
    }

    suspend fun isSessionOnlyFallback(): Boolean = mutationMutex.withLock {
        store.data.first()[KEY_SESSION_ONLY_FALLBACK] ?: false
    }

    suspend fun getPeer(peerId: String, peerRole: TrustedPeerRole): TrustedPeer? = mutationMutex.withLock {
        ensureSchemaV2MigratedLocked()
        readPeersLocked().firstOrNull { it.peerId == peerId && it.peerRole == peerRole }
    }

    /**
     * Fails closed if Keystore encryption fails (returns null and persists nothing to DataStore).
     * DISPLAY_INBOUND_HOST always persists lastKnownPort = null; HOST_SAVED_DISPLAY persists the valid controlPort.
     * Mutates peer record, [pendingCommitFinal], and optional [KEY_SESSION_ONLY_FALLBACK] reset atomically under [mutationMutex].
     */
    suspend fun trustPeer(
        peerId: String,
        deviceName: String,
        rawToken: String,
        peerRole: TrustedPeerRole,
        ip: String,
        port: Int?,
        profile: DisplayProfile?,
        pendingCommitFinal: Boolean = false,
        clearSessionOnlyFallbackOnSuccess: Boolean = false
    ): TrustedPeer? = mutationMutex.withLock {
        ensureSchemaV2MigratedLocked()
        val encrypted = crypto.encryptToken(rawToken) ?: return@withLock null
        val normalizedPort = when (peerRole) {
            TrustedPeerRole.DISPLAY_INBOUND_HOST -> null
            TrustedPeerRole.HOST_SAVED_DISPLAY -> port?.takeIf { it > 0 }
        }
        val currentList = readPeersLocked().toMutableList()
        val existingIdx = currentList.indexOfFirst { it.peerId == peerId && it.peerRole == peerRole }
        val nextVersion = if (existingIdx >= 0) currentList[existingIdx].tokenVersion + 1 else 1
        val peer = TrustedPeer(
            peerId = peerId,
            deviceName = deviceName,
            encryptedPairingToken = encrypted,
            tokenVersion = nextVersion,
            peerRole = peerRole,
            lastKnownIp = ip,
            lastKnownPort = normalizedPort,
            cachedDisplayProfile = profile ?: currentList.getOrNull(existingIdx)?.cachedDisplayProfile,
            lastConnectedAtMs = System.currentTimeMillis(),
            isOnline = true,
            pendingCommitFinal = pendingCommitFinal
        )
        if (existingIdx >= 0) {
            currentList[existingIdx] = peer
        } else {
            currentList.add(peer)
        }
        store.edit { prefs ->
            prefs[KEY_SCHEMA_VERSION] = SCHEMA_VERSION
            prefs[KEY_TRUSTED_PEERS] = json.encodeToString(currentList)
            if (clearSessionOnlyFallbackOnSuccess) {
                prefs[KEY_SESSION_ONLY_FALLBACK] = false
            }
        }
        peer
    }

    suspend fun clearPendingCommitFinal(peerId: String, peerRole: TrustedPeerRole) = mutationMutex.withLock {
        ensureSchemaV2MigratedLocked()
        val currentList = readPeersLocked().toMutableList()
        val idx = currentList.indexOfFirst { it.peerId == peerId && it.peerRole == peerRole }
        if (idx < 0 || !currentList[idx].pendingCommitFinal) return@withLock
        currentList[idx] = currentList[idx].copy(pendingCommitFinal = false)
        persistPeersLocked(currentList)
    }

    suspend fun removePeer(peerId: String, peerRole: TrustedPeerRole) = mutationMutex.withLock {
        ensureSchemaV2MigratedLocked()
        val currentList = readPeersLocked()
        val filtered = currentList.filterNot { it.peerId == peerId && it.peerRole == peerRole }
        if (filtered.size == currentList.size) return@withLock
        persistPeersLocked(filtered)
    }

    suspend fun updatePeerEndpoint(
        peerId: String,
        peerRole: TrustedPeerRole,
        ip: String,
        port: Int?
    ) = mutationMutex.withLock {
        ensureSchemaV2MigratedLocked()
        val currentList = readPeersLocked().toMutableList()
        val idx = currentList.indexOfFirst { it.peerId == peerId && it.peerRole == peerRole }
        if (idx < 0) return@withLock
        val existing = currentList[idx]
        val normalizedPort = if (peerRole == TrustedPeerRole.DISPLAY_INBOUND_HOST) {
            null
        } else {
            port?.takeIf { it > 0 } ?: existing.lastKnownPort
        }
        currentList[idx] = existing.copy(
            lastKnownIp = ip,
            lastKnownPort = normalizedPort,
            lastConnectedAtMs = System.currentTimeMillis(),
            isOnline = true
        )
        persistPeersLocked(currentList)
    }

    suspend fun getDecryptedToken(peerId: String, peerRole: TrustedPeerRole): String? = mutationMutex.withLock {
        ensureSchemaV2MigratedLocked()
        val peer = readPeersLocked().firstOrNull {
            it.peerId == peerId && it.peerRole == peerRole
        } ?: return@withLock null
        crypto.decryptToken(peer.encryptedPairingToken)
    }

    suspend fun revokePeerAndRotate(peerId: String, peerRole: TrustedPeerRole? = null) = mutationMutex.withLock {
        ensureSchemaV2MigratedLocked()
        val remaining = readPeersLocked().filterNot {
            it.peerId == peerId && (peerRole == null || it.peerRole == peerRole)
        }
        val decryptedSurvivors = remaining.mapNotNull { peer ->
            val raw = crypto.decryptToken(peer.encryptedPairingToken) ?: return@mapNotNull null
            peer to raw
        }
        crypto.rotateWrappingKeysOnRevoke()
        // Fail closed during re-encryption: drop records that fail Keystore encryption rather than saving plaintext
        val reEncrypted = decryptedSurvivors.mapNotNull { (peer, raw) ->
            val enc = crypto.encryptToken(raw) ?: return@mapNotNull null
            peer.copy(
                encryptedPairingToken = enc,
                tokenVersion = peer.tokenVersion + 1
            )
        }
        persistPeersLocked(reEncrypted)
    }

    private suspend fun readPeersLocked(): List<TrustedPeer> {
        val prefs = store.data.first()
        val schemaVersion = prefs[KEY_SCHEMA_VERSION] ?: 1
        val raw = prefs[KEY_TRUSTED_PEERS] ?: return emptyList()
        if (schemaVersion < SCHEMA_VERSION) return emptyList()
        return decodeAndNormalizePeers(raw)
    }

    private fun decodeAndNormalizePeers(raw: String): List<TrustedPeer> {
        return try {
            json.decodeFromString<List<TrustedPeer>>(raw).map { peer ->
                val cleanPort = when (peer.peerRole) {
                    TrustedPeerRole.DISPLAY_INBOUND_HOST -> null
                    TrustedPeerRole.HOST_SAVED_DISPLAY -> peer.lastKnownPort?.takeIf { it > 0 }
                }
                peer.copy(lastKnownPort = cleanPort)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun persistPeersLocked(peers: List<TrustedPeer>) {
        store.edit { prefs ->
            prefs[KEY_SCHEMA_VERSION] = SCHEMA_VERSION
            prefs[KEY_TRUSTED_PEERS] = json.encodeToString(peers)
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
        private val KEY_TRUSTED_PEERS = stringPreferencesKey("trusted_peers_json")
        private val KEY_SCHEMA_VERSION = intPreferencesKey("trusted_peers_schema_version")
        private val KEY_UPGRADE_NOTICE_PENDING = booleanPreferencesKey("trust_upgrade_notice_pending")
        private val KEY_SESSION_ONLY_FALLBACK = booleanPreferencesKey("display_session_only_fallback")
        private val KEY_DISPLAY_PROFILE = stringPreferencesKey("display_profile_json")
        private val KEY_LEGACY_CLIENT_PROFILE = stringPreferencesKey("client_display_profile_json")
        private val KEY_DEVICE_ID = stringPreferencesKey("local_device_id")
    }
}
