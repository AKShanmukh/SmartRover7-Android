package com.example.smartrover7

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var ble: BleConnectionManager
    private lateinit var permLauncher: ActivityResultLauncher<Array<String>>

    private var destinationScreen by mutableStateOf("")
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleConnectionManager(this)

        permLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions: Map<String, Boolean> ->
            if (permissions.values.all { it }) {
                if (isLocationEnabled()) {
                    ble.connect()
                } else {
                    Toast.makeText(this, "Please enable Location/GPS", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            } else {
                destinationScreen = ""
                Toast.makeText(this, "All permissions required!", Toast.LENGTH_SHORT).show()
            }
        }

        setContent {
            MaterialTheme {
                var screen by rememberSaveable { mutableStateOf("home") }
                var showDialog by remember { mutableStateOf<String?>(null) }
                var showLoader by remember { mutableStateOf(false) }
                var showRetry by remember { mutableStateOf(false) }

                val status by ble.status.collectAsState()
                val connected by ble.connected.collectAsState()

                LaunchedEffect(connected) {
                    if (connected) {
                        showLoader = false
                        showRetry = false
                        reconnectAttempts = 0
                        if (destinationScreen.isNotEmpty()) {
                            screen = destinationScreen
                            destinationScreen = ""
                        }
                    } else {
                        if (destinationScreen.isNotEmpty() || screen != "home") {
                            if (reconnectAttempts < maxReconnectAttempts) {
                                reconnectAttempts++
                                showLoader = true
                                delay(1500)
                                ble.connect()
                            } else {
                                showLoader = false
                                showRetry = true
                            }
                        }
                    }
                }

                LaunchedEffect(status) {
                    if (status.startsWith("OBSTACLE")) showDialog = "Obstacle detected!"
                    if (status == "BEACON_LOST") showDialog = "Beacon signal lost!"
                }

                val bg = Brush.verticalGradient(
                    listOf(Color(0xFF0B1220), Color(0xFF0F1730), Color(0xFF121A2A))
                )

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(bg)
                ) {
                    Column {
                        TopStatusBar(connected = connected, status = status)

                        Box(Modifier.weight(1f)) {
                            when (screen) {
                                "home" -> HomeScreen(
                                    onRC = {
                                        destinationScreen = "rcMenu"
                                        showLoader = true
                                        showRetry = false
                                        ensurePermsAndConnect()
                                    },
                                    onManual = {
                                        destinationScreen = "manual"
                                        showLoader = true
                                        showRetry = false
                                        ensurePermsAndConnect()
                                    }
                                )
                                "rcMenu" -> RCMenuScreen(
                                    onBack = {
                                        reconnectAttempts = 0
                                        ble.disconnect()
                                        screen = "home"
                                    },
                                    onBT = { screen = "rcBt" },
                                    onTX = {
                                        ble.send("MODE:MANUAL")
                                        Toast.makeText(this@MainActivity, "Transmitter active", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                "rcBt" -> RCRemoteScreen(
                                    onBack = { screen = "rcMenu" },
                                    onFwd = { ble.send("DRV:FWD") },
                                    onBackCmd = { ble.send("DRV:BACK") },
                                    onLeft = { ble.send("DRV:LEFT") },
                                    onRight = { ble.send("DRV:RIGHT") },
                                    onStop = { ble.send("STOP") },
                                    onSpeed = { ble.send("SPD:$it") }
                                )
                                "manual" -> ManualScreen(
                                    status = status,
                                    onBack = {
                                        reconnectAttempts = 0
                                        ble.disconnect()
                                        stopService(Intent(this@MainActivity, BeaconService::class.java))
                                        screen = "home"
                                    },
                                    onStartFollow = {
                                        ensureBeaconPerms()
                                        startService(
                                            Intent(this@MainActivity, BeaconService::class.java)
                                                .setAction(BeaconService.ACTION_START)
                                        )
                                        ble.send("MODE:FOLLOW")
                                    },
                                    onStopFollow = {
                                        stopService(
                                            Intent(this@MainActivity, BeaconService::class.java)
                                                .setAction(BeaconService.ACTION_STOP)
                                        )
                                        ble.send("STOP")
                                    }
                                )
                            }
                        }
                    }

                    if (showLoader) LoadingOverlay()
                    if (showRetry) RetryOverlay(
                        onRetry = {
                            showRetry = false
                            showLoader = true
                            reconnectAttempts = 0
                            ble.connect()
                        },
                        onCancel = {
                            destinationScreen = ""
                            showRetry = false
                            showLoader = false
                        }
                    )
                    if (showDialog != null) {
                        AlertDialog(
                            onDismissRequest = { showDialog = null },
                            confirmButton = { TextButton(onClick = { showDialog = null }) { Text("OK") } },
                            title = { Text("Smart Rover Alert") },
                            text = { Text(showDialog!!) }
                        )
                    }
                }
            }
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun ensurePermsAndConnect() {
        val perms = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()

        val allGranted = perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

        if (allGranted && isLocationEnabled()) {
            ble.connect()
        } else {
            permLauncher.launch(perms)
        }
    }

    private fun ensureBeaconPerms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            if (!ok) permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE))
        }
    }
}

