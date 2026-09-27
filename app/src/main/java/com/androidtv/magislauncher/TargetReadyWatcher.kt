package com.androidtv.magislauncher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Detecta cuándo la app destino llegó a su pantalla principal ([LauncherConfig.TARGET_READY_ACTIVITY]),
 * leyendo los eventos de UsageStats. Necesita el permiso especial "acceso a datos de uso", que el
 * usuario da desde Ajustes (MainActivity lo lleva ahí con [Settings.ACTION_USAGE_ACCESS_SETTINGS]).
 */
class TargetReadyWatcher(private val context: Context, private val targetPackage: String) {

    companion object {
        /** Sin permiso (o en API 21) no hay detección y la VPN se corta solo por tiempo. */
        fun isGranted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 22) return false
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }

        /** Intent a la pantalla de Ajustes donde se da el permiso, o null si el equipo no la tiene. */
        fun settingsIntent(context: Context): Intent? {
            if (Build.VERSION.SDK_INT < 22) return null
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            return intent.takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }

    /** True si desde [sinceMs] la pantalla principal de la app destino pasó al frente. */
    fun reachedHomeSince(sinceMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < 22) return false
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(sinceMs, System.currentTimeMillis())
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // MOVE_TO_FOREGROUND (1) es el mismo valor que ACTIVITY_RESUMED en API 29+.
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                event.packageName == targetPackage &&
                event.className?.endsWith(LauncherConfig.TARGET_READY_ACTIVITY) == true
            ) {
                return true
            }
        }
        return false
    }
}
