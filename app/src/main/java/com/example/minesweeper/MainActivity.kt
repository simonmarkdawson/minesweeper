package com.example.minesweeper

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.location.*
import okhttp3.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

class MainActivity : ComponentActivity() {
    private val skipIntro = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var webSocketManager: WebSocketManager
    private var locationCallback: LocationCallback? = null
    private var isBoomed = false
    private val currentLocation = mutableStateOf<GeoPoint?>(null)
    private val boomLocation = mutableStateOf<GeoPoint?>(null)
    private val locationHistory = mutableStateListOf<GeoPoint>()
    
    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPrefs = getSharedPreferences("MinesweeperPrefs", Context.MODE_PRIVATE)
        Configuration.getInstance().userAgentValue = packageName
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        webSocketManager = WebSocketManager()
        webSocketManager.onConnected = { sendHello() }

        // Only auto-initialize if we already have a name
        val savedName = sharedPrefs.getString("player_name", null)
        if (savedName != null) {
            initializeSystems()
        }
// Test comment
        setContent {
            val navController = rememberNavController()

            LaunchedEffect(navController) {
                webSocketManager.onMessageReceived = { message ->
                    handleIncomingMessage(message, navController)
                }
            }

            val startDestination = if (skipIntro) {
                if (sharedPrefs.getString("player_name", null) == null) "registration_screen" else "lobby_screen"
            } else {
                "title_screen"
            }

            NavHost(navController = navController, startDestination = startDestination) {
                composable("title_screen") {
                    TitleScreen(onVideoFinished = {
                        runOnUiThread {
                            val hasName = sharedPrefs.getString("player_name", null) != null
                            val dest = if (hasName) "lobby_screen" else "registration_screen"
                            navController.navigate(dest) { popUpTo("title_screen") { inclusive = true } }
                        }
                    })
                }
                composable("registration_screen") {
                    RegistrationScreen(onNameSaved = { name ->
                        sharedPrefs.edit { putString("player_name", name) }
                        initializeSystems() // Connect now that we have a name
                        navController.navigate("lobby_screen") { popUpTo("registration_screen") { inclusive = true } }
                    })
                }
                composable("lobby_screen") { LobbyScreen() }
                composable("map_screen") { MinesweeperOsmScreen(currentLocation.value, locationHistory) }
                composable("boom_screen") { BoomScreen(boomLocation.value) }
                composable("halted_screen") { HaltedScreen() }
            }
        }
    }

    private fun initializeSystems() {
        webSocketManager.connect("ws://cloudninesoftware.com:3003")
        startLocationUpdates()
    }

    private fun handleIncomingMessage(message: String, navController: NavController) {
        try {
            if (message.trim().startsWith("{")) {
                val json = JSONObject(message)
                when (json.optString("type")) {
                    "gameState" -> {
                        val state = json.optString("state")
                        runOnUiThread {
                            val route = navController.currentDestination?.route
                            if (route == "title_screen" || route == "registration_screen") return@runOnUiThread
                            
                            when (state) {
                                "lobby" -> {
                                    isBoomed = false
                                    locationHistory.clear()
                                    if (route != "lobby_screen") navController.navigate("lobby_screen") { popUpTo(0) }
                                }
                                "active" -> {
                                    isBoomed = false
                                    if (route != "map_screen") navController.navigate("map_screen") { popUpTo(0) }
                                }
                                "halted" -> {
                                    if (route != "halted_screen") navController.navigate("halted_screen") { popUpTo(0) }
                                }
                            }
                        }
                    }
                    "boom" -> triggerBoom(navController)
                }
            }
        } catch (e: Exception) { Log.e("Minesweeper", "Error", e) }
    }

    private fun triggerBoom(navController: NavController) {
        if (!isBoomed) {
            isBoomed = true
            boomLocation.value = currentLocation.value
            stopLocationUpdates()
            runOnUiThread {
                navController.navigate("boom_screen") { popUpTo(0) }
            }
        }
    }

    private fun startLocationUpdates() {
        if (locationCallback != null) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!isBoomed) result.lastLocation?.let {
                    val p = GeoPoint(it.latitude, it.longitude)
                    currentLocation.value = p
                    locationHistory.add(p)
                    sendToServer(it.latitude, it.longitude)
                }
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun sendHello() {
        val name = sharedPrefs.getString("player_name", "Unknown")
        webSocketManager.sendMessage("{\"type\": \"hello\", \"deviceId\": \"$name\"}")
    }

    private fun sendToServer(lat: Double, lon: Double) {
        val name = sharedPrefs.getString("player_name", "Unknown")
        webSocketManager.sendMessage("{\"type\": \"location\", \"deviceId\": \"$name\", \"lat\": $lat, \"lon\": $lon}")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        webSocketManager.disconnect()
    }
}

class WebSocketManager {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    var onMessageReceived: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null

    fun connect(url: String) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { onConnected?.invoke() }
            override fun onMessage(webSocket: WebSocket, text: String) { onMessageReceived?.invoke(text) }
        })
    }
    fun sendMessage(msg: String) { webSocket?.send(msg) }
    fun disconnect() { webSocket?.close(1000, "Closed") }
}

