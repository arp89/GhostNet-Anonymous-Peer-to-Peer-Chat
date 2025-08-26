package com.example.ghostnet // Make sure this package name matches yours!

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

// --- Data classes and constants ---
data class Message(val sender: String, val text: String, val isFromMe: Boolean, val timestamp: Long = System.currentTimeMillis())
data class DiscoveredEndpoint(val id: String, val name: String)

const val SERVICE_ID = "com.example.ghostnet.SERVICE_ID"
const val TAG = "GhostNet"

// --- Theme Colors ---
val CyberDarkBlue = Color(0xFF0A192F)
val CyberLightBlue = Color(0xFF112240)
val CyberCyan = Color(0xFF64FFDA)
val CyberLightText = Color(0xFFCCD6F6)
val CyberGrayText = Color(0xFF8892B0)

// --- Payload Types ---
const val PAYLOAD_TYPE_MESSAGE = "MESSAGE"
const val PAYLOAD_TYPE_TYPING = "TYPING"

class MainActivity : ComponentActivity() {

    private lateinit var connectionsClient: ConnectionsClient

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
                requestPermissions = this::requestPermissions
            )
        }
    }

    private fun requestPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.VIBRATE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.VIBRATE)
        }
        requestMultiplePermissions.launch(requiredPermissions)
    }

    override fun onStop() {
        super.onStop()
        connectionsClient.stopAllEndpoints()
    }
}

@Composable
fun GhostNetApp(
    connectionsClient: ConnectionsClient,
    requestPermissions: () -> Unit
) {
    var showIntroScreen by remember { mutableStateOf(true) }
    var endpointToConfirm by remember { mutableStateOf<DiscoveredEndpoint?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var otherUserIsTyping by remember { mutableStateOf(false) }

    var localUsername by remember { mutableStateOf("") }
    var discoveredEndpoints by remember { mutableStateOf<List<DiscoveredEndpoint>>(emptyList()) }
    var connectedEndpoint by remember { mutableStateOf<DiscoveredEndpoint?>(null) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isAdvertising by remember { mutableStateOf(false) }

    val payloadCallback = remember {
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                payload.asBytes()?.let {
                    val payloadString = String(it, StandardCharsets.UTF_8)
                    val parts = payloadString.split(":", limit = 2)
                    val type = parts.getOrNull(0)
                    val content = parts.getOrNull(1) ?: ""

                    when (type) {
                        PAYLOAD_TYPE_MESSAGE -> {
                            messages = messages + Message(connectedEndpoint?.name ?: "Unknown", content, false)
                            otherUserIsTyping = false
                        }
                        PAYLOAD_TYPE_TYPING -> {
                            otherUserIsTyping = content.toBoolean()
                        }
                    }
                }
            }
            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
        }
    }

    val connectionLifecycleCallback = remember {
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                endpointToConfirm = DiscoveredEndpoint(endpointId, connectionInfo.endpointName)
            }
            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                isConnecting = false
                when (result.status.statusCode) {
                    ConnectionsStatusCodes.STATUS_OK -> {
                        connectionsClient.stopDiscovery()
                        connectionsClient.stopAdvertising()
                        isDiscovering = false
                        isAdvertising = false
                        messages = listOf(Message("System", "Connected to ${connectedEndpoint?.name}", false))
                    }
                    else -> connectedEndpoint = null
                }
            }
            override fun onDisconnected(endpointId: String) {
                messages = messages + Message("System", "Disconnected.", false)
                connectedEndpoint = null
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = CyberDarkBlue) {
        if (showIntroScreen) {
            OnboardingScreen { username ->
                localUsername = if (username.isBlank()) "GhostUser-${(1000..9999).random()}" else username
                requestPermissions()
                showIntroScreen = false
            }
        } else if (connectedEndpoint == null) {
            DiscoveryScreen(
                isDiscovering = isDiscovering,
                isAdvertising = isAdvertising,
                isConnecting = isConnecting,
                discoveredEndpoints = discoveredEndpoints,
                onStartAdvertising = {
                    startAdvertising(connectionsClient, localUsername, connectionLifecycleCallback)
                    isAdvertising = true
                },
                onStartDiscovery = {
                    startDiscovery(connectionsClient) { newEndpoints -> discoveredEndpoints = newEndpoints }
                    isDiscovering = true
                },
                onStopAll = {
                    connectionsClient.stopAllEndpoints()
                    isAdvertising = false
                    isDiscovering = false
                    discoveredEndpoints = emptyList()
                },
                onConnectToEndpoint = { endpoint ->
                    isConnecting = true
                    connectionsClient.requestConnection(localUsername, endpoint.id, connectionLifecycleCallback)
                }
            )
        } else {
            ChatScreen(
                messages = messages,
                isTyping = otherUserIsTyping,
                onSendMessage = { text ->
                    val payloadString = "$PAYLOAD_TYPE_MESSAGE:$text"
                    val payload = Payload.fromBytes(payloadString.toByteArray(StandardCharsets.UTF_8))
                    connectionsClient.sendPayload(connectedEndpoint!!.id, payload)
                    messages = messages + Message(localUsername, text, true)
                },
                onTyping = { isTyping ->
                    val payloadString = "$PAYLOAD_TYPE_TYPING:$isTyping"
                    val payload = Payload.fromBytes(payloadString.toByteArray(StandardCharsets.UTF_8))
                    connectionsClient.sendPayload(connectedEndpoint!!.id, payload)
                },
                connectedTo = connectedEndpoint?.name ?: "Unknown"
            )
        }

        if (endpointToConfirm != null) {
            ConnectionConfirmDialog(
                endpointName = endpointToConfirm!!.name,
                onConfirm = {
                    connectionsClient.acceptConnection(endpointToConfirm!!.id, payloadCallback)
                    connectedEndpoint = endpointToConfirm
                    endpointToConfirm = null
                },
                onDismiss = {
                    connectionsClient.rejectConnection(endpointToConfirm!!.id)
                    endpointToConfirm = null
                }
            )
        }
    }
}

