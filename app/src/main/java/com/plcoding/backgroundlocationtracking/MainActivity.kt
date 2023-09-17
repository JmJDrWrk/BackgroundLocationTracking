package com.plcoding.backgroundlocationtracking

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {
    private var routeName: String? = null
    private lateinit var sharedPreferences: SharedPreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Change the view
        setContentView(R.layout.activity_main)

        val deviceButton = findViewById<Button>(R.id.deviceButton)
        deviceButton.setOnClickListener {
            val intent = Intent(this, DeviceActivity::class.java)
            startActivity(intent)
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            0
        )

        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        startButton.setOnClickListener {
            /*Intent(applicationContext, LocationService::class.java).apply {
                action = LocationService.ACTION_START
                startService(this)
            }*/
            showRouteNameDialog()
        }

        stopButton.setOnClickListener {
            Intent(applicationContext, LocationService::class.java).apply {
                action = LocationService.ACTION_STOP
                startService(this)
            }
        }
    }
    private fun showRouteNameDialog() {
        sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if(sharedPreferences.getBoolean("shareAllways",false) and sharedPreferences.getBoolean("recordRoute", false)){
            val dialogBuilder = AlertDialog.Builder(this)
            val inflater = this.layoutInflater
            val dialogView = inflater.inflate(R.layout.dialog_route_name, null)
            dialogBuilder.setView(dialogView)

            val editTextRouteName = dialogView.findViewById<EditText>(R.id.editTextRouteName)

            dialogBuilder.setTitle("Enter Route Name")
            dialogBuilder.setMessage("You are about to share your location. Please enter the name for the new route")
            dialogBuilder.setPositiveButton("Start") { _, _ ->
                routeName = editTextRouteName.text.toString()
                startLocationService()
            }
            dialogBuilder.setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }

            val alertDialog = dialogBuilder.create()
            alertDialog.show()
        }else{
            println("[RTRD] This rutt is not going to be recorded")
        }
    }

    private fun startLocationService() {
        if (routeName != null && routeName!!.isNotBlank()) {
            // Save routeName to SharedPreferences

            val editor = sharedPreferences.edit()
            editor.putString("routeName", routeName)
            editor.apply()

            Intent(applicationContext, LocationService::class.java).apply {
                action = LocationService.ACTION_START
                putExtra(LocationService.EXTRA_ROUTE_NAME, routeName)
                startService(this)
            }
        } else {
            // Handle the case where routeName is empty or null
            // You may want to show a toast or display an error message
        }
    }
}
