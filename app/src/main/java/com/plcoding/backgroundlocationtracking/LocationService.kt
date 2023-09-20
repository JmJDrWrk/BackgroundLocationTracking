package com.plcoding.backgroundlocationtracking


import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Address
import android.location.Geocoder
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled

import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject

import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

import android.os.PowerManager

import org.threeten.bp.LocalDateTime
import org.threeten.bp.format.DateTimeFormatter

class LocationService: Service() {
    private lateinit var wakeLock: PowerManager.WakeLock
    private val geocoder: Geocoder by lazy { Geocoder(applicationContext) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationClient: LocationClient
    private lateinit var gyroscopeClient: GyroscopeClient
    private var crashed = false
    private lateinit var socket: Socket
    private var lastAccelData = CopyOnWriteArrayList<FloatArray>()
    private var lastGyroData = CopyOnWriteArrayList<FloatArray>()
    private var lastLocationData = JSONObject()
        .put("latitude", "")
        .put("longitude", "")
        .put("vacc", "")
        .put("hacc", "")
        .put("alt", "")
        .put("speed", "")
        .put("speedacc", "")

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

    private fun getLocationUpdateInterval(): Long {
        return sharedPreferences.getLong("getlocationUpdateInterval", 60000L)
    }

    private fun getFallDetection(): Boolean {
        return sharedPreferences.getBoolean("fallDetection", false)
    }
    private fun getShareAllways(): Boolean {
        return sharedPreferences.getBoolean("shareAllways", false)
    }

    private fun getRecordRoute(): Boolean {
        return sharedPreferences.getBoolean("recordRoute", false)
    }

    private fun getRouteName(): String {
        return sharedPreferences.getString("routeName", "unamed") ?: "unamed"
    }

    fun calculateStats(data: List<FloatArray>): Triple<FloatArray, FloatArray, FloatArray> {
        val numAxes = data[0].size
        val maxValues = FloatArray(numAxes) { Float.MIN_VALUE }
        val minValues = FloatArray(numAxes) { Float.MAX_VALUE }
        val sumValues = FloatArray(numAxes) { 0f }

        for (array in data) {
            for (j in 0 until numAxes) {
                val value = array[j]
                maxValues[j] = maxOf(maxValues[j], value)
                minValues[j] = minOf(minValues[j], value)
                sumValues[j] += value
            }
        }

        val averages = FloatArray(numAxes) { sumValues[it] / data.size }
        return Triple(minValues, maxValues, averages)
    }

    private fun getAddress(lastLocalLocationData: JSONObject): String {
//        https://stackoverflow.com/questions/73456748/geocoder-getfromlocation-deprecated
        val addresses = geocoder.getFromLocation(lastLocalLocationData.getString("latitude").toDouble(), lastLocalLocationData.getString("longitude").toDouble() , 1)
        val address = addresses?.firstOrNull()?.getAddressLine(0) ?: "Unknown"
        println("[RTRD] Location geocoded: $address")
        return address
    }

    private fun getSettings():JSONObject{
        val settings = JSONObject()
        settings.put("recordRoute", getRecordRoute())
        settings.put("updateInterval", getLocationUpdateInterval())
        settings.put("shareAllways", getShareAllways())
        settings.put("routeName", getRouteName())
        settings.put("crashed", crashed)
        return settings
    }

    private fun getBucket(serverMessage: String): JSONObject {

        val lastLocalLocationData = lastLocationData

        lastLocalLocationData.put("requestor", JSONObject(serverMessage).getString(("requestor")))
        lastLocalLocationData.put("requested", JSONObject(serverMessage).getString(("requested")))
        lastLocalLocationData.put("settings", getSettings())
        lastLocalLocationData.put("address", getAddress(lastLocalLocationData))

        // Get current date and time
        val currentDateTime = org.threeten.bp.LocalDateTime.now()
        val formattedDateTime = currentDateTime.format(org.threeten.bp.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        // Assuming lastLocalLocationData is a Map<String, String>
        lastLocalLocationData.put("instant", formattedDateTime)


        println("[RTRD] lasts " + lastLocalLocationData.get("latitude") + " " + lastLocalLocationData.get("longitude"))
        val (minGyro, maxGyro, avgGyro) = calculateStats(lastGyroData)
        val (minAccel, maxAccel, avgAccel) = calculateStats(lastAccelData)

        val gyroData = JSONObject()
            .put("x", JSONObject().put("min",minGyro[0].toFloat() ).put("max", maxGyro[0].toFloat() ).put("avg", avgGyro[0].toFloat() ))
            .put("y", JSONObject().put("min",minGyro[1].toFloat() ).put("max", maxGyro[1].toFloat() ).put("avg", avgGyro[1].toFloat() ))
            .put("z", JSONObject().put("min",minGyro[2].toFloat() ).put("max", maxGyro[2].toFloat() ).put("avg", avgGyro[2].toFloat() ))

        val accelData = JSONObject()
            .put("x", JSONObject().put("min",minAccel[0]).put("max", maxAccel[0]).put("avg", avgAccel[0]))
            .put("y", JSONObject().put("min",minAccel[1]).put("max", maxAccel[1]).put("avg", avgAccel[1]))
            .put("z", JSONObject().put("min",minAccel[2]).put("max", maxAccel[2]).put("avg", avgAccel[2]))

//        lastLocalLocationData.put("accelerometer", accelData)
        lastLocalLocationData.put("gyroscope", gyroData)
        lastAccelData = CopyOnWriteArrayList<FloatArray>()
        lastGyroData = CopyOnWriteArrayList<FloatArray>()

        return lastLocalLocationData;
    }

    private fun scheduleLocationUpload() {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWorkRequest = PeriodicWorkRequest.Builder(
            LocationUploadWorker::class.java,
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueue(periodicWorkRequest)
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize the wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::LocationServiceWakeLock")
        wakeLock.acquire()

        sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        gyroscopeClient = GyroscopeClient(applicationContext)

        // Initialize the socket
        println("[RTRD] Initizalizing socketIo connection")

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
        try {
            val serverMessage = args[0].toString()
            println("[RTRD] to/requested " + JSONObject(serverMessage).getString(("requestor")))
            println("[RTRD] from/requestor " + JSONObject(serverMessage).getString(("requested")))
            println("[RTRD] Someone requested my location")
            val lastLocalLocationData = lastLocationData
            val settings = JSONObject()
            println("[RTRD] This is the latitude " + lastLocalLocationData.getString("latitude").isEmpty() )
            if(lastLocalLocationData.getString("latitude").isEmpty() ||
                lastLocalLocationData.getString("longitude").isEmpty() ||
                lastLocalLocationData.getString("speedacc").isEmpty() ||
                lastLocalLocationData.getString("speed").isEmpty() ||
                lastLocalLocationData.getString("alt").isEmpty() ||
                lastLocalLocationData.getString("hacc").isEmpty() ||
                lastLocalLocationData.getString("vacc").isEmpty() ){
                println("[RTRD] RISK UNDEFINED!")
                socket.emit("requestedLocationFailed")
            }else{

                //Get the bucket
                var bucket = getBucket(serverMessage)
                socket.emit("requestedLocation",bucket)

                //TOne generator
                val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                toneGenerator.startTone(3)
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 5000)
            }

        }catch (e: Error){
            println("[RTRD]" +e)
            socket.emit("requestedLocationFailed")
        }
    }
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                start()
                scheduleLocationUpload() // Schedule the periodic work
            }
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
    @RequiresApi(Build.VERSION_CODES.O)
    private fun start() {
        val notification = NotificationCompat.Builder(this, "location")
            .setContentTitle("Tracking location...")
            .setContentText("Location: null")
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setOngoing(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        locationClient
            .getLocationUpdates(1000L)
            .catch { e -> e.printStackTrace() }
            .onEach { location ->

                //LOCATION COMPUTE START
                println(location)
                
                val lat = location.latitude.toString().takeLast(3)
                val long = location.longitude.toString().takeLast(3)
                val updatedNotification = notification.setContentText("Location: ($lat, $long)")
                notificationManager.notify(1, updatedNotification.build())

                lastLocationData = JSONObject()
                    .put("latitude", location.latitude.toString())
                    .put("longitude", location.longitude.toString())
                    .put("vacc", location.verticalAccuracyMeters.toString())
                    .put("hacc", location.accuracy.toString())
                    .put("alt", location.altitude.toString())
                    .put("speed", location.speed.toString())
                    .put("speedacc", location.speedAccuracyMetersPerSecond.toString())

                if(getShareAllways()){
                    println("[RTRD] streaming location -> shareAllways:enabled")
                    var serverMessageMock = JSONObject().put("requestor","device").put("requested",getSavedEmail()).toString()
                    var bucket = getBucket(serverMessageMock)
                    println(bucket)
                    socket.emit("streamLocation", bucket)
                }

            }
            .launchIn(serviceScope)

        gyroscopeClient.getGyroscopeData()
            .onEach { gyroData ->
                lastGyroData.add(gyroData)
                // Do something with gyroData (FloatArray)
                // gyroData[0] -> x-axis, gyroData[1] -> y-axis, gyroData[2] -> z-axis
//                println("[RTRD] [gyro] x: " + gyroData[1] + " y: "+  gyroData[2])
                // Check if any axis exceeds 10
                if (gyroData[0].toFloat() > 8 || gyroData[1].toFloat()  > 8 || gyroData[2].toFloat()  > 8) {
                    //TOne generator
                    val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                    toneGenerator.startTone(3)
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_ANSWER, 10000)
                    crashed = true
                    val lastAccel = lastAccelData[lastAccelData.size-1]
                    socket.emit("crashDetected", getBucket(
                        JSONObject()
                        .put("requestor","device")
                        .put("requested", getSavedEmail()).toString()
                        ).put("instance",JSONObject()
                        .put("gx",gyroData[0].toFloat())
                        .put("gy",gyroData[1].toFloat())
                        .put("gz",gyroData[2].toFloat())
                        .put("ax",lastAccel[0].toFloat())
                        .put("ay",lastAccel[1].toFloat())
                        .put("az",lastAccel[2].toFloat()))
                    )
                }

            }
            .launchIn(serviceScope)

        gyroscopeClient.getAccelerometerData()
            .onEach { accelData ->
                lastAccelData.add(accelData)
                // Do something with accelData (FloatArray)
                // accelData[0] -> x-axis, accelData[1] -> y-axis, accelData[2] -> z-axis
//                println("[RTRD] [accel] x: " + accelData[1] + " y: "+  accelData[2])
            }
            .launchIn(serviceScope)
        startForeground(1, notification.build())
    }

    private fun stop() {
        socket.disconnect()
        socket.on(Socket.EVENT_DISCONNECT) {
            // This code will be executed when the disconnection is successful
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock.release()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_ROUTE_NAME = "route_name"
    }
}

//LOCATION COMPUTE STOP


//                val url = "https://locationsocket.jmjdrwrk.repl.co/location"
//
//
//                val requestBody = lastLocationData.toString().toRequestBody("application/json".toMediaTypeOrNull())
//
//                val request = Request.Builder()
//                    .url(url)
//                    .post(requestBody)
//                    .build()
//
//                val client = OkHttpClient()
//                client.newCall(request).execute().use { response ->
//                    if (!response.isSuccessful) {
//                        // Handle the error if the request was not successful
//                    }
//                }