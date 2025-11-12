// MainActivity.kt
package com.example.ghostnet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ---------- Models & constants ----------
enum class PeerState { PENDING, APPROVED, ME }
enum class MessageType { USER, SYSTEM, FILE }

data class Peer(
    val endpointId: String,
    val username: String,
    var publicKey: PublicKey,
    var state: PeerState
)

data class Message(
    val id: String,
    val senderUsername: String,
    val text: String,
    val timestamp: String,
    val isFromMe: Boolean,
    val type: MessageType = MessageType.USER,
    val fileMime: String? = null,
    val fileUri: Uri? = null,
    val fileSize: Long? = null,
    val transferProgress: Float? = null,
    val isReceiving: Boolean = false
)

private const val TAG = "GhostNet"
private const val SERVICE_ID = "com.example.ghostnet.SERVICE_ID.v13"
private const val INITIAL_TTL = 4
private const val CONNECTION_COOLDOWN_MS = 5000

// theme
val CyberDarkBlue = Color(0xFF0A192F)
val CyberLightBlue = Color(0xFF112240)
val CyberCyan = Color(0xFF64FFDA)
val CyberLightText = Color(0xFFCCD6F6)
val CyberGrayText = Color(0xFF8892B0)

enum class Screen { Onboarding, Discovery, Chat }

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    private lateinit var connectionsClient: ConnectionsClient
    private val processedMessageIds = Collections.synchronizedSet(HashSet<String>())
    private val permissionsGranted = mutableStateOf(false)

    private val askPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            permissionsGranted.value = map.values.all { it }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionsClient = Nearby.getConnectionsClient(this)
        setContent {
            GhostNetApp(
                connectionsClient = connectionsClient,
                requestPermissions = ::requestPermissions,
                permissionsGranted = permissionsGranted.value,
                processedMessageIds = processedMessageIds,
                context = this
            )
        }
    }

    private fun requestPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        }
        askPerms.launch(perms)
    }
}

// ---------- Crypto helpers ----------
object Crypto {
    private const val AES_ALGO = "AES"
    private const val AES_TX = "AES/CBC/PKCS5Padding"
    private const val SIGN_ALGO = "SHA256withRSA"
    private const val KEY_ALGO = "RSA"

