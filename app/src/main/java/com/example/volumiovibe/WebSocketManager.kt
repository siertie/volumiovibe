package com.example.volumiovibe

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URISyntaxException

object WebSocketManager {
    private const val volumioUrl = "http://volumio.local:3000"
    private var socket: Socket? = null
    private var isInitialized = false
    private var isConnected = false
    private var connectionListeners = mutableListOf<(Boolean) -> Unit>()
    private val TAG = "WebSocketManager"

    fun initialize() {
        if (isInitialized) return
        try {
            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                transports = arrayOf("websocket")
                query = "EIO=3"
            }
            socket = IO.socket(volumioUrl, opts)
            socket?.on(Socket.EVENT_CONNECT) {
                isConnected = true
                Log.d(TAG, "WebSocket connected")
                notifyConnectionChange(true)
            }
            socket?.on(Socket.EVENT_DISCONNECT) {
                isConnected = false
                Log.w(TAG, "WebSocket disconnected: ${it.joinToString()}")
                notifyConnectionChange(false)
            }
            socket?.on(Socket.EVENT_CONNECT_ERROR) {
                isConnected = false
                Log.e(TAG, "WebSocket error: ${it.joinToString()}")
                notifyConnectionChange(false)
            }
            socket?.connect()
            isInitialized = true
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid WebSocket URL: $e")
        }
    }

    fun isConnected(): Boolean = isConnected && socket?.connected() == true

    fun emit(event: String, data: Any? = null, onResponse: ((Array<Any>) -> Unit)? = null) {
        if (!isConnected()) {
            Log.e(TAG, "WebSocket not connected for event: $event")
            return
        }
        if (data != null) {
            socket?.emit(event, data)
            Log.d(TAG, "Emitted $event: $data")
        } else {
            socket?.emit(event)
            Log.d(TAG, "Emitted $event")
        }
        onResponse?.let { callback ->
            socket?.once(getResponseEvent(event)) { args ->
                CoroutineScope(Dispatchers.Main).launch {
                    Log.d(TAG, "Received response for $event: ${args.joinToString()}")
                    callback(args)
                }
            }
        }
    }

    fun on(event: String, callback: (Array<Any>) -> Unit) {
        socket?.on(event) { args ->
            CoroutineScope(Dispatchers.Main).launch {
                Log.d(TAG, "Received $event: ${args.joinToString()}")
                callback(args)
            }
        }
        Log.d(TAG, "Registered listener for $event")
    }

    fun onConnectionChange(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener)
    }

    private fun notifyConnectionChange(isConnected: Boolean) {
        connectionListeners.forEach { it(isConnected) }
    }

    fun reconnect() {
        if (!isConnected()) {
            CoroutineScope(Dispatchers.IO).launch {
                Log.d(TAG, "Attempting WebSocket reconnect")
                socket?.connect()
                repeat(5) { attempt ->
                    delay(2000)
                    if (isConnected()) {
                        Log.d(TAG, "WebSocket reconnected successfully")
                        return@launch
                    }
                    Log.w(TAG, "Reconnect attempt ${attempt + 1} failed")
                    socket?.connect()
                }
                Log.e(TAG, "Failed to reconnect after 5 attempts")
            }
        }
    }

    private fun getResponseEvent(event: String): String {
        return when (event) {
            "getQueue", "clearQueue", "removeFromQueue", "moveQueue" -> "pushQueue"
            "play", "pause", "next", "prev", "getState", "seek" -> "pushState"
            "search" -> "pushBrowseLibrary"
            "addToQueue" -> "pushQueue"
            else -> "pushState"
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        isInitialized = false
        isConnected = false
        connectionListeners.clear()
        Log.d(TAG, "WebSocket disconnected")
    }
}