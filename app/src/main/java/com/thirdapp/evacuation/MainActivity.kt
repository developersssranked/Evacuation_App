package com.thirdapp.evacuation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thirdapp.evacuation.ui.EvacuationScreen
import com.thirdapp.evacuation.ui.theme.EvacuationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EvacuationTheme {
                EvacuationScreen()
            }
        }
    }
}