    private fun secretKey(secret: String): SecretKeySpec {
        val sha = MessageDigest.getInstance("SHA-256")
        return SecretKeySpec(sha.digest(secret.toByteArray(StandardCharsets.UTF_8)), AES_ALGO)
    }

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEY_ALGO).apply { initialize(2048) }.genKeyPair()

    fun encodeKeyToString(key: PublicKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun decodeStringToKey(keyStr: String): PublicKey {
        val keyBytes = Base64.decode(keyStr, Base64.NO_WRAP)
        return KeyFactory.getInstance(KEY_ALGO).generatePublic(X509EncodedKeySpec(keyBytes))
    }

    fun signData(data: String, privateKey: PrivateKey): String {
        val s = Signature.getInstance(SIGN_ALGO)
        s.initSign(privateKey)
        s.update(data.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    fun verifySignature(data: String, sigStr: String, publicKey: PublicKey): Boolean = try {
        val s = Signature.getInstance(SIGN_ALGO)
        s.initVerify(publicKey)
        s.update(data.toByteArray(StandardCharsets.UTF_8))
        s.verify(Base64.decode(sigStr, Base64.NO_WRAP))
    } catch (_: Exception) { false }

    fun encrypt(plain: String, secret: String): String = try {
        val iv = SecureRandom().generateSeed(16)
        val c = Cipher.getInstance(AES_TX)
        c.init(Cipher.ENCRYPT_MODE, secretKey(secret), IvParameterSpec(iv))
        Base64.encodeToString(iv + c.doFinal(plain.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    } catch (_: Exception) { "" }

    fun decrypt(blob: String, secret: String): String = try {
        val b = Base64.decode(blob, Base64.NO_WRAP)
        val iv = b.copyOfRange(0, 16)
        val ct = b.copyOfRange(16, b.size)
        val c = Cipher.getInstance(AES_TX)
        c.init(Cipher.DECRYPT_MODE, secretKey(secret), IvParameterSpec(iv))
        String(c.doFinal(ct), StandardCharsets.UTF_8)
    } catch (_: Exception) { "DECRYPTION_FAILED" }

    fun hash(s: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
}

// ---------- Text payload handlers ----------
fun handleMsgPayload(
    content: String,
    fromId: String,
    client: ConnectionsClient,
    peers: List<Peer>,
    processed: MutableSet<String>,
    password: String,
    addMsg: (Message) -> Unit
) {
    val parts = content.split("|", limit = 6)
    if (parts.size != 6) return

    val msgId = parts[0]
    val sender = parts[1]
    val enc = parts[2]
    val ts = parts[3]
    val ttl = parts[4].toIntOrNull() ?: return
    val sig = parts[5]

    val senderPeer = peers.find { it.username == sender && (it.state == PeerState.APPROVED || it.state == PeerState.ME) } ?: return
    val verifyStr = parts.take(5).joinToString("|")
    if (!Crypto.verifySignature(verifyStr, sig, senderPeer.publicKey)) return

    if (processed.add(msgId)) {
        val dec = Crypto.decrypt(enc, password)
        if (dec != "DECRYPTION_FAILED") addMsg(Message(msgId, sender, dec, ts, false))
        if (ttl > 1) {
            val next = "MSG|${parts.take(4).joinToString("|")}|${ttl - 1}|$sig"
            val payload = Payload.fromBytes(next.toByteArray(StandardCharsets.UTF_8))
            peers.filter { it.state == PeerState.APPROVED && it.endpointId != fromId }
                .forEach { client.sendPayload(it.endpointId, payload) }
        }
    }
}

fun handleWelcomePayload(
    content: String,
    fromEndpointId: String,
    peers: MutableList<Peer>,
    addMsg: (Message) -> Unit
) {
    val roster = content.split(",").filter { it.isNotBlank() }
    val keyByUser = mutableMapOf<String, PublicKey>()
    for (entry in roster) {
        val parts = entry.split(":", limit = 2)
        if (parts.size != 2) continue
        val uname = parts[0]
        val keyStr = parts[1]
        val realKey = try { Crypto.decodeStringToKey(keyStr) } catch (_: Exception) { continue }
        keyByUser[uname] = realKey
        val existing = peers.find { it.username == uname }
        if (existing == null) peers.add(Peer("unknown", uname, realKey, PeerState.APPROVED))
        else if (existing.state != PeerState.ME) {
            existing.publicKey = realKey
            if (existing.state == PeerState.PENDING) existing.state = PeerState.APPROVED
        }
    }
    peers.find { it.endpointId == fromEndpointId }?.let { direct ->
        if (direct.state == PeerState.PENDING) direct.state = PeerState.APPROVED
        keyByUser[direct.username]?.let { direct.publicKey = it }
    }
    addMsg(Message(UUID.randomUUID().toString(), "System", "Successfully joined the channel!", System.currentTimeMillis().toString(), false, MessageType.SYSTEM))
}

// ---------- FILE meta ----------
data class FileMeta(
    val metaId: String,
    val sender: String,
    val name: String,
    val mime: String,
    val size: Long,
    val ts: String,
    val ttl: Int,
    val filePayloadId: Long,
    val sig: String
)

private fun parseFileMeta(raw: String): FileMeta? {
    val parts = raw.split("|", limit = 10)
    if (parts.size != 10 || parts[0] != "FILE") return null
    val size = parts[5].toLongOrNull() ?: -1L
    val ttl = parts[7].toIntOrNull() ?: return null
    val pid = parts[8].toLongOrNull() ?: return null
    return FileMeta(parts[1], parts[2], parts[3], parts[4], size, parts[6], ttl, pid, parts[9])
}

// ---------- App (Compose + Nearby) ----------
@Composable
fun GhostNetApp(
    connectionsClient: ConnectionsClient,
    requestPermissions: () -> Unit,
    permissionsGranted: Boolean,
    processedMessageIds: MutableSet<String>,
    context: Context
) {
    val appCtx = context.applicationContext
    val currentScreen = remember { mutableStateOf(Screen.Onboarding) }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val keyPair by remember { mutableStateOf(Crypto.generateKeyPair()) }

    val peers = remember { mutableStateListOf<Peer>() }
    var peerToApprove by remember { mutableStateOf<Peer?>(null) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }

    var isAdvertising by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var startupError by remember { mutableStateOf<String?>(null) }
    var isHost by remember { mutableStateOf(false) }

    val connectionAttempts = remember { ConcurrentHashMap<String, Long>() }

    // --- File transfer state
    val incomingFileMeta = remember { mutableStateMapOf<Long, FileMeta>() }
    val incomingTempFile = remember { mutableStateMapOf<Long, File>() }
    val sizeByPayloadId  = remember { mutableStateMapOf<Long, Long>() }
    val outgoingPayloadToMessage = remember { mutableStateMapOf<Long, String>() }
    val incomingPayloadToMessage = remember { mutableStateMapOf<Long, String>() }
    val outgoingCacheFile = remember { mutableStateMapOf<Long, File>() } // delete after SUCCESS

    LaunchedEffect(startupError) { if (startupError != null) { delay(2500); startupError = null } }

    fun updateMessageProgress(messageId: String, progress: Float) {
        messages = messages.map { if (it.id == messageId) it.copy(transferProgress = progress) else it }
    }

    fun ensureIncomingPlaceholder(meta: FileMeta) {
        val msgId = incomingPayloadToMessage[meta.filePayloadId] ?: meta.metaId
        if (!messages.any { it.id == msgId }) {
            incomingPayloadToMessage[meta.filePayloadId] = msgId
            messages = messages + Message(
                id = msgId,
                senderUsername = meta.sender,
                text = meta.name,
                timestamp = meta.ts,
                isFromMe = false,
                type = MessageType.FILE,
                fileMime = meta.mime,
                fileSize = meta.size,
                transferProgress = 0f,
                isReceiving = true
            )
        }
    }

    fun finalizeIncoming(meta: FileMeta, tmp: File) {
        try {
            val savedUri = saveIncomingToCache(appCtx, tmp, meta.name)
            val msgId = incomingPayloadToMessage[meta.filePayloadId] ?: meta.metaId
            if (!messages.any { it.id == msgId }) ensureIncomingPlaceholder(meta)
            messages = messages.map { if (it.id == msgId) it.copy(fileUri = savedUri, transferProgress = 1f) else it }
        } catch (e: Exception) {
            Log.e(TAG, "finalizeIncoming error", e)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    // ---- Payload callback ----
    val payloadCallback = remember(password) {
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                when (payload.type) {
                    Payload.Type.BYTES -> {
                        val raw = String(payload.asBytes() ?: return, StandardCharsets.UTF_8)
                        when {
                            raw.startsWith("MSG|") -> {
                                val content = raw.removePrefix("MSG|")
                                handleMsgPayload(content, endpointId, connectionsClient, peers, processedMessageIds, password) { m -> messages = messages + m }
                            }
                            raw.startsWith("WELCOME|") -> {
                                val content = raw.removePrefix("WELCOME|")
                                handleWelcomePayload(content, endpointId, peers) { m -> messages = messages + m }
                            }
                            raw.startsWith("FILE|") -> {
                                val meta = parseFileMeta(raw) ?: return
                                incomingFileMeta[meta.filePayloadId] = meta
                                sizeByPayloadId[meta.filePayloadId] = meta.size
                                ensureIncomingPlaceholder(meta)
                            }
                        }
                    }
                    Payload.Type.FILE -> {
                        val f = payload.asFile()?.asJavaFile() ?: return
                        incomingTempFile[payload.id] = f
                    }
                    else -> Unit
                }
            }

            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                val knownSize = sizeByPayloadId[update.payloadId]
                val total = if (update.totalBytes > 0) update.totalBytes else (knownSize ?: -1L)
                val progress = if (total > 0) update.bytesTransferred.toFloat() / total.toFloat() else null

                incomingPayloadToMessage[update.payloadId]?.let { id -> progress?.let { updateMessageProgress(id, it) } }
                outgoingPayloadToMessage[update.payloadId]?.let { id -> progress?.let { updateMessageProgress(id, it) } }

                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        incomingFileMeta.remove(update.payloadId)?.let { meta ->
                            incomingTempFile.remove(update.payloadId)?.let { tmp -> finalizeIncoming(meta, tmp) }
                        }
                        outgoingCacheFile.remove(update.payloadId)?.let { runCatching { it.delete() } }
                        incomingPayloadToMessage[update.payloadId]?.let { updateMessageProgress(it, 1f) }
                        outgoingPayloadToMessage[update.payloadId]?.let { updateMessageProgress(it, 1f) }
                        sizeByPayloadId.remove(update.payloadId)
                    }
                    PayloadTransferUpdate.Status.FAILURE -> {
                        Log.e(TAG, "Transfer failed id=${update.payloadId}")
                    }
                    else -> Unit
                }
            }
        }
    }

    // ---- Advertising / Discovery ----
    fun startAdvertising() {
        if (peers.none { it.state == PeerState.ME }) peers.add(Peer("me", username, keyPair.public, PeerState.ME))
        val advName = "$username:${Crypto.hash(password)}"
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient.startAdvertising(
            advName, SERVICE_ID,
            object : ConnectionLifecycleCallback() {
                override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                    if (info.isIncomingConnection && isHost) {
                        val parts = info.endpointName.split(":", limit = 2)
                        if (parts.size != 2) { connectionsClient.rejectConnection(endpointId); return }
                        val remoteUser = parts[0]
                        val pk = try { Crypto.decodeStringToKey(parts[1]) } catch (_: Exception) { connectionsClient.rejectConnection(endpointId); return }
                        val p = Peer(endpointId, remoteUser, pk, PeerState.PENDING)
                        if (peers.none { it.endpointId == endpointId }) peers.add(p)
                        peerToApprove = p
                    } else connectionsClient.acceptConnection(endpointId, payloadCallback)
                }
                override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                    if (!result.status.isSuccess) {
                        peers.find { it.endpointId == endpointId }?.let { peers.remove(it) }
                        if (peerToApprove?.endpointId == endpointId) peerToApprove = null
                    }
                }
                override fun onDisconnected(endpointId: String) {
                    peers.find { it.endpointId == endpointId }?.let {
                        messages = messages + Message(UUID.randomUUID().toString(), "System", "${it.username} has left.", System.currentTimeMillis().toString(), false, MessageType.SYSTEM)
                        peers.remove(it)
                    }
                }
            },
            options
        ).addOnSuccessListener { isAdvertising = true }
            .addOnFailureListener { e -> startupError = "Advertising failed: ${e.message}" }
    }

    fun startDiscovery() {
        if (peers.none { it.state == PeerState.ME }) peers.add(Peer("me", username, keyPair.public, PeerState.ME))
        val expectedHash = Crypto.hash(password)
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    val parts = info.endpointName.split(":", limit = 2)
                    if (parts.size != 2) return
                    val remoteUser = parts[0]; val remoteHash = parts[1]
                    if (remoteHash != expectedHash || remoteUser == username) return

                    val now = System.currentTimeMillis()
                    val last = connectionAttempts[endpointId] ?: 0
                    val already = peers.any { it.username == remoteUser && (it.state == PeerState.PENDING || it.state == PeerState.APPROVED) }
                    if (now - last <= CONNECTION_COOLDOWN_MS || already) return

                    connectionAttempts[endpointId] = now
                    val localName = "$username:${Crypto.encodeKeyToString(keyPair.public)}"

                    connectionsClient.requestConnection(localName, endpointId,
                        object : ConnectionLifecycleCallback() {
                            override fun onConnectionInitiated(eid: String, info: ConnectionInfo) {
                                if (peers.none { it.endpointId == eid }) peers.add(Peer(eid, remoteUser, keyPair.public, PeerState.PENDING))
                                connectionsClient.acceptConnection(eid, payloadCallback)
                            }
                            override fun onConnectionResult(eid: String, result: ConnectionResolution) {
                                if (!result.status.isSuccess) {
                                    peers.find { it.endpointId == eid }?.let { peers.remove(it) }
                                } else {
                                    peers.find { it.endpointId == eid }?.let { if (it.state == PeerState.PENDING) it.state = PeerState.APPROVED }
                                    startAdvertising()
                                }
                            }
                            override fun onDisconnected(eid: String) {
                                peers.find { it.endpointId == eid }?.let {
                                    messages = messages + Message(UUID.randomUUID().toString(), "System", "${it.username} has left.", System.currentTimeMillis().toString(), false, MessageType.SYSTEM)
                                    peers.remove(it)
                                }
                                connectionAttempts.remove(eid)
                            }
                        })
                        .addOnFailureListener { e -> startupError = "Connect failed: ${e.message}" }
                }
                override fun onEndpointLost(endpointId: String) { connectionAttempts.remove(endpointId) }
            },
            options
        ).addOnSuccessListener { isDiscovering = true }
            .addOnFailureListener { e -> startupError = "Discovery failed: ${e.message}" }
    }

    // ---- Approvals ----
    fun approvePeer(p: Peer) {
        connectionsClient.acceptConnection(p.endpointId, payloadCallback)
            .addOnSuccessListener {
                val roster = peers.filter { it.state == PeerState.APPROVED || it.state == PeerState.ME }
                    .joinToString(",") { "${it.username}:${Crypto.encodeKeyToString(it.publicKey)}" }
                connectionsClient.sendPayload(p.endpointId, Payload.fromBytes("WELCOME|$roster".toByteArray(StandardCharsets.UTF_8)))
                p.state = PeerState.APPROVED
                messages = messages + Message(UUID.randomUUID().toString(), "System", "${p.username} has joined.", System.currentTimeMillis().toString(), false, MessageType.SYSTEM)
                peerToApprove = null
                if (peers.count { it.state == PeerState.APPROVED } == 1) currentScreen.value = Screen.Chat
            }
            .addOnFailureListener { e -> startupError = "Approve failed: ${e.message}" }
    }
    fun rejectPeer(p: Peer) { connectionsClient.rejectConnection(p.endpointId); peers.remove(p); peerToApprove = null }

    fun stopAll() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        peers.clear(); processedMessageIds.clear(); messages = emptyList()
        isAdvertising = false; isDiscovering = false
    }

    LaunchedEffect(permissionsGranted, isHost) {
        if (permissionsGranted && currentScreen.value == Screen.Discovery) {
            if (isHost) startAdvertising() else startDiscovery()
        }
    }

    // ---- UI scaffolding ----
    GhostNetTheme {
        Surface(Modifier.fillMaxSize(), color = CyberDarkBlue) {
            when (currentScreen.value) {
                Screen.Onboarding -> OnboardingScreen { u, p, host ->
                    username = u; password = p; isHost = host
                    peers.clear(); messages = emptyList()
                    requestPermissions()
                    currentScreen.value = Screen.Discovery
                }
                Screen.Discovery -> DiscoveryScreen(
                    isAdvertising, isDiscovering, startupError,
                    peers.count { it.state == PeerState.APPROVED },
                    onStartChat = { currentScreen.value = Screen.Chat },
                    onStop = { stopAll(); currentScreen.value = Screen.Onboarding },
                    isHost = isHost
                )
                Screen.Chat -> ChatScreen(
                    messages = messages,
                    onSendMessage = { txt ->
                        val ts = System.currentTimeMillis().toString()
                        val id = UUID.randomUUID().toString()
                        val enc = Crypto.encrypt(txt, password)
                        val data = "$id|$username|$enc|$ts|$INITIAL_TTL"
                        val sig = Crypto.signData(data, keyPair.private)
                        val wire = Payload.fromBytes("MSG|$data|$sig".toByteArray(StandardCharsets.UTF_8))
                        if (processedMessageIds.add(id)) messages = messages + Message(id, username, txt, ts, true)
                        peers.filter { it.state == PeerState.APPROVED }.forEach { connectionsClient.sendPayload(it.endpointId, wire) }
                    },
                    onSendFile = { pickedUri, name, mime, size ->
                        // 1) Copy to our cache (also used for sender's "Open")
                        val cacheFile = File(appCtx.cacheDir, name.ifBlank { "ghostnet_${System.currentTimeMillis()}" })
                        appCtx.contentResolver.openInputStream(pickedUri)?.use { input ->
                            FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                        }
                        val localViewUri = FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", cacheFile)

                        // 2) Build FILE payload from the real cache File (not PFD)
                        val filePayload = Payload.fromFile(cacheFile)
                        sizeByPayloadId[filePayload.id] = size
                        outgoingCacheFile[filePayload.id] = cacheFile // delete when SUCCESS

                        // 3) Build & send header, then the file per peer
                        val metaId = UUID.randomUUID().toString()
                        val ts = System.currentTimeMillis().toString()
                        val metaStr = "FILE|$metaId|$username|$name|$mime|$size|$ts|$INITIAL_TTL|${filePayload.id}|"
                        val sig = Crypto.signData(metaStr, keyPair.private)
                        val header = Payload.fromBytes("$metaStr$sig".toByteArray(StandardCharsets.UTF_8))

                        messages = messages + Message(metaId, username, name, ts, true, MessageType.FILE, mime, localViewUri, size, 0f, false)
                        outgoingPayloadToMessage[filePayload.id] = metaId

                        val targets = peers.filter { it.state == PeerState.APPROVED }.map { it.endpointId }
                        targets.forEach { eid ->
                            connectionsClient.sendPayload(eid, header)
                                .addOnSuccessListener { connectionsClient.sendPayload(eid, filePayload) }
                                .addOnFailureListener { e -> Log.e(TAG, "Header send failed: $e") }
                        }
                    },
                    onStop = { stopAll(); currentScreen.value = Screen.Onboarding },
                    peerCount = peers.count { it.state == PeerState.APPROVED },
                    context = appCtx
                )
            }
            peerToApprove?.let { p -> ApprovalDialog(p, onApprove = { approvePeer(it) }, onDecline = { rejectPeer(it) }) }
        }
    }
}

