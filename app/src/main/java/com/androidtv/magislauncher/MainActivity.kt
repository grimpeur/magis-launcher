package com.androidtv.magislauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.androidtv.magislauncher.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Única pantalla: al abrirse busca actualizaciones, conecta la VPN, abre MagisTV y se cierra.
 * La VPN la maneja [VpnSessionService], que la baja sola pasados los segundos configurados.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "magis_launcher"
        private const val SKIPPED_VERSION_KEY = "skipped_version"
        private const val USAGE_ACCESS_DECLINED_KEY = "usage_access_declined"
        /** Pausa para que se llegue a leer "Conectado" antes de saltar a MagisTV. */
        private const val LAUNCH_DELAY_MS = 1_200L
    }

    private lateinit var binding: ActivityMainBinding
    private var target: TargetApp? = null
    private var launched = false
    /** Ya se hizo startActivity de MagisTV: desde acá la VPN la corta el service, no la activity. */
    private var targetStarted = false
    /** Hay una pantalla de otra app arriba a pedido nuestro (Ajustes, permiso VPN): no es "salir". */
    private var awaitingExternal = false
    /** Hasta pedir la sesión se ignora el estado viejo del service (p.ej. un error anterior). */
    private var sessionRequested = false

    private var pendingDownloadId = -1L
    private var downloadWaiter: CancellableContinuation<Unit>? = null
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == pendingDownloadId) {
                pendingDownloadId = -1
                downloadWaiter?.resume(Unit)
                downloadWaiter = null
            }
        }
    }

    private var settingsWaiter: CancellableContinuation<Unit>? = null
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            awaitingExternal = false
            settingsWaiter?.resume(Unit)
            settingsWaiter = null
        }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingExternal = false
            val app = target
            if (result.resultCode == Activity.RESULT_OK && app != null) {
                VpnSessionService.start(this, app.packageName)
            } else {
                showError("Hace falta aceptar el permiso de VPN para continuar")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.version.text = "Versión ${BuildConfig.VERSION_NAME}"
        binding.retryButton.setOnClickListener { startSession() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exit()
        })

        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VpnSessionService.state.collect(::render)
            }
        }

        lifecycleScope.launch {
            // Si la VPN ya está arriba no hace falta demorar: se reabre MagisTV directo.
            if (VpnSessionService.state.value !is VpnState.Connected) {
                showStatus("Buscando actualizaciones...", null)
                if (checkForUpdates()) {
                    showStatus("Instalando actualización...", null)
                    return@launch
                }
                askForUsageAccess()
            }
            startSession()
        }
    }

    /** HOME u otra app encima antes de abrir MagisTV cuenta como salir: no dejar la VPN colgada. */
    override fun onStop() {
        super.onStop()
        if (!targetStarted && !awaitingExternal && !isChangingConfigurations && !isFinishing) exit()
    }

    /** Salida del usuario: si MagisTV todavía no se abrió, corta la sesión VPN (conectando o no). */
    private fun exit() {
        if (!targetStarted) VpnSessionService.stop(this)
        finish()
    }

    override fun onDestroy() {
        unregisterReceiver(downloadReceiver)
        super.onDestroy()
    }

    private fun startSession() {
        launched = false
        sessionRequested = true
        target = TargetApp.find(this)
        if (target == null) {
            showError("No encontré MagisTV / Xuper TV instalada en este equipo")
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            showStatus("Autorizá la conexión VPN", null)
            awaitingExternal = true
            vpnPermission.launch(consent)
        } else {
            VpnSessionService.start(this, target!!.packageName)
        }
    }

    private fun render(state: VpnState) {
        if (!sessionRequested) return
        when (state) {
            VpnState.Idle -> Unit
            is VpnState.Connecting -> showStatus(state.detail, null)
            is VpnState.Connected -> {
                val server = state.server?.let { "Servidor $it · " } ?: ""
                showStatus(
                    "Conectado",
                    "${server}IP ${state.publicIp}\nSe desconecta en ${state.secondsLeft}s",
                )
                launchTarget()
            }
            is VpnState.Failed -> showError(state.message)
        }
    }

    private fun launchTarget() {
        val app = target ?: return
        if (launched) return
        launched = true
        lifecycleScope.launch {
            delay(LAUNCH_DELAY_MS)
            showStatus("Abriendo ${app.label}...", null)
            try {
                targetStarted = true
                startActivity(app.launchIntent)
                finish()
            } catch (e: Exception) {
                targetStarted = false
                showError("No se pudo abrir ${app.label}: ${e.message}")
            }
        }
    }

    private fun showStatus(status: String, detail: String?) {
        binding.progress.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.status.text = status
        binding.detail.text = detail ?: ""
        binding.detail.visibility = if (detail == null) View.GONE else View.VISIBLE
    }

    private fun showError(message: String) {
        binding.progress.visibility = View.GONE
        binding.status.text = "Error"
        binding.detail.text = message
        binding.detail.visibility = View.VISIBLE
        binding.retryButton.visibility = View.VISIBLE
        binding.retryButton.requestFocus()
    }

    /**
     * Sin "acceso a datos de uso" la VPN se corta solo por tiempo; se ofrece ir a Ajustes a darlo
     * (lo activa el usuario con el control). "No volver a preguntar" lo deja en modo por tiempo.
     */
    private suspend fun askForUsageAccess() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (TargetReadyWatcher.isGranted(this) || prefs.getBoolean(USAGE_ACCESS_DECLINED_KEY, false)) return
        val settings = TargetReadyWatcher.settingsIntent(this) ?: return

        val choice = ask(
            title = "Permiso: Acceso a datos de uso",
            message = "Para desconectar la VPN apenas MagisTV termina de abrir, " +
                "${getString(R.string.app_name)} necesita el permiso «Acceso a datos de uso».\n\n" +
                "Se va a abrir esa pantalla de Ajustes: elegí «${getString(R.string.app_name)}» " +
                "en la lista, dejalo en «Permitido» y volvé con Atrás.\n\n" +
                "Sin este permiso la VPN se corta a los ${LauncherConfig.VPN_DURATION_SECONDS} segundos.",
            positive = "Abrir ajustes",
            neutral = "No volver a preguntar",
            negative = "Ahora no",
        )
        when (choice) {
            AlertDialog.BUTTON_POSITIVE -> {
                openSettings(settings)
            }
            AlertDialog.BUTTON_NEUTRAL -> prefs.edit().putBoolean(USAGE_ACCESS_DECLINED_KEY, true).apply()
        }
    }

    // --- Actualizaciones -------------------------------------------------------------------

    /**
     * Si hay versión nueva pregunta; si se elige descargar, espera la descarga y ofrece instalar.
     * Devuelve true si se lanzó el instalador (entonces no se conecta la VPN).
     */
    private suspend fun checkForUpdates(): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val remote = UpdateChecker.check() ?: return false
        if (remote.versionCode <= prefs.getInt(SKIPPED_VERSION_KEY, 0)) return false

        val choice = ask(
            title = "Nueva versión disponible",
            message = "Magis Launcher v${remote.versionName}\n\n${remote.changelog}",
            positive = "Descargar",
            neutral = "Ignorar esta versión",
            negative = "Más tarde",
        )
        when (choice) {
            AlertDialog.BUTTON_NEUTRAL -> {
                prefs.edit().putInt(SKIPPED_VERSION_KEY, remote.versionCode).apply()
                return false
            }
            AlertDialog.BUTTON_POSITIVE -> Unit
            else -> return false
        }

        showStatus("Descargando actualización...", "v${remote.versionName}")
        suspendCancellableCoroutine { cont ->
            downloadWaiter = cont
            pendingDownloadId = UpdateChecker.download(this, remote)
        }
        val install = ask(
            title = "Descarga completa",
            message = "La actualización se descargó. ¿Instalar ahora?",
            positive = "Instalar",
            neutral = null,
            negative = "Después",
        )
        if (install != AlertDialog.BUTTON_POSITIVE) return false
        if (!ensureInstallPermission()) return false
        UpdateChecker.install(this)
        return true
    }

    /**
     * Android 8+ exige que el usuario autorice a esta app como "fuente desconocida" para poder
     * instalar la actualización; sin eso el instalador del sistema la rechaza. Devuelve si quedó dado.
     */
    private suspend fun ensureInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) return true
        val choice = ask(
            title = "Permiso: Fuentes desconocidas",
            message = "Para instalar la actualización, ${getString(R.string.app_name)} necesita " +
                "permiso para instalar apps.\n\n" +
                "Se va a abrir la pantalla «Fuentes desconocidas» de Ajustes: elegí " +
                "«${getString(R.string.app_name)}», dejalo en «Autorizadas» y volvé con Atrás.",
            positive = "Abrir ajustes",
            neutral = null,
            negative = "Ahora no",
        )
        if (choice != AlertDialog.BUTTON_POSITIVE) return false
        openSettings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        return packageManager.canRequestPackageInstalls()
    }

    /** Abre una pantalla de Ajustes y suspende hasta que el usuario vuelve con Atrás. */
    private suspend fun openSettings(intent: Intent) {
        showStatus("Esperando que vuelvas de Ajustes...", null)
        suspendCancellableCoroutine { cont ->
            settingsWaiter = cont
            awaitingExternal = true
            settingsLauncher.launch(intent)
        }
    }

    private suspend fun ask(
        title: String,
        message: String,
        positive: String,
        neutral: String?,
        negative: String,
    ): Int = suspendCancellableCoroutine { cont ->
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, which -> cont.resume(which) }
            .setNegativeButton(negative) { _, which -> cont.resume(which) }
            .setOnCancelListener { if (cont.isActive) cont.resume(AlertDialog.BUTTON_NEGATIVE) }
        if (neutral != null) builder.setNeutralButton(neutral) { _, which -> cont.resume(which) }
        val dialog = builder.show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }
}
