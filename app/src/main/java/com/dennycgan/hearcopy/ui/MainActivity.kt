package com.dennycgan.hearcopy.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.dennycgan.hearcopy.ui.theme.HearCopyTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HearCopyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {}
            }
        }
    }
}
