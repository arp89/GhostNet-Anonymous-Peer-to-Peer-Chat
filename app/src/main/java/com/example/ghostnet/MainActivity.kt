package com.example.ghostnet // Make sure this package name matches yours!

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// --- Data classes and constants ---
data class Message(
    val id: String,
    val sender: String,
    val text: String,
    val timestamp: String,
    val isFromMe: Boolean
)
const val SERVICE_ID = "com.example.ghostnet.SERVICE_ID"
const val TAG = "GhostNet"

// --- Theme Colors ---
val CyberDarkBlue = Color(0xFF0A192F)
val CyberLightBlue = Color(0xFF112240)
val CyberCyan = Color(0xFF64FFDA)
val CyberLightText = Color(0xFFCCD6F6)
val CyberGrayText = Color(0xFF8892B0)

class MainActivity : ComponentActivity() {

    private lateinit var connectionsClient: ConnectionsClient
    private val processedMessageIds = Collections.synchronizedSet(HashSet<String>())

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach { Log.d(TAG, "${it.key} = ${it.value}") }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionsClient = Nearby.getConnectionsClient(this)

        setContent {
            GhostNetApp(
                connectionsClient = connectionsClient,
                requestPermissions = this::requestPermissions,
                processedMessageIds = processedMessageIds,
                context = this
            )
        }
    }

    private fun requestPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        requestMultiplePermissions.launch(requiredPermissions)
    }
}

// --- Encryption Helper ---
object Crypto {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"

    private fun getKey(secret: String): SecretKeySpec {
        val sha = MessageDigest.getInstance("SHA-256")
        val key = sha.digest(secret.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(key, ALGORITHM)
    }

    fun encrypt(data: String, secret: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getKey(secret))
            val encryptedBytes = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            ""
        }
    }

    fun decrypt(encryptedData: String, secret: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getKey(secret))
            val decodedBytes = Base64.decode(encryptedData, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            "DECRYPTION_FAILED"
        }
    }

    fun hash(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(digest, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }
}

@Composable
fun GhostNetApp(
    connectionsClient: ConnectionsClient,
    requestPermissions: () -> Unit,
    processedMessageIds: MutableSet<String>,
    context: Context
) {
    val currentScreen = remember { mutableStateOf<Screen>(Screen.Onboarding) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    val connectedEndpoints = remember { mutableStateListOf<String>() }

    // New state variables for debugging
    var isAdvertising by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var startupError by remember { mutableStateOf<String?>(null) }


    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedBytes = payload.asBytes() ?: return
                val dataString = String(receivedBytes, StandardCharsets.UTF_8)
                val parts = dataString.split("|", limit = 4)
                if (parts.size == 4) { // Corrected this line
                    val messageId = parts[0]
                    val sender = parts[1]
                    val encryptedText = parts[2]
                    val timestamp = parts[3]

                    if (processedMessageIds.add(messageId)) {
                        val decryptedText = Crypto.decrypt(encryptedText, password)
                        if (decryptedText != "DECRYPTION_FAILED") {
                            messages = messages + Message(
                                id = messageId,
                                sender = sender,
                                text = decryptedText,
                                timestamp = timestamp,
                                isFromMe = false
                            )
                        }
                        // Forward the message to other connected devices
                        val forwardPayload = Payload.fromBytes(dataString.toByteArray(StandardCharsets.UTF_8))
                        connectedEndpoints.filter { it != endpointId }.forEach {
                            connectionsClient.sendPayload(it, forwardPayload)
                        }
                    }
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                if (!connectedEndpoints.contains(endpointId)) {
                    connectedEndpoints.add(endpointId)
                }
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
        }
    }

    fun startMesh() {
        val passwordHash = Crypto.hash(password)
        val endpointName = "$username:$passwordHash"

        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(
            endpointName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            isAdvertising = true
            startupError = null
            Log.d(TAG, "Advertising started successfully")
        }.addOnFailureListener { e ->
            isAdvertising = false
            startupError = "Advertising failed: ${e.javaClass.simpleName}"
            Log.e(TAG, "Advertising failed", e)
        }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo) {
                    val parts = discoveredEndpointInfo.endpointName.split(":", limit = 2)
                    if (parts.size == 2) {
                        val discoveredName = parts[0]
                        val discoveredHash = parts[1]

                        if (discoveredHash == passwordHash && discoveredName != username) {
                            connectionsClient.requestConnection(username, endpointId, connectionLifecycleCallback)
                        }
                    }
                }
                override fun onEndpointLost(endpointId: String) {}
            },
            discoveryOptions
        ).addOnSuccessListener {
            isDiscovering = true
            startupError = null
            Log.d(TAG, "Discovery started successfully")
        }.addOnFailureListener { e ->
            isDiscovering = false
            startupError = "Discovery failed: ${e.javaClass.simpleName}"
            Log.e(TAG, "Discovery failed", e)
        }
    }

    fun stopAll() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectedEndpoints.clear()
        isAdvertising = false
        isDiscovering = false
        Log.d(TAG, "Stopped all Nearby Connections activities.")
    }

    GhostNetTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = CyberDarkBlue) {
            when (currentScreen.value) {
                is Screen.Onboarding -> OnboardingScreen(onFinished = { u, p ->
                    username = u
                    password = p
                    requestPermissions()
                    startMesh()
                    currentScreen.value = Screen.Discovery
                })
                is Screen.Discovery -> DiscoveryScreen(
                    isAdvertising = isAdvertising,
                    isDiscovering = isDiscovering,
                    startupError = startupError,
                    connectedPeerCount = connectedEndpoints.size,
                    onStartChat = { currentScreen.value = Screen.Chat },
                    onStop = {
                        stopAll()
                        currentScreen.value = Screen.Onboarding
                    }
                )
                is Screen.Chat -> ChatScreen(
                    messages = messages,
                    onSendMessage = { text ->
                        val timestamp = System.currentTimeMillis().toString()
                        val messageId = UUID.randomUUID().toString()
                        val encryptedText = Crypto.encrypt(text, password)
                        val messageString = "$messageId|$username|$encryptedText|$timestamp"
                        val payload = Payload.fromBytes(messageString.toByteArray(StandardCharsets.UTF_8))

                        if (processedMessageIds.add(messageId)) {
                            messages = messages + Message(messageId, username, text, timestamp, true)
                        }

                        connectionsClient.sendPayload(connectedEndpoints.toList(), payload)
                    },
                    onStop = {
                        stopAll()
                        currentScreen.value = Screen.Onboarding
                        messages = emptyList()
                        processedMessageIds.clear()
                    },
                    context = context
                )
            }
        }
    }
}

