package com.Zerodactyl.bloomina.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.Zerodactyl.bloomina.ui.theme.BloominaTheme

/**
 * Compose entry point. Edge-to-edge lets the MD3 surface show behind the system bars; the
 * bottom [NavigationBar] and each screen handle their own insets via Scaffold / content padding.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BloominaTheme {
                BloominaMain()
            }
        }
    }
}
