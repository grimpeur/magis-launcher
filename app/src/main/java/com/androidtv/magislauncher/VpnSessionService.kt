package com.androidtv.magislauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface VpnState {
    data object Idle : VpnState
    data class Connecting(val detail: String) : VpnState
    data class Connected(val server: String?, val publicIp: String, val secondsLeft: Int) : VpnState
    data class Failed(val message: String) : VpnState
}

/**
 * Foreground service dueño de la sesión VPN: levanta el túnel WireGuard embebido (solo para la app
 * destino y esta misma, para poder verificarlo), verifica que tenga salida a internet y lo baja
 * cuando [TargetReadyWatcher] ve que MagisTV llegó a su pantalla principal, o como tope después de
 * [LauncherConfig.VPN_DURATION_SECONDS]. Vive aparte de MainActivity porque la activity se cierra
 * apenas abre MagisTV.
 */
class VpnSessionService : Service() {

    companion object {
        private const val TAG = "VpnSession"
        private const val CHANNEL_ID = "vpn_session"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val ACTION_STOP = "com.androidtv.magislauncher.STOP"

        /** Hay una instancia viva (y en foreground): sin esto no se le puede mandar el STOP. */
        @Volatile private var running = false

        private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        /** Conecta, o si ya hay una sesión arriba reinicia la cuenta regresiva. */
        fun start(context: Context, targetPackage: String) {
            if (_state.value !is VpnState.Connected) {
                _state.value = VpnState.Connecting("Iniciando VPN...")
            }
            val intent = Intent(context, VpnSessionService::class.java)
                .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Corta la sesión en el estado que esté (conectando o conectada) y baja la VPN. */
        fun stop(context: Context) {
            if (!running) {
                _state.value = VpnState.Idle
                return
            }
            // startService alcanza: con el service en foreground la app no cuenta como en background.
            context.startService(Intent(context, VpnSessionService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessionJob: Job? = null
    private var countdownJob: Job? = null

    private val backend by lazy { GoBackend(applicationContext) }
    private val tunnel = object : Tunnel {
        override fun getName() = "magis"
        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "Túnel: $newState")
        }
    }
    private var tunnelUp = false
    private var targetPackage = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        intent?.getStringExtra(EXTRA_TARGET_PACKAGE)?.let { targetPackage = it }
        val current = _state.value
        when {
            current is VpnState.Connected -> startCountdown(current.server, current.publicIp)
            sessionJob?.isActive == true -> Unit // ya está conectando
            else -> sessionJob = scope.launch { connect() }
        }
        return START_NOT_STICKY
    }

    private suspend fun connect() {
        try {
            val config = withContext(Dispatchers.IO) { loadConfig() }
            val server = config.peers.firstOrNull()?.endpoint?.orElse(null)?.host
            _state.value = VpnState.Connecting(if (server != null) "Conectando a $server..." else "Conectando...")
            // Antes de levantarlo: si se cancela a mitad de setState, disconnect() igual lo baja.
            tunnelUp = true
            withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, config) }

            _state.value = VpnState.Connecting("Verificando conexión...")
            val ip = waitForInternet()
            if (ip == null) {
                fail("La VPN levantó pero no hay salida a internet")
                return
            }
            startCountdown(server, ip)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando", e)
            fail("No se pudo conectar la VPN: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Parsea la config embebida agregándole `IncludedApplications` para que por el túnel pase solo
     * la app destino (y esta, que necesita verificar la salida), no toda la TV.
     */
    private fun loadConfig(): Config {
        val raw = assets.open(LauncherConfig.WIREGUARD_ASSET).bufferedReader().use { it.readText() }
        val apps = listOf(targetPackage, packageName).filter { it.isNotEmpty() }.joinToString(", ")
        val scoped = raw.replaceFirst(
            Regex("""^\s*\[Interface]\s*$""", RegexOption.MULTILINE),
            "[Interface]\nIncludedApplications = $apps",
        )
        return Config.parse(scoped.byteInputStream())
    }

    /** Reintenta el pedido de IP pública hasta [LauncherConfig.CONNECT_TIMEOUT_MS]; null si nunca responde. */
    private suspend fun waitForInternet(): String? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + LauncherConfig.CONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                val request = Request.Builder().url(LauncherConfig.IP_CHECK_URL).build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()?.trim()
                    if (response.isSuccessful && !body.isNullOrEmpty()) return@withContext body
                }
            } catch (e: Exception) {
                Log.d(TAG, "Sin salida todavía: ${e.message}")
            }
            delay(1_000)
        }
        null
    }

    private fun startCountdown(server: String?, ip: String) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            val start = System.currentTimeMillis()
            var deadline = start + LauncherConfig.VPN_DURATION_SECONDS * 1_000L
            val watcher = TargetReadyWatcher(applicationContext, targetPackage)
                .takeIf { targetPackage.isNotEmpty() && TargetReadyWatcher.isGranted(applicationContext) }
            if (watcher == null) Log.w(TAG, "Sin acceso a datos de uso: se corta solo por tiempo")
            var ready = false
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= deadline) break
                if (!ready && watcher != null && withContext(Dispatchers.IO) { watcher.reachedHomeSince(start) }) {
                    ready = true
                    deadline = minOf(deadline, now + LauncherConfig.READY_GRACE_SECONDS * 1_000L)
                    Log.d(TAG, "Pantalla principal de $targetPackage detectada, corto en ${LauncherConfig.READY_GRACE_SECONDS}s")
                }
                _state.value = VpnState.Connected(server, ip, ((deadline - now + 999) / 1_000).toInt())
                delay(500)
            }
            Log.d(TAG, if (ready) "Corte por pantalla principal detectada" else "Corte por tope de tiempo")
            disconnect()
            _state.value = VpnState.Idle
            stopSelf()
        }
    }

    /** Corte pedido por el usuario (salió de la app antes de que abriera MagisTV). */
    private fun shutdown() {
        Log.d(TAG, "Corte pedido al salir de la app")
        countdownJob?.cancel()
        val pending = sessionJob
        pending?.cancel()
        scope.launch {
            // Espera que termine un setState en curso (no es interrumpible) antes de bajar el túnel.
            pending?.join()
            disconnect()
            _state.value = VpnState.Idle
            stopSelf()
        }
    }

    private fun fail(message: String) {
        _state.value = VpnState.Failed(message)
        scope.launch {
            disconnect()
            stopSelf()
        }
    }

    private suspend fun disconnect() {
        if (!tunnelUp) return
        withContext(Dispatchers.IO) {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error desconectando", e)
            }
        }
        tunnelUp = false
    }

    override fun onDestroy() {
        // Si el sistema mata el service antes de terminar la cuenta, que no quede la VPN colgada.
        if (tunnelUp) runBlocking { disconnect() }
        if (_state.value is VpnState.Connected || _state.value is VpnState.Connecting) {
            _state.value = VpnState.Idle
        }
        scope.cancel()
        running = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Magis Launcher")
            .setContentText("VPN activa por ${LauncherConfig.VPN_DURATION_SECONDS}s")
            .setOngoing(true)
            .build()
    }
}
