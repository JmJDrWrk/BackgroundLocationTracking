package com.plcoding.backgroundlocationtracking
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        setContent {
            BackgroundLocationTrackingTheme {
                Surface(color = MaterialTheme.colors.background) {
                    DeviceScreen()
                }
            }
        }
    }

    @Composable
    private fun DeviceScreen() {
        val emailState = rememberSaveable { mutableStateOf(getSavedEmail()) }
        val passwordState = rememberSaveable { mutableStateOf(getSavedPassword()) }

        Column(modifier = Modifier.padding(16.dp)) {
            TextField(
                value = emailState.value,
                onValueChange = { emailState.value = it },
                label = { Text(text = "Enter email") }
            )

            TextField(
                value = passwordState.value,
                onValueChange = { passwordState.value = it },
                label = { Text(text = "Enter password") },
                visualTransformation = PasswordVisualTransformation()
            )

            Button(
                onClick = { saveCredentials(emailState.value, passwordState.value) },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(text = "Login")
            }
        }
    }

    private fun getSavedEmail(): String {
        return sharedPreferences.getString("email", "") ?: ""
    }

    private fun getSavedPassword(): String {
        return sharedPreferences.getString("password", "") ?: ""
    }

    private fun saveCredentials(email: String, password: String) {
        sharedPreferences.edit {
            putString("email", email)
            putString("password", password)
            apply()
        }
    }
}