sealed class Screen {
    data object Onboarding : Screen()
    data object Discovery : Screen()
    data object Chat : Screen()
}

// --- Onboarding Composables ---
@Composable
fun OnboardingScreen(onFinished: (String, String) -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        when (step) {
            1 -> WelcomePage(onNext = { step = 2 })
            2 -> HowItWorksPage(onNext = { step = 3 })
            3 -> PrivacyPage(onNext = { step = 4 })
            4 -> SetUsernamePage(username = username, onUsernameChange = { username = it }, onNext = { step = 5 })
            5 -> SetPasswordPage(
                password = password,
                onPasswordChange = { password = it },
                onFinished = { onFinished(username, password) }
            )
        }
    }
}

@Composable
fun WelcomePage(onNext: () -> Unit) {
    OnboardingContent(
        title = "Welcome to GhostNet",
        subtitle = "Anonymous P2P Communication",
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GhostNetLogo()
                Spacer(Modifier.height(24.dp))
                Text("GhostNet", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
                Spacer(Modifier.height(8.dp))
                Text("Secure • Anonymous • Decentralized", color = CyberGrayText, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Connect and chat with nearby devices using a Bluetooth mesh network. No internet required, no accounts needed, complete anonymity.",
                    color = CyberGrayText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        },
        onNext = onNext,
        buttonText = "Continue"
    )
}

@Composable
fun HowItWorksPage(onNext: () -> Unit) {
    OnboardingContent(
        title = "How It Works",
        subtitle = "Mesh Network Communication",
        content = {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                FeatureItem("Bluetooth Mesh", "Connect directly to nearby devices without internet or cellular networks.")
                FeatureItem("Anonymous Chat", "No accounts, no tracking. Your identity remains completely private.")
                FeatureItem("P2P Network", "Messages route through the mesh, creating a resilient communication network.")
                FeatureItem("End-to-End Encrypted", "Messages are encrypted and can only be read by people with the channel password.")
            }
        },
        onNext = onNext,
        buttonText = "Continue"
    )
}