// --- Onboarding Screens ---

@Composable
fun OnboardingScreen(onFinished: (String) -> Unit) {
    var currentPage by remember { mutableIntStateOf(0) }
    var username by remember { mutableStateOf("") }
    val totalPages = 4 // Increased to 4 pages

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberDarkBlue)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OnboardingProgressIndicator(currentPage = currentPage, totalPages = totalPages)

        Box(modifier = Modifier.weight(1f)) {
            when (currentPage) {
                0 -> WelcomePage()
                1 -> HowItWorksPage()
                2 -> PrivacyFirstPage()
                3 -> SetUsernamePage(username = username, onUsernameChange = { username = it })
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { if (currentPage > 0) currentPage-- },
                enabled = currentPage > 0
            ) {
                Text("Back", color = if(currentPage > 0) CyberLightText else Color.Transparent)
            }

            Button(
                onClick = {
                    if (currentPage < totalPages - 1) {
                        currentPage++
                    } else {
                        onFinished(username)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                modifier = Modifier.height(50.dp)
            ) {
                val buttonText = if (currentPage < totalPages - 1) "Continue" else "Enter GhostNet"
                Text(buttonText, color = CyberDarkBlue, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun OnboardingProgressIndicator(currentPage: Int, totalPages: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        for (i in 0 until totalPages) {
            val width by animateFloatAsState(targetValue = if (i == currentPage) 32f else 12f, label = "")
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(4.dp)
                    .width(width.dp)
                    .clip(CircleShape)
                    .background(if (i == currentPage) CyberCyan else CyberGrayText.copy(alpha = 0.5f))
            )
        }
    }
}

@Composable
fun WelcomePage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text("Welcome to GhostNet", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
        Text("Anonymous P2P Communication", fontSize = 16.sp, color = CyberGrayText)
        Spacer(modifier = Modifier.height(32.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(CyberLightBlue.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            GhostNetLogo(modifier = Modifier.size(120.dp))
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text("GhostNet", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
        Text("Secure • Anonymous • Decentralized", color = CyberGrayText, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Connect and chat with nearby devices using Bluetooth mesh networking. No internet required, no accounts needed, complete anonymity.",
            color = CyberLightText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun HowItWorksPage() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("How It Works", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
        Text("Mesh Network Communication", fontSize = 16.sp, color = CyberGrayText)
        Spacer(modifier = Modifier.height(32.dp))

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HowItWorksRow(title = "Bluetooth Mesh", subtitle = "Connect directly to nearby devices without internet or cellular networks.")
            HowItWorksRow(title = "Anonymous Chat", subtitle = "No accounts, no tracking. Your identity remains completely private.")
            HowItWorksRow(title = "P2P Network", subtitle = "Messages route through the mesh, creating a resilient communication network.")
            HowItWorksRow(title = "Zero Metadata", subtitle = "No logs, no history stored. Messages exist only during transmission.")
        }
    }
}

@Composable
fun HowItWorksRow(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CyberLightBlue)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = CyberLightText)
            Text(subtitle, color = CyberGrayText, fontSize = 14.sp)
        }
    }
}

@Composable
fun PrivacyFirstPage() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Privacy First", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
        Text("Your Anonymity Guaranteed", fontSize = 16.sp, color = CyberGrayText)
        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(CyberLightBlue.copy(alpha = 0.5f))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Complete Anonymity", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
            Spacer(modifier = Modifier.height(24.dp))

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrivacyPoint("No personal information required")
                PrivacyPoint("Messages encrypted end-to-end")
                PrivacyPoint("No message history stored")
                PrivacyPoint("Mesh routing for maximum privacy")
            }
        }
    }
}

