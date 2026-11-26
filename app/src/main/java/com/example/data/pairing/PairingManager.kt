package com.example.data.pairing

import android.os.Build
import android.util.Log
import com.example.data.network.ConnectionManager
import com.example.data.network.ConnectionState
import com.example.data.network.NetworkClient
import com.example.data.network.NetworkServer
import com.example.data.protocol.ProtocolConstants
import com.example.data.protocol.ProtocolMessage
import com.example.data.protocol.ProtocolMessageType
import com.example.data.security.SecurityKeyStore
import com.example.data.security.SecurityValidator
import com.example.domain.model.AppErrorMapper
import com.example.domain.model.AppErrorReporter
import com.example.domain.model.AppSettings
import com.example.domain.model.DeviceType
import com.example.domain.model.ErrorCategory
import com.example.domain.model.PairedDevice
import com.example.domain.repository.PairedDeviceRepository
import com.example.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PairingSession(
    val sessionId: String,
    val token: String,
    val payload: PairingPayload,
    val expiresAt: Long,
    val remainingSeconds: Int,
    val status: String = "Waiting for peer to scan…"
)

sealed interface PairingResult {
    data object Idle : PairingResult
    data class InProgress(val status: String) : PairingResult
    data class Success(val device: PairedDevice) : PairingResult
    data class Failure(val reason: String) : PairingResult
}

