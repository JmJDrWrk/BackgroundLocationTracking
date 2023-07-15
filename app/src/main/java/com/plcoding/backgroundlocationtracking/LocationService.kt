package com.plcoding.backgroundlocationtracking

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach


import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import io.socket.engineio.client.transports.WebSocket

import android.media.AudioManager
import android.media.ToneGenerator


class LocationService: Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationClient: LocationClient
    private lateinit var socket: Socket

    private var lastLat = "!";
    private var lastLng = "!";

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize the socket
        println("[RTRD] Initizalizing socketIo connection")
        val authParameters = mapOf(
            "email" to "jroman@ruttradar.com",
            "pass" to "123"
        )
        socket = IO.socket("https://locationsocket.jmjdrwrk.repl.co/?mode=certified-ruttradar-webapp", IO.Options().apply {
            transports = arrayOf(WebSocket.NAME)
            isDebugInspectorInfoEnabled = true
            /*jaimeroman@ruttradar.com*/
            auth = mapOf("token" to "1eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiI2NGIxOTIyNTk5ODZiMjBkYTY0ZDA1Y2IiLCJlbWFpbCI6ImphaW1lcm9tYW5AcnV0dHJhZGFyLmNvbSIsIm5hbWUiOiJKYWltZSIsImlhdCI6MTY4OTM1ODk1OSwiZXhwIjoxNjg5NDQ1MzU5fQ.D_x1t4jADrVOI4oViy-vjmKvx4Y-M0Ow1FZULTqENCk")
        })

        socket.connect()
        socket.on(Socket.EVENT_CONNECT) {
            socket.emit("handshake", "Hello, server!")
            println("[RTRD] Handshake to server emitted")
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) {
            val exception = it[0] as Exception
            println("[RTRD] Socket connection error: ${exception.message}")
        }
       socket.on("emitMyLocation", onLocationRequested)
       socket.on("serverHandshake", onServerHandshake)
       socket.on("authError", onAuthError)
        locationClient = DefaultLocationClient(
            applicationContext,
            LocationServices.getFusedLocationProviderClient(applicationContext)
        )
    }
    private val onAuthError = Emitter.Listener { args ->
        val serverMessage = args[0].toString()
        println("[RTRD] SERVER RESPONSE: " + serverMessage)

    }
    private val onServerHandshake = Emitter.Listener { args ->
        if (args.isNotEmpty()) {
            val serverMessage = args[0].toString()
            println("[RTRD] Server said: $serverMessage")
        }
    }
    private val onLocationRequested = Emitter.Listener {
        println("[RTRD] Someone requested my location")
        val json = JSONObject();
        json.put("latitude", lastLat)
        json.put("longitude", lastLng)
        socket.emit("requestedLocation",json)

        //TOne generator
        val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 1000)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun start() {
        val notification = NotificationCompat.Builder(this, "location")
            .setContentTitle("Tracking location...")
            .setContentText("Location: null")
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setOngoing(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        locationClient
            .getLocationUpdates(4000L)
            .catch { e -> e.printStackTrace() }
            .onEach { location ->
                val lat = location.latitude.toString().takeLast(3)
                val long = location.longitude.toString().takeLast(3)
                val updatedNotification = notification.setContentText("Location: ($lat, $long)")
                notificationManager.notify(1, updatedNotification.build())


                lastLat = lat
                lastLng = long


                val url = "https://locationsocket.jmjdrwrk.repl.co/location"

                val json = JSONObject()
                json.put("latitude", location.latitude)
                json.put("longitude", location.longitude)
                val requestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val client = OkHttpClient()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Handle the error if the request was not successful
                    }
                }
            }
            .launchIn(serviceScope)

        startForeground(1, notification.build())
    }

    private fun stop() {
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }
}