@Composable
fun TopStatusBar(connected: Boolean, status: String) {
    val bg = if (connected) Color(0xFF0A3F2E) else Color(0xFF3A2020)
    val text = when {
        connected -> "CONNECTED"
        status.contains("Scanning", true) || status.contains("Connecting", true) -> "SEARCHING..."
        else -> "DISCONNECTED"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(bg)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LoadingOverlay() {
    val infinite = rememberInfiniteTransition(label = "loader_pulse")
    val scale by infinite.animateFloat(
        0.75f, 1.1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "loader_scale"
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(enabled = false, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(100.dp).scale(scale).background(Color(0xFF00E5FF), CircleShape))
            Spacer(Modifier.height(14.dp))
            Text("Connecting to Rover…", color = Color.White, fontSize = 18.sp)
        }
    }
}

@Composable
fun RetryOverlay(onRetry: () -> Unit, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(enabled = false, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2A40)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Connection Failed", color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun Title() {
    val t = rememberInfiniteTransition(label = "title_pulse")
    val s by t.animateFloat(
        1f, 1.05f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "title_scale"
    )
    Text("SMART ROVER", color = Color(0xFF00E5FF), fontSize = 30.sp, modifier = Modifier.scale(s))
}

@Composable
fun HomeScreen(onRC: () -> Unit, onManual: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { Title() }
        ModeCard("RC MODE", "Bluetooth remote + Transmitter", onRC)
        Spacer(Modifier.height(20.dp))
        ModeCard("MANUAL MODE", "Human-following + alerts", onManual)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun ModeCard(head: String, sub: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(120.dp).clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF17233F))
    ) {
        Column(Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(head, color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Color(0xFF9FB6D9), fontSize = 14.sp)
        }
    }
}

@Composable
fun RCMenuScreen(onBack: () -> Unit, onBT: () -> Unit, onTX: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("RC MODE", color = Color.White, fontSize = 22.sp)
            TextButton(onClick = onBack) { Text("Back to Home") }
        }
        Spacer(Modifier.height(20.dp))
        ModeCard("Bluetooth Remote", "On-screen D-pad", onBT)
        Spacer(Modifier.height(20.dp))
        ModeCard("Transmitter (FlySky)", "External RC controller", onTX)
    }
}

@Composable
fun RCRemoteScreen(onBack: () -> Unit, onFwd: () -> Unit, onBackCmd: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit, onStop: () -> Unit, onSpeed: (Int) -> Unit) {
    var speed by remember { mutableStateOf(140f) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("RC – Bluetooth Remote", color = Color.White, fontSize = 20.sp)
            TextButton(onClick = onBack) { Text("Back to Menu") }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            RoundBtn("LEFT", onLeft)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RoundBtn("FWD", onFwd)
                Spacer(Modifier.height(12.dp))
                RoundBtn("STOP", onStop)
                Spacer(Modifier.height(12.dp))
                RoundBtn("BACK", onBackCmd)
            }
            RoundBtn("RIGHT", onRight)
        }
        Spacer(Modifier.weight(1f))
        Column {
            Text("Speed: ${speed.toInt()}", color = Color.White)
            Slider(
                value = speed,
                onValueChange = { speed = it },
                onValueChangeFinished = { scope.launch { onSpeed(speed.toInt()) } },
                valueRange = 60f..255f
            )
        }
    }
}

@Composable
fun RoundBtn(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2D53)),
        shape = CircleShape,
        modifier = Modifier.size(92.dp)
    ) {
        Text(label, color = Color.White)
    }
}

@Composable
fun ManualScreen(status: String, onBack: () -> Unit, onStartFollow: () -> Unit, onStopFollow: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("MANUAL MODE", color = Color.White, fontSize = 22.sp)
            TextButton(onClick = onBack) { Text("Back to Home") }
        }
        Spacer(Modifier.height(20.dp))
        Text("Human Following", color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStartFollow) { Text("Start Follow") }
            OutlinedButton(onClick = onStopFollow) { Text("Stop") }
        }
        Spacer(Modifier.height(24.dp))
        Text("Rover Status: $status", color = Color(0xFF9FB6D9))
        Spacer(Modifier.height(12.dp))
        Text(
            "• Starts a phone beacon for the rover to follow.\n• Shows pop-ups based on OBSTACLE and BEACON status messages from the rover.",
            color = Color(0xFF6F86A6), style = MaterialTheme.typography.bodySmall
        )
    }
}