class PairingManager(
    private val server: NetworkServer,
    private val client: NetworkClient,
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val securityKeyStore: SecurityKeyStore,
    private val connectionManager: ConnectionManager,
    private val myDeviceId: String,
    private val settingsProvider: () -> AppSettings,
    private val localCertFingerprintProvider: () -> String? = { null }
) {
    companion object {
        private const val TAG = "PairingManager"
        const val SESSION_DURATION_MS = 120_000L // 2 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var countdownJob: Job? = null
    private val consumedSessionIds = ConcurrentHashMap.newKeySet<String>()

    private val _activeSession = MutableStateFlow<PairingSession?>(null)
    val activeSession: StateFlow<PairingSession?> = _activeSession.asStateFlow()

    private val _pairingResult = MutableStateFlow<PairingResult>(PairingResult.Idle)
    val pairingResult: StateFlow<PairingResult> = _pairingResult.asStateFlow()

    init {
        setupServerMessageHandler()
    }

    private fun setupServerMessageHandler() {
        server.setMessageHandler { message, _ ->
            handleIncomingMessage(message)
        }
    }

    suspend fun handleIncomingMessage(message: ProtocolMessage): ProtocolMessage? {
        return when (message.type) {
            ProtocolMessageType.PAIRING_REQUEST -> handleIncomingPairingRequest(message)
            ProtocolMessageType.AUTHENTICATION_REQUEST -> handleIncomingAuthRequest(message)
            ProtocolMessageType.HEARTBEAT -> ProtocolMessage(
                version = ProtocolConstants.negotiateProtocolVersion(message.version) ?: ProtocolConstants.VERSION_V1,
                type = ProtocolMessageType.HEARTBEAT,
                senderId = myDeviceId,
                receiverId = message.senderId,
                payload = mapOf("pong" to System.currentTimeMillis())
            )
            else -> null
        }
    }

    fun generateNewPairingSession(): PairingSession {
        countdownJob?.cancel()

        val sessionId = UUID.randomUUID().toString()
        val token = securityKeyStore.generateSecureToken(16)
        val nonce = securityKeyStore.generateSecureToken(8)
        val expiresAt = System.currentTimeMillis() + SESSION_DURATION_MS

        val settings = settingsProvider()
        val ip = NetworkUtils.getLocalIpv4Address() ?: "127.0.0.1"
        val certFp = localCertFingerprintProvider()?.takeIf { it.isNotBlank() }
        val tlsSupported = settings.tlsPreferred && certFp != null
        val version = settings.protocolVersion.takeIf { it in ProtocolConstants.SUPPORTED_VERSIONS }
            ?: ProtocolConstants.PREFERRED_VERSION

        val payload = PairingPayload(
            protocolVersion = version,
            sessionId = sessionId,
            pairingToken = token,
            deviceId = myDeviceId,
            deviceName = settings.deviceName,
            platform = "android",
            ipAddress = ip,
            port = settings.port,
            expiresAt = expiresAt,
            tlsSupported = tlsSupported,
            certFingerprint = certFp,
            nonce = nonce
        )

        val session = PairingSession(
            sessionId = sessionId,
            token = token,
            payload = payload,
            expiresAt = expiresAt,
            remainingSeconds = (SESSION_DURATION_MS / 1000).toInt(),
            status = "Waiting for peer to scan…"
        )

        _activeSession.value = session
        _pairingResult.value = PairingResult.Idle

        startCountdown(expiresAt)
        return session
    }

    private fun startCountdown(expiresAt: Long) {
        countdownJob = scope.launch {
            while (isActive) {
                val remaining = ((expiresAt - System.currentTimeMillis()) / 1000).toInt()
                if (remaining <= 0) {
                    _activeSession.update { current ->
                        current?.copy(remainingSeconds = 0, status = "QR Code Expired")
                    }
                    break
                } else {
                    _activeSession.update { current ->
                        current?.copy(remainingSeconds = remaining)
                    }
                }
                delay(1000)
            }
        }
    }

    fun cancelActiveSession() {
        countdownJob?.cancel()
        _activeSession.value = null
        _pairingResult.value = PairingResult.Idle
    }

    fun resetPairingResult() {
        _pairingResult.value = PairingResult.Idle
    }

    /**
     * Called when our device scans a peer's QR code or pairing URI.
     * We connect to the peer's TCP/TLS server and send a PAIRING_REQUEST.
     */
    suspend fun pairWithScannedDevice(payload: PairingPayload) {
        if (payload.isExpired) {
            val reason = "Pairing QR code has expired. Ask peer to regenerate."
            AppErrorReporter.report(
                category = ErrorCategory.PAIRING,
                userMessage = reason,
                technicalDetails = "Expired pairing payload for ${payload.deviceName}",
                targetLabel = payload.deviceName,
                address = "${payload.ipAddress}:${payload.port}"
            )
            _pairingResult.value = PairingResult.Failure(reason)
            return
        }

        _pairingResult.value = PairingResult.InProgress("Connecting to ${payload.deviceName}…")

        val settings = settingsProvider()
        val myIp = NetworkUtils.getLocalIpv4Address() ?: "127.0.0.1"
        val myCertFp = localCertFingerprintProvider()?.takeIf { it.isNotBlank() }
        val negotiatedVersion = ProtocolConstants.negotiateProtocolVersion(payload.protocolVersion)
            ?: ProtocolConstants.VERSION_V1

        val reqPayload = linkedMapOf<String, Any?>(
            "sessionId" to payload.sessionId,
            "token" to payload.pairingToken,
            "deviceName" to settings.deviceName,
            "platform" to "android",
            "ipAddress" to myIp,
            "port" to settings.port,
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "tlsSupported" to (settings.tlsPreferred && myCertFp != null)
        )
        if (myCertFp != null) {
            reqPayload["certFingerprint"] = myCertFp
        }

        val requestMessage = ProtocolMessage(
            version = negotiatedVersion,
            type = ProtocolMessageType.PAIRING_REQUEST,
            senderId = myDeviceId,
            receiverId = payload.deviceId,
            payload = reqPayload
        )

        _pairingResult.value = PairingResult.InProgress("Authenticating with ${payload.deviceName}…")

        val useTls = settings.tlsPreferred && payload.tlsSupported
        val result = client.sendMessage(
            host = payload.ipAddress,
            port = payload.port,
            message = requestMessage,
            timeoutMs = 10_000,
            useTls = useTls,
            expectedCertFingerprint = payload.certFingerprint
        )

        result.fold(
            onSuccess = { response ->
                when (response.type) {
                    ProtocolMessageType.PAIRING_ACCEPTED -> {
                        val peerToken = response.payload["authToken"] as? String ?: payload.pairingToken
                        val peerModel = response.payload["model"] as? String
                        val peerTlsSupported = (response.payload["tlsSupported"] as? Boolean) ?: payload.tlsSupported
                        val peerCertFp = (response.payload["certFingerprint"] as? String)
                            ?.takeIf { it.isNotBlank() } ?: payload.certFingerprint

                        val pairedDevice = PairedDevice(
                            id = payload.deviceId,
                            alias = payload.deviceName,
                            deviceType = DeviceType.fromIdentifier(payload.platform),
                            ipAddress = payload.ipAddress,
                            port = payload.port,
                            isTrusted = true,
                            pairedAt = System.currentTimeMillis(),
                            lastConnectedAt = System.currentTimeMillis(),
                            model = peerModel,
                            isConnected = true,
                            certFingerprint = peerCertFp,
                            tlsSupported = peerTlsSupported || peerCertFp != null
                        )

                        pairedDeviceRepository.savePairedDevice(pairedDevice, peerToken)
                        connectionManager.setConnectionState(pairedDevice.id, ConnectionState.Connected)
                        _pairingResult.value = PairingResult.Success(pairedDevice)
                    }
                    ProtocolMessageType.PAIRING_REJECTED -> {
                        val reason = response.payload["reason"] as? String ?: "Pairing request was rejected by device"
                        AppErrorReporter.report(
                            category = ErrorCategory.PAIRING,
                            userMessage = reason,
                            technicalDetails = "PAIRING_REJECTED from ${payload.deviceName}",
                            targetLabel = payload.deviceName,
                            address = "${payload.ipAddress}:${payload.port}"
                        )
                        _pairingResult.value = PairingResult.Failure(reason)
                    }
                    else -> {
                        val msg = (response.payload["message"] as? String)
                            ?: "Unexpected response received while pairing with ${payload.deviceName}."
                        AppErrorReporter.report(
                            category = ErrorCategory.PROTOCOL,
                            userMessage = msg,
                            technicalDetails = "Unexpected protocol response: ${response.type}",
                            targetLabel = payload.deviceName,
                            address = "${payload.ipAddress}:${payload.port}"
                        )
                        _pairingResult.value = PairingResult.Failure(msg)
                    }
                }
            },
            onFailure = { error ->
                val appError = AppErrorMapper.fromThrowable(
                    category = ErrorCategory.CONNECTION,
                    throwable = error,
                    targetLabel = payload.deviceName,
                    address = "${payload.ipAddress}:${payload.port}"
                )
                AppErrorReporter.report(appError)
                _pairingResult.value = PairingResult.Failure(appError.userMessage)
            }
        )
    }

    /**
     * Handshake when another device scans our QR code and connects to our TCP/TLS server.
     * Single-use session enforcement prevents second-device replay within the TTL window.
     */
    private suspend fun handleIncomingPairingRequest(message: ProtocolMessage): ProtocolMessage {
        val negotiatedVersion = ProtocolConstants.negotiateProtocolVersion(message.version)
            ?: ProtocolConstants.VERSION_V1
        val currentSession = _activeSession.value
        val requestSessionId = message.payload["sessionId"] as? String
        val requestToken = message.payload["token"] as? String

        if (requestSessionId != null && requestSessionId in consumedSessionIds) {
            return ProtocolMessage(
                version = negotiatedVersion,
                type = ProtocolMessageType.PAIRING_REJECTED,
                senderId = myDeviceId,
                receiverId = message.senderId,
                payload = mapOf(
                    "code" to ProtocolConstants.ErrorCode.ERR_PAIRING_REPLAY,
                    "reason" to "Pairing QR session was already used"
                )
            )
        }

        if (currentSession == null || currentSession.remainingSeconds <= 0) {
            return ProtocolMessage(
                version = negotiatedVersion,
                type = ProtocolMessageType.PAIRING_REJECTED,
                senderId = myDeviceId,
                receiverId = message.senderId,
                payload = mapOf(
                    "code" to ProtocolConstants.ErrorCode.ERR_PAIRING_EXPIRED,
                    "reason" to "No active pairing session or QR code expired"
                )
            )
        }

        if (!SecurityValidator.constantTimeEquals(requestSessionId, currentSession.sessionId) ||
            !SecurityValidator.constantTimeEquals(requestToken, currentSession.token)
        ) {
            return ProtocolMessage(
                version = negotiatedVersion,
                type = ProtocolMessageType.PAIRING_REJECTED,
                senderId = myDeviceId,
                receiverId = message.senderId,
                payload = mapOf(
                    "code" to ProtocolConstants.ErrorCode.ERR_AUTH_FAILED,
                    "reason" to "Invalid pairing credentials or token mismatch"
                )
            )
        }

        // Consume single-use session atomically to reject concurrent or subsequent replays
        if (!consumedSessionIds.add(currentSession.sessionId)) {
            return ProtocolMessage(
                version = negotiatedVersion,
                type = ProtocolMessageType.PAIRING_REJECTED,
                senderId = myDeviceId,
                receiverId = message.senderId,
                payload = mapOf(
                    "code" to ProtocolConstants.ErrorCode.ERR_PAIRING_REPLAY,
                    "reason" to "Pairing QR session was already used"
                )
            )
        }
        countdownJob?.cancel()

        val peerAlias = message.payload["deviceName"] as? String ?: "Remote Peer"
        val peerPlatform = message.payload["platform"] as? String ?: "unknown"
        val peerIp = message.payload["ipAddress"] as? String ?: "127.0.0.1"
        val peerPort = (message.payload["port"] as? Number)?.toInt() ?: ProtocolConstants.DEFAULT_PORT
        val peerModel = message.payload["model"] as? String
        val peerCertFp = (message.payload["certFingerprint"] as? String)?.takeIf { it.isNotBlank() }
        val peerTlsSupported = (message.payload["tlsSupported"] as? Boolean) ?: (peerCertFp != null)

        // Generate mutual permanent authentication token
        val mutualAuthToken = securityKeyStore.generateSecureToken(24)

        val newPairedDevice = PairedDevice(
            id = message.senderId,
            alias = peerAlias,
            deviceType = DeviceType.fromIdentifier(peerPlatform),
            ipAddress = peerIp,
            port = peerPort,
            isTrusted = true,
            pairedAt = System.currentTimeMillis(),
            lastConnectedAt = System.currentTimeMillis(),
            model = peerModel,
            isConnected = true,
            certFingerprint = peerCertFp,
            tlsSupported = peerTlsSupported
        )

        pairedDeviceRepository.savePairedDevice(newPairedDevice, mutualAuthToken)
        connectionManager.setConnectionState(newPairedDevice.id, ConnectionState.Connected)

        _activeSession.update {
            it?.copy(remainingSeconds = 0, status = "Successfully paired with $peerAlias!")
        }
        _pairingResult.value = PairingResult.Success(newPairedDevice)

        val settings = settingsProvider()
        val myCertFp = localCertFingerprintProvider()?.takeIf { it.isNotBlank() }
        val respPayload = linkedMapOf<String, Any?>(
            "authToken" to mutualAuthToken,
            "deviceName" to settings.deviceName,
            "platform" to "android",
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "protocolVersion" to negotiatedVersion,
            "tlsSupported" to (settings.tlsPreferred && myCertFp != null)
        )
        if (myCertFp != null) {
            respPayload["certFingerprint"] = myCertFp
        }
        return ProtocolMessage(
            version = negotiatedVersion,
            type = ProtocolMessageType.PAIRING_ACCEPTED,
            senderId = myDeviceId,
            receiverId = message.senderId,
            payload = respPayload
        )
    }

    private suspend fun handleIncomingAuthRequest(message: ProtocolMessage): ProtocolMessage {
        val deviceId = message.senderId
        val negotiatedVersion = ProtocolConstants.negotiateProtocolVersion(message.version)
            ?: ProtocolConstants.VERSION_V1

        if (SecurityValidator.isPeerAuthLockedOut(deviceId)) {
            return ProtocolMessage.createError(
                senderId = myDeviceId,
                receiverId = deviceId,
                errorCode = ProtocolConstants.ErrorCode.ERR_AUTH_LOCKOUT,
                errorMessage = "Too many failed authentication attempts. Try again later.",
                version = negotiatedVersion
            )
        }

        val reqTimestamp = (message.payload["timestamp"] as? Number)?.toLong() ?: message.timestamp
        val reqNonce = message.payload["nonce"] as? String
        if (!SecurityValidator.isAuthTimestampAndNonceValid(deviceId, reqTimestamp, reqNonce)) {
            AppErrorReporter.report(
                category = ErrorCategory.AUTHENTICATION,
                userMessage = "Rejected stale or replayed authentication request.",
                technicalDetails = "ERR_REPLAY_DETECTED for peerId=$deviceId",
                targetLabel = deviceId
            )
            return ProtocolMessage.createError(
                senderId = myDeviceId,
                receiverId = deviceId,
                errorCode = ProtocolConstants.ErrorCode.ERR_REPLAY_DETECTED,
                errorMessage = "Authentication request timestamp or nonce was already used or expired.",
                version = negotiatedVersion
            )
        }

        val providedToken = message.payload["token"] as? String
        val storedToken = pairedDeviceRepository.getAuthToken(deviceId)
        val isMatch = !storedToken.isNullOrBlank() &&
            SecurityValidator.constantTimeEquals(storedToken, providedToken)
        SecurityValidator.recordAuthResult(deviceId, isMatch)

        return if (isMatch) {
            pairedDeviceRepository.updateLastConnected(deviceId)
            connectionManager.setConnectionState(deviceId, ConnectionState.Connected)
            ProtocolMessage(
                version = negotiatedVersion,
                type = ProtocolMessageType.AUTHENTICATION_RESPONSE,
                senderId = myDeviceId,
                receiverId = deviceId,
                payload = mapOf(
                    "status" to "authenticated",
                    "protocolVersion" to negotiatedVersion
                )
            )
        } else {
            AppErrorReporter.report(
                category = ErrorCategory.AUTHENTICATION,
                userMessage = "Rejected authentication attempt from an unrecognized or reset peer.",
                technicalDetails = "ERR_AUTH_FAILED for peerId=$deviceId",
                targetLabel = deviceId
            )
            ProtocolMessage.createError(
                senderId = myDeviceId,
                receiverId = deviceId,
                errorCode = ProtocolConstants.ErrorCode.ERR_AUTH_FAILED,
                errorMessage = "Invalid or unknown authentication token",
                version = negotiatedVersion
            )
        }
    }
}
