package dev.dshpm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.dshpm.proto.ProtoV1
import dev.dshpm.proto.ws.ConnectionState

/**
 * Skeleton entry point. Hosts a single Compose surface proving the
 * Kotlin + Compose build chain works end to end. Zero business logic.
 *
 * AND2-3: the second line consumes [ConnectionState] from the proto module —
 * dependency-chain proof only; the real gateway UI is AND-002.
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
        Column {
            Text(text = "Hello, dsh-pm-android")
            Text(text = "gw ${ConnectionState.IDLE} · proto ${ProtoV1.PROTO}")
        }
    }
}