// ---------- File helpers ----------
private fun saveIncomingToCache(ctx: Context, tmpFile: File, name: String): Uri {
    val safeName = name.ifBlank { "ghostnet_${System.currentTimeMillis()}" }
    val outFile = File(ctx.cacheDir, safeName)
    FileInputStream(tmpFile).use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", outFile)
}

private fun openFile(ctx: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(Intent.createChooser(intent, "Open with")) }
        .onFailure { Log.e(TAG, "Open failed", it) }
}

// ---------- UI ----------
@Composable
fun ApprovalDialog(peer: Peer, onApprove: (Peer) -> Unit, onDecline: (Peer) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Connection Request", color = CyberLightText) },
        text = { Text("'${peer.username}' wants to join this channel. Approve?", color = CyberGrayText) },
        confirmButton = { Button(onClick = { onApprove(peer) }, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)) { Text("Approve", color = CyberDarkBlue) } },
        dismissButton = { Button(onClick = { onDecline(peer) }, colors = ButtonDefaults.buttonColors(containerColor = CyberGrayText)) { Text("Decline", color = CyberDarkBlue) } },
        containerColor = CyberLightBlue
    )
}

@Composable
fun DiscoveryScreen(
    isAdvertising: Boolean,
    isDiscovering: Boolean,
    startupError: String?,
    connectedPeerCount: Int,
    onStartChat: () -> Unit,
    onStop: () -> Unit,
    isHost: Boolean
) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (isHost) "Hosting Channel" else "Joining Channel", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
        Spacer(Modifier.height(24.dp))
        StatusIndicator("Advertising", isAdvertising)
        Spacer(Modifier.height(8.dp))
        StatusIndicator("Discovering", isDiscovering)
        Spacer(Modifier.height(16.dp))
        if (startupError != null) {
            Text("Error: $startupError", color = Color.Red, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().background(Color.Red.copy(alpha = 0.1f)).padding(8.dp))
        } else {
            CircularProgressIndicator(color = CyberCyan)
            Spacer(Modifier.height(12.dp))
            Text(if (isHost) "Waiting for peers to join." else "Scanning for host.", color = CyberGrayText)
        }
        Spacer(Modifier.height(24.dp))
        Text("Approved Peers: $connectedPeerCount", color = CyberLightText)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onStartChat, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDarkBlue)) { Text("Go to Chat", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStop, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = CyberGrayText, contentColor = CyberDarkBlue)) { Text("Leave Channel", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun StatusIndicator(label: String, isActive: Boolean) {
    Row(Modifier.fillMaxWidth(0.6f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("$label Status:", color = CyberGrayText)
        Text(if (isActive) "Active" else "Inactive", color = if (isActive) Color.Green else Color.Red, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun OnboardingScreen(onFinished: (String, String, Boolean) -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        when (step) {
            1 -> OnboardingContent("Welcome to GhostNet", "Secure P2P Communication", {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GhostNetLogo(); Spacer(Modifier.height(24.dp))
                    Text("GhostNet", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
                    Spacer(Modifier.height(8.dp))
                    Text("Secure • Anonymous • Decentralized", color = CyberGrayText, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Connect and chat with nearby devices using a Bluetooth mesh network. No internet required, no accounts needed.", color = CyberGrayText, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }, "Continue", { step = 2 })
            2 -> OnboardingContent("How It Works", "Invitation-Only Mesh Network", {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    FeatureItem("Bluetooth Mesh", "Connect directly to nearby devices without internet or cellular networks.")
                    FeatureItem("Web of Trust", "New users must be approved by an existing member to join the channel.")
                    FeatureItem("Signed Messages", "Cryptographic signatures prevent message forgery.")
                    FeatureItem("End-to-End Encryption", "Only channel members can read messages.")
                }
            }, "Continue", { step = 3 })
            3 -> OnboardingContent("Privacy First", "Your Anonymity Guaranteed", {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    FeatureItem("No Personal Info", "No phone, email, or account needed.")
                    FeatureItem("No Cloud Logs", "Nothing is stored on a server.")
                    FeatureItem("Session-Only", "Messages disappear when you leave.")
                }
            }, "I Understand", { step = 4 })
            4 -> OnboardingContent("Choose Your Name", "Set a temporary username for this session.", {
                OutlinedTextField(
                    value = username, onValueChange = { username = it }, label = { Text("Temporary Username") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(0.8f),
                    colors = TextFieldDefaults.colors(focusedContainerColor = CyberLightBlue, unfocusedContainerColor = CyberLightBlue,
                        focusedIndicatorColor = CyberCyan, cursorColor = CyberCyan,
                        focusedTextColor = CyberLightText, unfocusedTextColor = CyberLightText)
                )
            }, "Next", { step = 5 }, isButtonEnabled = username.length > 2)
            5 -> OnboardingContent("Set Channel Password", "Everyone must enter the same password.", {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Channel Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(0.8f),
                    colors = TextFieldDefaults.colors(focusedContainerColor = CyberLightBlue, unfocusedContainerColor = CyberLightBlue,
                        focusedIndicatorColor = CyberCyan, cursorColor = CyberCyan,
                        focusedTextColor = CyberLightText, unfocusedTextColor = CyberLightText)
                )
            }, "Continue", { }, isButtonVisible = false, extraButtons = {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Button(onClick = { onFinished(username, password, true) }, enabled = password.length >= 4, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDarkBlue)) { Text("Create Channel (Host)", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onFinished(username, password, false) }, enabled = password.length >= 4, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberLightBlue, contentColor = CyberLightText)) { Text("Join Channel", fontWeight = FontWeight.Bold) }
                }
            })
        }
    }
}

@Composable
fun OnboardingContent(
    title: String, subtitle: String, content: @Composable () -> Unit,
    buttonText: String, onNext: () -> Unit,
    isButtonEnabled: Boolean = true, isButtonVisible: Boolean = true,
    extraButtons: (@Composable () -> Unit)? = null
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = CyberGrayText)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
        extraButtons?.invoke()
        if (isButtonVisible) {
            Button(onClick = onNext, enabled = isButtonEnabled, modifier = Modifier.fillMaxWidth(0.8f).padding(bottom = 24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDarkBlue)) { Text(buttonText, fontWeight = FontWeight.Bold) }
        } else Spacer(Modifier.height(24.dp))
    }
}

@Composable fun FeatureItem(title: String, desc: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Text(title, color = CyberLightText, fontWeight = FontWeight.Bold)
        Text(desc, color = CyberGrayText, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<Message>,
    onSendMessage: (String) -> Unit,
    onSendFile: (uri: Uri, name: String, mime: String, size: Long) -> Unit,
    onStop: () -> Unit,
    peerCount: Int,
    context: Context
) {
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val name = queryDisplayName(context, uri) ?: "attachment"
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val size = querySize(context, uri) ?: -1L
        onSendFile(uri, name, mime, size)
    }

    LaunchedEffect(messages.size) { scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GhostNet | $peerCount Peers") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberLightBlue, titleContentColor = CyberLightText),
                actions = { TextButton(onClick = onStop) { Text("LEAVE", color = CyberCyan, fontWeight = FontWeight.Bold) } }
            )
        },
        bottomBar = {
            MessageInputWithAttach(
                messageText = text,
                onMessageChange = { text = it },
                onSend = { if (text.isNotBlank()) { onSendMessage(text); vibrate(context); text = "" } },
                onAttach = { pickFile.launch(arrayOf("*/*")) }
            )
        },
        containerColor = CyberDarkBlue
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (messages.isEmpty()) item { SystemMessageBubble("Waiting for peers to join.") }
            items(messages, key = { it.id }) { m ->
                when (m.type) {
                    MessageType.SYSTEM -> SystemMessageBubble(m.text)
                    MessageType.USER -> MessageBubble(m)
                    MessageType.FILE -> FileBubble(m, context)
                }
            }
        }
    }
}