@Composable
fun PrivacyPage(onNext: () -> Unit) {
    OnboardingContent(
        title = "Privacy First",
        subtitle = "Your Anonymity Guaranteed",
        content = {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                FeatureItem("No Personal Info", "The app never asks for your name, number, or email.")
                FeatureItem("Encrypted Messages", "All messages are end-to-end encrypted with a shared password.")
                FeatureItem("No Message History", "Messages are not stored and disappear when you close the app.")
                FeatureItem("Decentralized", "No central server to log your activity or metadata.")
            }
        },
        onNext = onNext,
        buttonText = "Continue"
    )
}

@Composable
fun SetUsernamePage(username: String, onUsernameChange: (String) -> Unit, onNext: () -> Unit) {
    OnboardingContent(
        title = "Choose Your Name",
        subtitle = "Set a temporary username for this session.",
        content = {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Temporary Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.8f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CyberLightBlue,
                    unfocusedContainerColor = CyberLightBlue,
                    focusedIndicatorColor = CyberCyan,
                    cursorColor = CyberCyan,
                    focusedTextColor = CyberLightText,
                    unfocusedTextColor = CyberLightText,
                    focusedLabelColor = CyberGrayText,
                    unfocusedLabelColor = CyberGrayText
                )
            )
        },
        onNext = onNext,
        buttonText = "Next",
        isButtonEnabled = username.isNotBlank()
    )
}

@Composable
fun SetPasswordPage(password: String, onPasswordChange: (String) -> Unit, onFinished: () -> Unit) {
    OnboardingContent(
        title = "Set Channel Password",
        subtitle = "This password encrypts your messages.",
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(0.8f)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Channel Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = CyberLightBlue,
                        unfocusedContainerColor = CyberLightBlue,
                        focusedIndicatorColor = CyberCyan,
                        cursorColor = CyberCyan,
                        focusedTextColor = CyberLightText,
                        unfocusedTextColor = CyberLightText,
                        focusedLabelColor = CyberGrayText,
                        unfocusedLabelColor = CyberGrayText
                    )
                )
                Spacer(Modifier.height(8.dp))
                Text("Everyone in the chat must use the same password to send and receive messages.", color = CyberGrayText, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        },
        onNext = onFinished,
        buttonText = "Enter GhostNet",
        isButtonEnabled = password.length >= 4
    )
}


@Composable
fun OnboardingContent(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
    onNext: () -> Unit,
    buttonText: String,
    isButtonEnabled: Boolean = true
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Spacer(Modifier.height(64.dp))
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = CyberGrayText)
            Spacer(Modifier.height(48.dp))
            content()
        }
        Button(
            onClick = onNext,
            enabled = isButtonEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberCyan,
                contentColor = CyberDarkBlue,
                disabledContainerColor = CyberGrayText.copy(alpha = 0.5f)
            )
        ) {
            Text(buttonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}


@Composable
fun FeatureItem(title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon removed for max compatibility
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = CyberLightText, fontSize = 16.sp)
            Text(description, color = CyberGrayText, fontSize = 14.sp)
        }
    }
}

