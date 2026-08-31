package dev.dshpm.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.dshpm.android.pm.BoardController
import dev.dshpm.android.pm.GatewayConfig
import dev.dshpm.android.pm.SettingsStore
import dev.dshpm.android.ui.DshPmApp
import dev.dshpm.android.voice.AndroidMicSource
import dev.dshpm.android.voice.AndroidPlayerSink
import dev.dshpm.android.voice.VoiceController
import dev.dshpm.proto.frame.ClientFrame
import dev.dshpm.proto.frame.ErrorFrame
import dev.dshpm.proto.frame.MalformedFrame
import dev.dshpm.proto.frame.UnknownFrame
import dev.dshpm.proto.frame.WsFrame
import dev.dshpm.proto.ws.ConnectionState
import dev.dshpm.proto.ws.GatewayListener
import dev.dshpm.proto.ws.VoiceGatewayClient
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * AND-002 mobile PM face + voice page (AND3-1 / AND4-2).
 *
 * UI ↔ network boundary: Compose screens see only controller states; the
 * controllers speak [ClientFrame]s through the injected send ports bound to
 * [VoiceGatewayClient] — zero raw network calls in UI (red line), proto
 * module untouched (consumption face).
 *
 * AND4-2: ONE shared gateway connection carries board (pm.req/pm.sub) AND
 * the voice session (session.start{observe:false}) — the app stays within a
 * single connection of the per-token ≤2 concurrency budget (README note).
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var controller: BoardController
    private lateinit var voice: VoiceController

    private val sendRef = AtomicReference<(ClientFrame) -> Unit>({ })
    private val audioRef = AtomicReference<(ByteArray) -> Unit>({ })
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dshpm-board").apply { isDaemon = true }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dshpm-clock").apply { isDaemon = true }
    }

    private val mic = AndroidMicSource()
    private val player = AndroidPlayerSink()

    // Compose-observable state bridges
    private var uiState by mutableStateOf(BoardController.UiState())
    private var voiceState by mutableStateOf(VoiceController.VoiceState())
    private var config by mutableStateOf(GatewayConfig.DEFAULT)
    private var nowMs by mutableLongStateOf(System.currentTimeMillis())
    private var micPermission by mutableStateOf(false)

    private var client: VoiceGatewayClient? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micPermission = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(getSharedPreferences(SettingsStore.PREFS_NAME, MODE_PRIVATE))
        config = settings.load()
        micPermission = hasMicPermission()

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

        voice = VoiceController(
            sendPort = { frame -> sendRef.get().invoke(frame) },
            audioPort = { pcm -> audioRef.get().invoke(pcm) },
            mic = mic,
            player = player,
            log = { msg -> android.util.Log.i("DshPmVoice", msg) },
        )
        voice.onChange = { next -> runOnUiThread { voiceState = next } }

        player.start()

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
                voiceState = voiceState,
                voiceHooks = dev.dshpm.android.ui.VoiceHooks(
                    micPermissionGranted = micPermission,
                    requestPermission = { requestMicPermission() },
                    pttDown = {
                        if (!micPermission) requestMicPermission() else voice.pttDown()
                    },
                    pttUp = { voice.pttUp() },
                    switchHead = { voice.switchHead(it) },
                ),
                onVoiceTabActive = { active ->
                    voice.wantsSession = active
                    if (active) voice.activate() else voice.deactivate()
                },
            )
        }

        // relative-time re-render heartbeat (1/min resolution → tick every 30s)
        ticker.scheduleAtFixedRate({
            runOnUiThread { nowMs = System.currentTimeMillis() }
        }, 30_000, 30_000, TimeUnit.MILLISECONDS)

        reconnect(config)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        if (!hasMicPermission()) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        else micPermission = true
    }

    private fun reconnect(cfg: GatewayConfig) {
        client?.close()
        val listener = FanOutListener(controller, voice)
        val fresh = VoiceGatewayClient(
            uri = URI.create(cfg.wsUri()),
            token = cfg.token,
            listener = listener,
            config = VoiceGatewayClient.Config(pingIntervalMillis = 30_000), // spec §2: 30s 周期
        )
        sendRef.set { frame ->
            try {
                fresh.send(frame)
            } catch (_: IllegalStateException) {
                // reconnect window — controller re-pulls on next CONNECTED
            }
        }
        audioRef.set { pcm ->
            try {
                fresh.sendAudio(pcm)
            } catch (_: IllegalStateException) {
                // session audio outside an open socket — dropped
            }
        }
        client = fresh
        fresh.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            runCatching { voice.deactivate() }
            client?.close()
            mic.close()
            player.close()
            scheduler.shutdownNow()
            ticker.shutdownNow()
        }
    }
}

/** Boards and voice share one connection; frames fan out to both. */
private class FanOutListener(
    private val board: BoardController,
    private val voice: VoiceController,
) : GatewayListener {
    override fun onState(state: ConnectionState) {
        board.onState(state)
        voice.onState(state)
    }

    override fun onFrame(frame: WsFrame) {
        board.onFrame(frame)
        voice.onFrame(frame)
    }

    private var mediaSeen = 0L

    override fun onMedia(pcm: ByteArray) {
        // AND4-3 ②c: arrival probe — first + every 25th frame lands in logcat
        mediaSeen++
        if (mediaSeen == 1L || mediaSeen % 25L == 0L) {
            android.util.Log.i("DshPmVoice", "downlink binary #${mediaSeen}: ${pcm.size}B")
        }
        voice.onMedia(pcm)
    }

    override fun onError(error: Throwable) {
        board.onError(error)
        voice.onError(error)
    }
}
