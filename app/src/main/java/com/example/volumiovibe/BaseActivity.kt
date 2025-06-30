package com.example.volumiovibe

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

open class BaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.WHITE  // or your light bg color

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        // Let content draw edge-to-edge but keep system bars visible
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // OPTIONAL: Hide nav bar only if needed
        // window.decorView.systemUiVisibility = (
        //     View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        //     View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        // )
    }
}
