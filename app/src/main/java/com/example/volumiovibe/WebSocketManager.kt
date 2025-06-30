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
    private const val volumioUrl = "http://192.168.0.250:3000"
    private var socket: Socket? = null
    private var isInitialized = false
    private var isConnected = false
    private var connectionListeners = mutableListOf<(Boolean) -> Unit>()
    private val TAG = "WebSocketManager"

    fun initialize() {
        if (isInitialized && isConnected()) {
            Log.d(TAG, "WebSocket already good, fam")
            return
        }
        try {
            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionAttempts = 5 // Down from 20
                reconnectionDelay = 200 // Down from 500
                reconnectionDelayMax = 1000 // Down from 2000
                timeout = 5000 // Explicit connect timeout
                transports = arrayOf("websocket")
            }
            socket = IO.socket(volumioUrl, opts)
            socket?.on(Socket.EVENT_CONNECT) {
                isConnected = true
                Log.d(TAG, "WebSocket connected, yo!")
                notifyConnectionChange(true)
            }
            socket?.on(Socket.EVENT_DISCONNECT) {
                isConnected = false
                Log.w(TAG, "WebSocket dropped: ${it.joinToString()}")
                notifyConnectionChange(false)
            }
            socket?.on(Socket.EVENT_CONNECT_ERROR) {
                isConnected = false
                Log.e(TAG, "WebSocket error: ${it.joinToString()}")
                notifyConnectionChange(false)
            }
            socket?.connect()
            isInitialized = true
            Log.d(TAG, "WebSocket initialized")
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Bad WebSocket URL: $e")
        }
    }

    fun isConnected(): Boolean = isConnected && socket?.connected() == true

    suspend fun waitForConnection(timeoutMs: Long = 5000): Boolean {
        val start = System.currentTimeMillis()
        while (!isConnected() && System.currentTimeMillis() - start < timeoutMs) {
            socket?.connect() // Force connect attempt
            delay(50) // Tighter loop
        }
        return isConnected()
    }

    fun emit(event: String, data: Any? = null, onResponse: ((Array<Any>) -> Unit)? = null) {
        if (!isConnected()) {
            Log.e(TAG, "WebSocket ain’t ready for event: $event")
            return
        }
        if (data != null) {
            socket?.emit(event, data)
            Log.d(TAG, "Sent $event: $data")
        } else {
            socket?.emit(event)
            Log.d(TAG, "Sent $event")
        }
        onResponse?.let { callback ->
            socket?.once(getResponseEvent(event)) { args ->
                CoroutineScope(Dispatchers.Main).launch {
                    Log.d(TAG, "Got response for $event: ${args.joinToString()}")
                    callback(args)
                }
            }
        }
    }

    fun on(event: String, callback: (Array<Any>) -> Unit) {
        socket?.on(event) { args ->
            CoroutineScope(Dispatchers.Main).launch {
                Log.d(TAG, "Got event $event: ${args.joinToString()}")
                callback(args)
            }
        }
        Log.d(TAG, "Set listener for event: $event")
    }

    fun debugAllEvents() {
        val knownEvents = listOf(
            "pushListPlaylist", "pushBrowseLibrary", "pushCreatePlaylist", "pushAddToPlaylist",
            "pushState", "pushQueue", Socket.EVENT_CONNECT, Socket.EVENT_DISCONNECT, Socket.EVENT_CONNECT_ERROR
        )
        knownEvents.forEach { event ->
            socket?.on(event) { args ->
                CoroutineScope(Dispatchers.Main).launch {
                    Log.d(TAG, "Debug: Got event $event: ${args.joinToString()}")
                }
            }
        }
        socket ?: Log.w(TAG, "Socket null, can’t debug shit")
    }

    fun onConnectionChange(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener)
    }

    private fun notifyConnectionChange(isConnected: Boolean) {
        connectionListeners.forEach { it(isConnected) }
    }

    private var reconnecting = false

    fun reconnect() {
        if (!isConnected() && socket != null && !reconnecting) {
            reconnecting = true
            CoroutineScope(Dispatchers.IO).launch {
                Log.d(TAG, "Tryna reconnect WebSocket")
                var retries = 0
                while (retries < 5 && !isConnected()) {
                    socket?.connect()
                    if (waitForConnection(15000)) {
                        Log.d(TAG, "Reconnect worked, yo!")
                        emit("getState") // Force state refresh
                        break
                    } else {
                        Log.w(TAG, "Reconnect attempt $retries failed")
                        retries++
                    }
                }
                reconnecting = false
            }
        }
    }

    fun reconnectNow() {
        if (!isConnected() && !reconnecting) {
            reconnecting = true
            socket?.connect()
            Log.d(TAG, "Forcin’ reconnect, yo!")
            CoroutineScope(Dispatchers.IO).launch {
                if (waitForConnection(5000)) {
                    emit("getState")
                }
                reconnecting = false
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
        Log.d(TAG, "WebSocket shut down")
    }
}