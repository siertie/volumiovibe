package com.example.volumiovibe

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun VolumioTheme(content: @Composable () -> Unit) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(LocalContext.current) // Material You
    } else {
        lightColorScheme(
            primary = Color(0xFF6200EE), // Volumio purple (customize)
            secondary = Color(0xFF03DAC6), // Teal accent
            background = Color(0xFF121212), // Dark background
            surface = Color(0xFF1F1F1F)
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            headlineLarge = TextStyle(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onBackground
            ),
            bodyLarge = TextStyle(
                fontSize = 16.sp,
                color = colorScheme.onSurface
            )
            // Add more styles as needed
        ),
        shapes = Shapes(
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}