// --- Discovery Composable ---
@Composable
fun DiscoveryScreen(
    isAdvertising: Boolean,
    isDiscovering: Boolean,
    startupError: String?,
    connectedPeerCount: Int,
    onStartChat: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("GhostNet Discovery", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
        Spacer(Modifier.height(32.dp))

        // Status Dashboard
        StatusIndicator(label = "Advertising", isActive = isAdvertising)
        Spacer(Modifier.height(8.dp))
        StatusIndicator(label = "Discovering", isActive = isDiscovering)
        Spacer(Modifier.height(16.dp))

        if (startupError != null) {
            Text(
                text = "Error: $startupError",
                color = Color.Red,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Red.copy(alpha = 0.1f))
                    .padding(8.dp)
            )
        } else {
            CircularProgressIndicator(color = CyberCyan)
            Spacer(Modifier.height(16.dp))
            Text("Scanning for devices in the mesh...", color = CyberGrayText)
        }

        Spacer(Modifier.height(32.dp))
        Text("Connected Peers: $connectedPeerCount", fontSize = 18.sp, color = CyberLightText)
        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onStartChat,
            enabled = connectedPeerCount > 0,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberCyan,
                contentColor = CyberDarkBlue,
                disabledContainerColor = CyberGrayText.copy(alpha = 0.5f)
            )
        ) {
            Text("Go to Chat", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onStop,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberGrayText,
                contentColor = CyberDarkBlue
            )
        ) {
            Text("Leave Mesh", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StatusIndicator(label: String, isActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(0.6f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label Status:", color = CyberGrayText)
        Text(
            text = if (isActive) "Active" else "Inactive",
            color = if (isActive) Color.Green else Color.Red,
            fontWeight = FontWeight.Bold
        )
    }
}


// --- Chat Composables ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<Message>,
    onSendMessage: (String) -> Unit,
    onStop: () -> Unit,
    context: Context
) {
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        coroutineScope.launch {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GhostNet Group Chat") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CyberLightBlue,
                    titleContentColor = CyberLightText
                ),
                actions = {
                    TextButton(onClick = onStop) {
                        Text("LEAVE", color = CyberCyan, fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        bottomBar = {
            MessageInput(
                messageText = messageText,
                onMessageChange = { messageText = it },
                onSend = {
                    if (messageText.isNotBlank()) {
                        onSendMessage(messageText)
                        vibrate(context)
                        messageText = ""
                    }
                }
            )
        },
        containerColor = CyberDarkBlue
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    val alignment = if (message.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (message.isFromMe) CyberCyan else CyberLightBlue
    val textColor = if (message.isFromMe) CyberDarkBlue else CyberLightText

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (message.isFromMe) 48.dp else 0.dp,
                end = if (message.isFromMe) 0.dp else 48.dp
            ),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start
        ) {
            if (!message.isFromMe) {
                Text(
                    text = message.sender,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan
                )
            }
            Text(text = message.text, color = textColor)
        }
    }
}

@Composable
fun MessageInput(
    messageText: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CyberLightBlue)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = messageText,
            onValueChange = onMessageChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Enter message...", color = CyberGrayText) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CyberLightBlue,
                unfocusedContainerColor = CyberLightBlue,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = CyberCyan,
                focusedTextColor = CyberLightText,
                unfocusedTextColor = CyberLightText
            )
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(CyberCyan)
                .clickable(onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send Message",
                tint = CyberDarkBlue
            )
        }
    }
}

fun vibrate(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(50)
    }
}

// --- Preview Composables ---
@Preview(showBackground = true, device = "id:pixel_6")
@Composable
fun OnboardingScreenPreview() {
    var step by remember { mutableIntStateOf(1) }
    GhostNetTheme {
        Surface(color = CyberDarkBlue, modifier = Modifier.fillMaxSize()) {
            when (step) {
                1 -> WelcomePage(onNext = { step = 2 })
                2 -> HowItWorksPage(onNext = { step = 3 })
                3 -> PrivacyPage(onNext = { step = 4 })
                4 -> SetUsernamePage(username = "GhostUser", onUsernameChange = {}, onNext = { step = 5 })
                5 -> SetPasswordPage(password = "pass", onPasswordChange = {}, onFinished = {})
            }
        }
    }
}

@Preview(showBackground = true, device = "id:pixel_6")
@Composable
fun ChatScreenPreview() {
    val sampleMessages = listOf(
        Message("id1", "GhostUser-5678", "Hey, are you there?", "10:30", false),
        Message("id2", "Me", "Yeah, what's up?", "10:31", true),
        Message("id3", "GhostUser-5678", "Just testing out the mesh network. This is pretty cool!", "10:32", false)
    )
    GhostNetTheme {
        ChatScreen(
            messages = sampleMessages,
            onSendMessage = {},
            onStop = {},
            context = androidx.compose.ui.platform.LocalContext.current
        )
    }
}

@Composable
fun GhostNetLogo() {
    val animatedProgress = animateFloatAsState(targetValue = 1f, label = "logoAnimation").value
    Canvas(modifier = Modifier.size(80.dp)) {
        val strokeWidth = 8.dp.toPx()
        drawArc(
            color = CyberCyan.copy(alpha = 0.5f),
            startAngle = 0f,
            sweepAngle = 360f * animatedProgress,
            useCenter = false,
            style = Stroke(width = strokeWidth)
        )
        drawArc(
            color = CyberCyan,
            startAngle = 135f,
            sweepAngle = 270f * animatedProgress,
            useCenter = false,
            style = Stroke(width = strokeWidth)
        )
        drawCircle(
            color = CyberCyan,
            radius = strokeWidth * 0.75f * animatedProgress,
            center = center
        )
    }
}

@Composable
fun GhostNetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CyberCyan,
            background = CyberDarkBlue,
            surface = CyberLightBlue
        ),
        content = content
    )
}

