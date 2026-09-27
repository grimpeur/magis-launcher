package com.androidtv.magislauncher

object LauncherConfig {
    /**
     * Tope de tiempo con la VPN arriba (desde que se verifica la conexión). Normalmente se corta
     * antes, cuando MagisTV llega a su pantalla principal (ver [TARGET_READY_ACTIVITY]).
     */
    const val VPN_DURATION_SECONDS = 20

    /** Activity de MagisTV que indica que ya pasó la bienvenida (que es la que necesita la VPN). */
    const val TARGET_READY_ACTIVITY = ".HomeActivity"

    /** Margen tras llegar a [TARGET_READY_ACTIVITY] para que termine de cargar antes de cortar. */
    const val READY_GRACE_SECONDS = 3

    /** Tiempo máximo para que el túnel quede con salida a internet antes de dar error. */
    const val CONNECT_TIMEOUT_MS = 20_000L

    /** Devuelve la IP pública en texto plano; se usa para verificar que el túnel tiene salida. */
    const val IP_CHECK_URL = "https://api.ipify.org"

    /** Config WireGuard embebida (git-ignored, ver wireguard.conf.example). */
    const val WIREGUARD_ASSET = "wireguard.conf"

    /** Se abre la primera app instalada cuyo nombre o paquete contenga alguno de estos textos. */
    val TARGET_APP_PATTERNS = listOf("magis", "xuper")

    const val VERSION_JSON_URL = "https://magis-launcher.surge.sh/version.json"
    const val DOWNLOAD_DIR = "updates"
    const val DOWNLOAD_FILE_NAME = "magis-launcher-update.apk"
}
