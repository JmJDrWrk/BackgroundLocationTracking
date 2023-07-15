package com.plcoding.backgroundlocationtracking

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Address
import android.location.Geocoder
import android.os.IBinder
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
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
import kotlinx.coroutines.*


class LocationService: Service() {
    private val geocoder: Geocoder by lazy { Geocoder(applicationContext) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationClient: LocationClient
    private lateinit var socket: Socket

    private var lastLat = "!";
    private var lastLng = "!";
    private var lastAddress = "!"

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
    private lateinit var sharedPreferences: SharedPreferences

    private fun getSavedEmail(): String {
        return sharedPreferences.getString("email", "") ?: ""
    }

    private fun getSavedPassword(): String {
        return sharedPreferences.getString("password", "") ?: ""
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)


        // Initialize the socket
        println("[RTRD] Initizalizing socketIo connection")
        val authParameters = mapOf(
            "email" to "jroman@ruttradar.com",
            "pass" to "123"
        )
        socket = IO.socket("https://locationsocket.jmjdrwrk.repl.co/?mode=certified-ruttradar-device", IO.Options().apply {
            transports = arrayOf(WebSocket.NAME)
            isDebugInspectorInfoEnabled = true
            auth = mapOf(
                "email" to getSavedEmail(),
                "password" to getSavedPassword()
            )
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
    private val onLocationRequested = Emitter.Listener {args ->

        val serverMessage = args[0].toString()
        println("[RTRD] to/requested " + JSONObject(serverMessage).getString(("requestor")))
        println("[RTRD] from/requestor " + JSONObject(serverMessage).getString(("requested")))


        println("[RTRD] Someone requested my location")
        val json = JSONObject();
        json.put("latitude", lastLat)
        json.put("longitude", lastLng)
        json.put("requestor", JSONObject(serverMessage).getString(("requestor")))
        json.put("requested", JSONObject(serverMessage).getString(("requested")))

        println("[RTRD] lasts " + lastLat + " " + lastLng)
        //Geocode here
        val addresses = geocoder.getFromLocation(lastLat.toDouble(), lastLng.toDouble() , 1)
        val address = addresses?.firstOrNull()?.getAddressLine(0) ?: "Unknown"
        println("[RTRD] Location geocoded: $address")

        json.put("address", address)

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
    private fun formatAddress(address: Address): String {
        val lines = mutableListOf<String>()

        // Add the address lines
        for (i in 0..address.maxAddressLineIndex) {
            lines.add(address.getAddressLine(i))
        }

        // Add the locality, if available
        address.locality?.let { lines.add(it) }

        // Add the postal code, if available
        address.postalCode?.let { lines.add(it) }

        // Add the country name, if available
        address.countryName?.let { lines.add(it) }

        // Join all the lines into a single string
        return lines.joinToString(separator = ", ")
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


                //Last value
                lastLat = location.latitude.toString()
                lastLng = location.longitude.toString()



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
        socket.disconnect()
        socket.on(Socket.EVENT_DISCONNECT) {
            // This code will be executed when the disconnection is successful
            stopForeground(true)
            stopSelf()
        }

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