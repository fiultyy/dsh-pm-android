package dev.dshpm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dshpm.android.pm.BoardController
import dev.dshpm.android.pm.GatewayConfig
import dev.dshpm.android.pm.SettingsStore
import dev.dshpm.android.ui.DshPmApp
import dev.dshpm.proto.frame.ClientFrame
import dev.dshpm.proto.ws.ConnectionState
import dev.dshpm.proto.ws.VoiceGatewayClient
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * AND-002 three-view mobile PM face (AND3-1).
 *
 * UI ↔ network boundary: Compose screens see only [BoardController.UiState];
 * the controller speaks [ClientFrame]s through the injected send port bound to
 * [VoiceGatewayClient] — zero raw network calls in UI (red line), proto module
 * untouched (consumption face).
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var controller: BoardController

    private val sendRef = AtomicReference<(ClientFrame) -> Unit>({ })
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dshpm-board").apply { isDaemon = true }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dshpm-clock").apply { isDaemon = true }
    }

    // Compose-observable state bridges
    private var uiState by mutableStateOf(BoardController.UiState())
    private var config by mutableStateOf(GatewayConfig.DEFAULT)
    private var nowMs by mutableLongStateOf(System.currentTimeMillis())

    private var client: VoiceGatewayClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(getSharedPreferences(SettingsStore.PREFS_NAME, MODE_PRIVATE))
        config = settings.load()

        controller = BoardController(
            sendPort = { frame -> sendRef.get().invoke(frame) },
            schedule = { delay, action ->
                scheduler.schedule(action, delay, TimeUnit.MILLISECONDS)
            },
        )
        controller.onChange = { next ->
            runOnUiThread {
                uiState = next
                nowMs = System.currentTimeMillis()
            }
        }

        setContent {
            DshPmApp(
                state = uiState,
                config = config,
                nowMs = nowMs,
                onConfigSaved = { saved ->
                    config = saved
                    settings.save(saved) // cold-start restore (spec §3 验收 7)
                    reconnect(saved)
                },
            )
        }

        // relative-time re-render heartbeat (1/min resolution → tick every 30s)
        ticker.scheduleAtFixedRate({
            runOnUiThread { nowMs = System.currentTimeMillis() }
        }, 30_000, 30_000, TimeUnit.MILLISECONDS)

        reconnect(config)
    }

    private fun reconnect(cfg: GatewayConfig) {
        client?.close()
        val fresh = VoiceGatewayClient(
            uri = URI.create(cfg.wsUri()),
            token = cfg.token,
            listener = controller,
            config = VoiceGatewayClient.Config(pingIntervalMillis = 30_000), // spec §2: 30s 周期
        )
        sendRef.set { frame ->
            try {
                fresh.send(frame)
            } catch (_: IllegalStateException) {
                // reconnect window — controller re-pulls on next CONNECTED
            }
        }
        client = fresh
        fresh.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            client?.close()
            scheduler.shutdownNow()
            ticker.shutdownNow()
        }
    }
}