@Composable
fun SetUsernamePage(username: String, onUsernameChange: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Choose Your Name", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CyberLightText)
        Text("This name will be visible to nearby users.", fontSize = 16.sp, color = CyberGrayText)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Enter a temporary name") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberCyan,
                unfocusedBorderColor = CyberGrayText,
                focusedContainerColor = CyberLightBlue,
                unfocusedContainerColor = CyberLightBlue,
                cursorColor = CyberCyan,
                focusedTextColor = CyberLightText,
                unfocusedTextColor = CyberLightText,
                focusedLabelColor = CyberLightText.copy(alpha = 0.7f),
                unfocusedLabelColor = CyberLightText.copy(alpha = 0.7f)
            )
        )
    }
}

@Composable
fun PrivacyPoint(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(CyberCyan)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, color = CyberLightText)
    }
}


// --- Existing Screens (Discovery, Chat, etc.) ---

@Composable
fun GhostNetLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(80.dp), onDraw = {
        val strokeWidth = 8.dp.toPx()
        drawArc(color = CyberCyan, startAngle = -150f, sweepAngle = 120f, useCenter = false, style = Stroke(width = strokeWidth))
        val size2 = this.size * 0.7f
        val topLeft2 = Offset(x = (this.size.width - size2.width) / 2.0f, y = (this.size.height - size2.height) / 2.0f)
        drawArc(color = CyberCyan, startAngle = -160f, sweepAngle = 140f, useCenter = false, style = Stroke(width = strokeWidth), size = size2, topLeft = topLeft2)
        val size3 = this.size * 0.4f
        val topLeft3 = Offset(x = (this.size.width - size3.width) / 2.0f, y = (this.size.height - size3.height) / 2.0f)
        drawArc(color = CyberCyan, startAngle = -170f, sweepAngle = 160f, useCenter = false, style = Stroke(width = strokeWidth), size = size3, topLeft = topLeft3)
    })
}

fun startAdvertising(client: ConnectionsClient, username: String, callback: ConnectionLifecycleCallback) {
    val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
    client.startAdvertising(username, SERVICE_ID, callback, advertisingOptions)
        .addOnSuccessListener { Log.d(TAG, "Advertising started") }
        .addOnFailureListener { e -> Log.e(TAG, "Advertising failed", e) }
}

fun startDiscovery(client: ConnectionsClient, onEndpointsFound: (List<DiscoveredEndpoint>) -> Unit) {
    val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
    val discoveredEndpoints = mutableListOf<DiscoveredEndpoint>()
    val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo) {
            val newEndpoint = DiscoveredEndpoint(endpointId, discoveredEndpointInfo.endpointName)
            if (!discoveredEndpoints.contains(newEndpoint)) {
                discoveredEndpoints.add(newEndpoint)
                onEndpointsFound(discoveredEndpoints.toList())
            }
        }
        override fun onEndpointLost(endpointId: String) {
            discoveredEndpoints.removeAll { it.id == endpointId }
            onEndpointsFound(discoveredEndpoints.toList())
        }
    }
    client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
        .addOnSuccessListener { Log.d(TAG, "Discovery started") }
        .addOnFailureListener { e -> Log.e(TAG, "Discovery failed", e) }
}

@Composable
fun DiscoveryScreen(
    isDiscovering: Boolean, isAdvertising: Boolean, isConnecting: Boolean, discoveredEndpoints: List<DiscoveredEndpoint>,
    onStartAdvertising: () -> Unit, onStartDiscovery: () -> Unit, onStopAll: () -> Unit,
    onConnectToEndpoint: (DiscoveredEndpoint) -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("GhostNet Discovery", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CyberCyan)
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartAdvertising, enabled = !isAdvertising && !isDiscovering, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)) {
                Text("Advertise", color = CyberDarkBlue)
            }
            Button(onClick = onStartDiscovery, enabled = !isDiscovering && !isAdvertising, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)) {
                Text("Discover", color = CyberDarkBlue)
            }
        }
        Button(onClick = onStopAll, enabled = isAdvertising || isDiscovering, modifier = Modifier.padding(top = 8.dp)) { Text("Stop All") }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Nearby Devices:", style = MaterialTheme.typography.titleMedium, color = CyberLightText)

        if (isConnecting) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top=16.dp)) {
                CircularProgressIndicator()
                Text("Connecting...", color = CyberGrayText, modifier = Modifier.padding(top=8.dp))
            }
        } else if (isDiscovering && discoveredEndpoints.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top=16.dp)) {
                CircularProgressIndicator()
                Text("Scanning for devices...", color = CyberGrayText, modifier = Modifier.padding(top=8.dp))
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(discoveredEndpoints) { endpoint ->
                EndpointItem(endpoint = endpoint) {
                    vibrate(context)
                    onConnectToEndpoint(endpoint)
                }
            }
        }
    }
}

