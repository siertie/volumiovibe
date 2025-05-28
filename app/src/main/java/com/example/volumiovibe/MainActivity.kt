package com.example.volumiovibe

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

class MainActivity : ComponentActivity() {
    private var socket: Socket? = null
    private val volumioUrl = "http://192.168.1.100:3000" // Replace with your Pi 4's IP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VolumioControlScreen()
        }
        connectToVolumio()
    }

    private fun connectToVolumio() {
        try {
            socket = IO.socket(volumioUrl)
            socket?.on(Socket.EVENT_CONNECT) {
                runOnUiThread {
                    Toast.makeText(this, "Connected to Volumio, yo!", Toast.LENGTH_SHORT).show()
                }
            }
            socket?.on(Socket.EVENT_DISCONNECT) {
                runOnUiThread {
                    Toast.makeText(this, "Volumio disconnected, damn!", Toast.LENGTH_SHORT).show()
                }
            }
            socket?.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Can’t connect to Volumio, shit’s broke!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendVolumioCommand(command: String, data: JSONObject? = null) {
        if (socket?.connected() == true) {
            socket?.emit(command, data)
        } else {
            runOnUiThread {
                Toast.makeText(this, "Ain’t connected to Volumio, fam!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        socket?.disconnect()
    }

    @Composable
    fun VolumioControlScreen() {
        var statusText by remember { mutableStateOf("Volumio Status: Disconnected") }
        val context = LocalContext.current

        // Listen for pushState to update status
        socket?.on("pushState") { args ->
            val state = args[0] as JSONObject
            try {
                val status = state.getString("status")
                val title = state.optString("title", "Nothin’ playin’")
                val artist = state.optString("artist", "")
                runOnUiThread {
                    statusText = "Volumio Status: $status\nNow Playin’: $title by $artist"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = { sendVolumioCommand("play") }) {
                    Text("Play")
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = { sendVolumioCommand("pause") }) {
                    Text("Pause")
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = { sendVolumioCommand("next") }) {
                    Text("Next")
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = { sendVolumioCommand("previous") }) {
                    Text("Previous")
                }
            }
        }
    }
}