@Composable
fun RegistrationScreen(onNameSaved: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        CornerAccents(Color.Red.copy(alpha = 0.5f))
        Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("IDENTIFY YOURSELF", style = TextStyle(color = Color.Red, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp))
            Spacer(Modifier.height(48.dp))
            Box(Modifier.fillMaxWidth().height(60.dp).border(1.dp, Color.Red.copy(0.3f)).background(Color.White.copy(0.05f)), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) Text("ENTER NAME", Modifier.padding(16.dp), style = TextStyle(color = Color.White.copy(0.3f), fontSize = 14.sp))
                BasicTextField(name, { name = it.uppercase() }, Modifier.fillMaxWidth().padding(16.dp), textStyle = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold), cursorBrush = SolidColor(Color.Red))
            }
            Spacer(Modifier.height(32.dp))
            Button({ if (name.isNotBlank()) onNameSaved(name.trim()) }, Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red), enabled = name.isNotBlank()) {
                Text("INITIALIZE", style = TextStyle(color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 3.sp))
            }
        }
    }
}

@Composable
fun TitleScreen(onVideoFinished: () -> Unit) {
    val context = LocalContext.current
    var isFinished by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { if (!isFinished) { isFinished = true; onVideoFinished() } }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    val resId = context.resources.getIdentifier("intro", "raw", context.packageName)
                    if (resId != 0) {
                        setVideoURI("android.resource://${context.packageName}/$resId".toUri())
                        setOnCompletionListener { if (!isFinished) { isFinished = true; onVideoFinished() } }
                        start()
                    } else onVideoFinished()
                }
            }
        )
    }
}

@Composable
fun LobbyScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("MINESWEEPER", style = TextStyle(color = Color.Red, fontSize = 22.sp, fontWeight = FontWeight.Black, shadow = Shadow(Color.Red, blurRadius = 25f)))
            Spacer(Modifier.height(60.dp))
            Text("STATUS: WAITING FOR COMMANDS", style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
        CornerAccents(Color.Red.copy(alpha = 0.5f))
    }
}

@Composable
fun MinesweeperOsmScreen(location: GeoPoint?, history: List<GeoPoint>, isBoom: Boolean = false) {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true) } }
    val marker = remember { Marker(mapView).apply { 
        val d = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_mylocation)?.mutate()
        d?.colorFilter = PorterDuffColorFilter(android.graphics.Color.RED, PorterDuff.Mode.SRC_IN)
        icon = d; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    } }
    LaunchedEffect(location) { location?.let {
        mapView.controller.animateTo(it); mapView.controller.setZoom(18.0)
        if (isBoom) { marker.position = it; if (!mapView.overlays.contains(marker)) mapView.overlays.add(marker) }
        else {
            val historyOverlay = HistoryOverlay(history.toList())
            mapView.overlays.clear(); mapView.overlays.add(historyOverlay)
        }
        mapView.invalidate()
    } }
    AndroidView({ mapView }, Modifier.fillMaxSize())
}

class HistoryOverlay(private val history: List<GeoPoint>) : Overlay() {
    private val paint = Paint().apply { color = android.graphics.Color.RED; style = Paint.Style.FILL; isAntiAlias = true }
    private val point = Point()
    override fun draw(c: Canvas, m: MapView, s: Boolean) {
        if (s) return
        history.forEach { m.projection.toPixels(it, point); c.drawCircle(point.x.toFloat(), point.y.toFloat(), 8f, paint) }
    }
}

@Composable
fun BoomScreen(loc: GeoPoint?) {
    val context = LocalContext.current
    var isDone by remember { mutableStateOf(false) }
    var isOutro by remember { mutableStateOf(false) }
    if (isDone) MinesweeperOsmScreen(loc, emptyList(), true)
    else Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    val bId = context.resources.getIdentifier("bombvid", "raw", context.packageName)
                    val oId = context.resources.getIdentifier("outro", "raw", context.packageName)
                    if (bId != 0) {
                        setVideoURI("android.resource://${context.packageName}/$bId".toUri())
                        setOnCompletionListener {
                            if (!isOutro && oId != 0) {
                                isOutro = true
                                setVideoURI("android.resource://${context.packageName}/$oId".toUri())
                                setOnCompletionListener { isDone = true }
                                start()
                            } else isDone = true
                        }
                        start()
                    } else isDone = true
                }
            }
        )
    }
}

@Composable
fun HaltedScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("SYSTEM HALTED", style = TextStyle(color = Color.Yellow, fontSize = 28.sp, fontWeight = FontWeight.Black))
    }
}

@Composable
fun CornerAccents(color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val s = 2.dp.toPx(); val c = 40.dp.toPx(); val p = 20.dp.toPx()
        drawLine(color, Offset(p, p), Offset(p + c, p), s); drawLine(color, Offset(p, p), Offset(p, p + c), s)
        drawLine(color, Offset(size.width - p, p), Offset(size.width - p - c, p), s); drawLine(color, Offset(size.width - p, p), Offset(size.width - p, p + c), s)
        drawLine(color, Offset(p, size.height - p), Offset(p + c, size.height - p), s); drawLine(color, Offset(p, size.height - p), Offset(p, size.height - p - c), s)
        drawLine(color, Offset(size.width - p, size.height - p), Offset(size.width - p - c, size.height - p), s); drawLine(color, Offset(size.width - p, size.height - p), Offset(size.width - p, size.height - p - c), s)
    }
}
