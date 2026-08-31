package dev.dshpm.android.pm

/** Connection settings persisted locally (cold-start restore, spec-android §3 验收 7). */
data class GatewayConfig(
    val host: String,
    val port: String,
    val token: String,
) {
    fun wsUri(): String = "ws://$host:$port/ws"

    companion object {
        /** Emulator reaches the host-machine gateway via the 10.0.2.2 alias. */
        const val DEFAULT_HOST = "10.0.2.2"
        const val DEFAULT_PORT = "8765"
        val DEFAULT = GatewayConfig(DEFAULT_HOST, DEFAULT_PORT, token = "")
    }
}

/** Simplest durable store per dispatch: SharedPreferences (no extra dependency). */
class SettingsStore(private val prefs: android.content.SharedPreferences) {

    fun load(): GatewayConfig = GatewayConfig(
        host = prefs.getString(KEY_HOST, null) ?: GatewayConfig.DEFAULT_HOST,
        port = prefs.getString(KEY_PORT, null) ?: GatewayConfig.DEFAULT_PORT,
        token = prefs.getString(KEY_TOKEN, null) ?: "",
    )

    fun save(cfg: GatewayConfig) {
        prefs.edit()
            .putString(KEY_HOST, cfg.host)
            .putString(KEY_PORT, cfg.port)
            .putString(KEY_TOKEN, cfg.token) // local only; never logged
            .apply()
    }

    companion object {
        const val PREFS_NAME = "dshpm_settings"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"
    }
}
