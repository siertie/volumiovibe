package com.example.volumiovibe

import android.app.Application
import android.util.Log

class VibeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WebSocketManager.initialize()
        Log.d("VibeApp", "WebSocket init fired off, yo!")
    }
}