@Composable
fun EndpointItem(endpoint: DiscoveredEndpoint, onConnect: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = ""
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(8.dp))
            .background(CyberLightBlue).clickable(onClick = onConnect).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = endpoint.name, color = CyberLightText, fontWeight = FontWeight.Bold)
        Text(text = "Connect", color = CyberCyan, modifier = Modifier.alpha(alpha))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<Message>, isTyping: Boolean,
    onSendMessage: (String) -> Unit, onTyping: (Boolean) -> Unit,
    connectedTo: String
) {
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
        }
    }

    LaunchedEffect(messageText) {
        onTyping(messageText.isNotEmpty())
    }

    Scaffold(
        containerColor = CyberDarkBlue,
        topBar = {
            TopAppBar(
                title = { Text("Connected to: $connectedTo") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberLightBlue, titleContentColor = CyberLightText)
            )
        },
        bottomBar = {
            MessageInput(messageText = messageText, onMessageChange = { messageText = it }) {
                if (messageText.isNotBlank()) {
                    vibrate(context)
                    onSendMessage(messageText)
                    messageText = ""
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState, modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { message -> MessageBubble(message = message) }
            if (isTyping) {
                item {
                    TypingIndicator()
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isFromMe) 16.dp else 0.dp,
                        bottomEnd = if (message.isFromMe) 0.dp else 16.dp
                    )
                )
                .background(if (message.isFromMe) CyberCyan else CyberLightBlue)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                color = if (message.isFromMe) CyberDarkBlue else CyberLightText
            )
        }
        Text(
            text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = CyberGrayText,
            modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp)
        )
    }
}

@Composable
fun MessageInput(messageText: String, onMessageChange: (String) -> Unit, onSendMessage: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(CyberDarkBlue).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = messageText,
            onValueChange = onMessageChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Enter message...", color = CyberLightText.copy(alpha = 0.7f)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberCyan,
                unfocusedBorderColor = CyberGrayText,
                focusedContainerColor = CyberLightBlue,
                unfocusedContainerColor = CyberLightBlue,
                cursorColor = CyberCyan,
                focusedTextColor = CyberLightText,
                unfocusedTextColor = CyberLightText
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onSendMessage, shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
            modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp),
            enabled = messageText.isNotBlank()
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send Message", tint = CyberDarkBlue)
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ), label = ""
    )
    Row(
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp).alpha(alpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Typing...", color = CyberGrayText, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ConnectionConfirmDialog(endpointName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection Request") },
        text = { Text("Accept connection from $endpointName?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Accept") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Reject") }
        }
    )
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

// --- Preview functions ---

@Preview(showBackground = true, name = "Onboarding Screens")
@Composable
fun OnboardingScreenPreview() {
    MaterialTheme {
        OnboardingScreen(onFinished = {})
    }
}

@Preview(showBackground = true, name = "Discovery Screen")
@Composable
fun DiscoveryScreenPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = CyberDarkBlue) {
            DiscoveryScreen(
                isDiscovering = true, isAdvertising = true, isConnecting = false,
                discoveredEndpoints = listOf(
                    DiscoveredEndpoint("id1", "GhostUser-1234"),
                    DiscoveredEndpoint("id2", "GhostUser-5678")
                ),
                onStartAdvertising = {}, onStartDiscovery = {}, onStopAll = {}, onConnectToEndpoint = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "Chat Screen")
@Composable
fun ChatScreenPreview() {
    MaterialTheme {
        ChatScreen(
            messages = listOf(
                Message("GhostUser-1234", "Hey, are you there?", false),
                Message("Me", "Yeah, I'm here. What's up?", true),
                Message("GhostUser-1234", "Just testing out the GhostNet app. It's pretty cool!", false)
            ),
            isTyping = true,
            onSendMessage = {},
            onTyping = {},
            connectedTo = "GhostUser-1234"
        )
    }
}