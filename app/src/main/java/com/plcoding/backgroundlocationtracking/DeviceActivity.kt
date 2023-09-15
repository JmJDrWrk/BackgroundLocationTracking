package com.plcoding.backgroundlocationtracking
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.plcoding.backgroundlocationtracking.ui.theme.BackgroundLocationTrackingTheme

class DeviceActivity : ComponentActivity() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var recordRouteCheckbox: CheckBox
    private lateinit var fallDetectionCheckbox: CheckBox
    private lateinit var locationUpdateIntervalSpinner: Spinner
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.device_activity)
        sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)


        val savedEmail = getSavedEmail()
        val savedPassword = getSavedPassword()
        val recordRoute = getRecordRoute()
        val fallDetection = getFallDetection()
        val selectedInterval = getLocationUpdateInterval()

        emailEditText.setText(savedEmail)
        passwordEditText.setText(savedPassword)

        recordRouteCheckbox = findViewById(R.id.recordRouteCheckbox)
        fallDetectionCheckbox = findViewById(R.id.fallDetectionCheckbox)
        locationUpdateIntervalSpinner = findViewById(R.id.locationUpdateIntervalSpinner)

        // Set the state of checkboxes and spinner
        recordRouteCheckbox.isChecked = recordRoute
        fallDetectionCheckbox.isChecked = fallDetection
        // Set the selected item in the spinner
        val position = resources.getStringArray(R.array.location_update_intervals).indexOf(selectedInterval)
        locationUpdateIntervalSpinner.setSelection(position)


        val loginButton = findViewById<Button>(R.id.savebt)
        loginButton.setOnClickListener {
            val email = emailEditText.text.toString()
            val password = passwordEditText.text.toString()
            saveCredentials(email, password)

            finish()
        }
        // Initialize checkboxes and spinner
        recordRouteCheckbox = findViewById(R.id.recordRouteCheckbox)
        fallDetectionCheckbox = findViewById(R.id.fallDetectionCheckbox)
        locationUpdateIntervalSpinner = findViewById(R.id.locationUpdateIntervalSpinner)
    }


    private fun getSavedEmail(): String {
        return sharedPreferences.getString("email", "") ?: ""
    }

    private fun getSavedPassword(): String {
        return sharedPreferences.getString("password", "") ?: ""
    }
    private fun getRecordRoute(): Boolean {
        return sharedPreferences.getBoolean("recordRoute", false)
    }

    private fun getFallDetection(): Boolean {
        return sharedPreferences.getBoolean("fallDetection", false)
    }

    private fun getLocationUpdateInterval(): String {
        return sharedPreferences.getString("locationUpdateInterval", "1 minute") ?: "1 minute"
    }

    //
    private fun saveCredentials(email: String, password: String) {
        sharedPreferences.edit {
            putString("email", email)
            putString("password", password)
            putBoolean("recordRoute", recordRouteCheckbox.isChecked)
            putBoolean("fallDetection", fallDetectionCheckbox.isChecked)
            putString("locationUpdateInterval", locationUpdateIntervalSpinner.selectedItem.toString())
            apply()
        }
    }
}