@Composable
fun FileBubble(m: Message, context: Context) {
    val alignment = if (m.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
    val bg = if (m.isFromMe) CyberCyan else CyberLightBlue
    val fg = if (m.isFromMe) CyberDarkBlue else CyberLightText
    val isImage = (m.fileMime ?: "").startsWith("image/")

    Box(Modifier.fillMaxWidth().padding(start = if (m.isFromMe) 48.dp else 0.dp, end = if (m.isFromMe) 0.dp else 48.dp), contentAlignment = alignment) {
        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(12.dp),
            horizontalAlignment = if (m.isFromMe) Alignment.End else Alignment.Start) {

            if (!m.isFromMe) Text(m.senderUsername, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyberCyan)
            Text(m.text, color = fg)

            if (isImage && m.fileUri != null) {
                Spacer(Modifier.height(8.dp))
                val bmp = remember(m.fileUri) {
                    try {
                        context.contentResolver.openInputStream(m.fileUri)?.use { ins ->
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            BitmapFactory.decodeStream(ins, null, opts)
                        }
                    } catch (_: Exception) { null }
                }
                bmp?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "preview",
                    modifier = Modifier.fillMaxWidth(0.8f).heightIn(min = 120.dp, max = 220.dp).clip(RoundedCornerShape(10.dp))) }
            }

            m.transferProgress?.let { p ->
                if (p < 1f) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { p },
                        color = if (m.isFromMe) CyberDarkBlue else CyberCyan,
                        trackColor = if (m.isFromMe) CyberLightBlue else CyberDarkBlue,
                        modifier = Modifier.fillMaxWidth(0.8f).height(6.dp).clip(RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(2.dp))
                    Text("${(p * 100).toInt()}%", color = fg, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { m.fileUri?.let { openFile(context, it, m.fileMime ?: "*/*") } },
                enabled = m.fileUri != null,
                colors = ButtonDefaults.buttonColors(containerColor = if (m.isFromMe) CyberDarkBlue else CyberCyan,
                    contentColor = if (m.isFromMe) CyberCyan else CyberDarkBlue),
                shape = RoundedCornerShape(8.dp)
            ) { Text(if (m.fileUri != null) "Open" else "Waiting…") }
        }
    }
}

