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
        if (isInitialized && isConnected()) {
            Log.d(TAG, "WebSocket already good, fam")
            return
        }
        try {
            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionAttempts = 10
                reconnectionDelay = 1000
                reconnectionDelayMax = 10000
                transports = arrayOf("websocket")
                query = "EIO=3"
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

    suspend fun waitForConnection(timeoutMs: Long = 15000): Boolean {
        val start = System.currentTimeMillis()
        while (!isConnected() && System.currentTimeMillis() - start < timeoutMs) {
            delay(100)
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
        // Only reconnect if not already connected or reconnecting
        if (!isConnected() && socket != null && !reconnecting) {
            reconnecting = true
            CoroutineScope(Dispatchers.IO).launch {
                Log.d(TAG, "Tryna reconnect WebSocket")
                var retries = 0
                while (retries < 5 && !isConnected()) {
                    socket?.connect()
                    if (!waitForConnection(15000)) {
                        Log.w(TAG, "Reconnect attempt $retries failed")
                        retries++
                    } else {
                        Log.d(TAG, "Reconnect worked, yo!")
                        break
                    }
                }
                reconnecting = false
            }
        } else if (reconnecting) {
            Log.d(TAG, "Reconnect already in progress, skip")
        } else if (isConnected()) {
            Log.d(TAG, "WebSocket already connected, no need to reconnect")
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