package dev.dshpm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Skeleton entry point. Hosts a single Compose surface proving the
 * Kotlin + Compose build chain works end to end. Zero business logic.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HelloScreen() }
    }
}

/** Build-chain smoke screen. */
@Composable
fun HelloScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Text(text = "Hello, dsh-pm-android")
    }
}
