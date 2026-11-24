package com.voiceflow.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SetupActivity : ComponentActivity() {

    private var hasMicPermission by mutableStateOf(false)
    private var isImeEnabled by mutableStateOf(false)
    private var isImeSelected by mutableStateOf(false)
    private var micDeniedPermanently by mutableStateOf(false)
    private var statusMessage by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshSetupStatus()

        setContent {
            var testInput by remember { mutableStateOf("") }

            val micPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                refreshSetupStatus()
                if (granted) {
                    micDeniedPermanently = false
                    statusMessage = "Microphone approved! Next, enable VoiceFlow in System Settings."
                } else {
                    micDeniedPermanently = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                    statusMessage = if (micDeniedPermanently) {
                        "Microphone denied. Tap Step 1 to open App Settings and enable Microphone."
                    } else {
                        "Microphone permission is required for voice typing."
                    }
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0B0E15),
                    surface = Color(0xFF161C29),
                    primary = Color(0xFF2563EB)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0B0E15))
                        .systemBarsPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "VoiceFlow Keyboard",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (hasMicPermission && isImeEnabled && isImeSelected) {
                            "All set! Tap the box below to test VoiceFlow."
                        } else {
                            "3 quick steps to enable your voice-first keyboard:"
                        },
                        color = if (hasMicPermission && isImeEnabled && isImeSelected) Color(0xFF86EFAC) else Color(0xFF94A3B8),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(28.dp))

                    val step1Active = !hasMicPermission
                    Button(
                        onClick = {
                            if (hasMicPermission) {
                                statusMessage = "Microphone permission is already granted ✓"
                            } else if (micDeniedPermanently) {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", packageName, null)
                                    )
                                )
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .then(
                                if (hasMicPermission) {
                                    Modifier.border(1.dp, Color(0xFF22C55E), RoundedCornerShape(14.dp))
                                } else {
                                    Modifier
                                }
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                hasMicPermission -> Color(0xFF143326)
                                step1Active -> Color(0xFF2563EB)
                                else -> Color(0xFF1E293B)
                            }
                        )
                    ) {
                        Text(
                            text = when {
                                hasMicPermission -> "✓ 1. Microphone Permission Granted"
                                micDeniedPermanently -> "1. Open App Settings for Microphone"
                                else -> "1. Grant Microphone Permission"
                            },
                            color = if (hasMicPermission) Color(0xFF86EFAC) else Color.White,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val step2Active = hasMicPermission && !isImeEnabled
                    Button(
                        onClick = {
                            statusMessage = "Toggle VoiceFlow ON in Keyboard Settings, then press Back."
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .then(
                                if (isImeEnabled) {
                                    Modifier.border(1.dp, Color(0xFF22C55E), RoundedCornerShape(14.dp))
                                } else {
                                    Modifier
                                }
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                isImeEnabled -> Color(0xFF143326)
                                step2Active -> Color(0xFF2563EB)
                                else -> Color(0xFF1E293B)
                            }
                        )
                    ) {
                        Text(
                            text = if (isImeEnabled) "✓ 2. VoiceFlow Enabled in Settings" else "2. Enable VoiceFlow in System Settings",
                            color = if (isImeEnabled) Color(0xFF86EFAC) else Color.White,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val step3Active = hasMicPermission && isImeEnabled && !isImeSelected
                    Button(
                        onClick = {
                            if (!isImeEnabled) {
                                statusMessage = "Complete Step 2 first so VoiceFlow appears in the picker."
                            } else {
                                statusMessage = "Choose VoiceFlow from the keyboard picker."
                                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showInputMethodPicker()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .then(
                                if (isImeSelected) {
                                    Modifier.border(1.dp, Color(0xFF22C55E), RoundedCornerShape(14.dp))
                                } else {
                                    Modifier
                                }
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                isImeSelected -> Color(0xFF143326)
                                step3Active -> Color(0xFF2563EB)
                                else -> Color(0xFF1E293B)
                            }
                        )
                    ) {
                        Text(
                            text = if (isImeSelected) "✓ 3. VoiceFlow is Active Keyboard" else "3. Select VoiceFlow as Active Keyboard",
                            color = if (isImeSelected) Color(0xFF86EFAC) else Color.White,
                            fontSize = 15.sp
                        )
                    }

                    if (statusMessage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = statusMessage,
                            color = Color(0xFF93C5FD),
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = testInput,
                        onValueChange = { testInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Test VoiceFlow here") },
                        placeholder = { Text("Tap here and press the mic pill") },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedLabelColor = Color(0xFF93C5FD),
                            unfocusedLabelColor = Color(0xFF94A3B8),
                            cursorColor = Color(0xFF3B82F6)
                        )
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshSetupStatus()
        }
    }

    private fun refreshSetupStatus() {
        hasMicPermission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasMicPermission) {
            micDeniedPermanently = false
        }

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        isImeEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }

        val defaultIme = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull().orEmpty()
        isImeSelected = defaultIme.startsWith("$packageName/")

        if (hasMicPermission && isImeEnabled && isImeSelected) {
            statusMessage = "VoiceFlow is ready! Tap the test box below."
        } else if (hasMicPermission && isImeEnabled && statusMessage.contains("Keyboard Settings")) {
            statusMessage = "VoiceFlow enabled! Now tap Step 3 to select it."
        }
    }
}