@Composable fun SystemMessageBubble(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(text, color = CyberGrayText, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.background(CyberLightBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
fun MessageBubble(m: Message) {
    val alignment = if (m.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
    val bg = if (m.isFromMe) CyberCyan else CyberLightBlue
    val fg = if (m.isFromMe) CyberDarkBlue else CyberLightText
    Box(Modifier.fillMaxWidth().padding(start = if (m.isFromMe) 48.dp else 0.dp, end = if (m.isFromMe) 0.dp else 48.dp), contentAlignment = alignment) {
        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = if (m.isFromMe) Alignment.End else Alignment.Start) {
            if (!m.isFromMe) Text(m.senderUsername, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyberCyan)
            Text(m.text, color = fg)
        }
    }
}

@Composable
fun MessageInputWithAttach(messageText: String, onMessageChange: (String) -> Unit, onSend: () -> Unit, onAttach: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(CyberLightBlue).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onAttach, modifier = Modifier.padding(end = 4.dp)) { Text("Attach", color = CyberCyan, fontWeight = FontWeight.Bold) }
        TextField(value = messageText, onValueChange = onMessageChange, modifier = Modifier.weight(1f),
            placeholder = { Text("Enter message.", color = CyberGrayText) },
            colors = TextFieldDefaults.colors(focusedContainerColor = CyberDarkBlue, unfocusedContainerColor = CyberDarkBlue,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = CyberCyan, focusedTextColor = CyberLightText, unfocusedTextColor = CyberLightText))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(48.dp).clip(CircleShape).background(CyberCyan).clickable(onClick = onSend), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = CyberDarkBlue)
        }
    }
}

fun vibrate(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    } else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(50)
}

@Composable
fun GhostNetLogo() {
    val p = animateFloatAsState(targetValue = 1f, label = "logo").value
    Canvas(Modifier.size(80.dp)) {
        val w = 8.dp.toPx()
        drawArc(color = CyberCyan.copy(alpha = 0.5f), startAngle = 0f, sweepAngle = 360f * p, useCenter = false, style = Stroke(width = w))
        drawArc(color = CyberCyan, startAngle = 135f, sweepAngle = 270f * p, useCenter = false, style = Stroke(width = w))
        drawCircle(color = CyberCyan, radius = w * 0.75f * p, center = center)
    }
}

@Composable fun GhostNetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = CyberCyan, background = CyberDarkBlue, surface = CyberLightBlue), content = content)
}

// ---------- small utils ----------
private fun queryDisplayName(ctx: Context, uri: Uri): String? =
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null }

private fun querySize(ctx: Context, uri: Uri): Long? =
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
        ?.use { c -> val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE); if (c.moveToFirst() && i >= 0) c.getLong(i) else null }
