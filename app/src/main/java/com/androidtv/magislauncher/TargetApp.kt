package com.androidtv.magislauncher

import android.content.Context
import android.content.Intent

/** La app a abrir con la VPN arriba (MagisTV / Xuper TV o alguno de sus clones). */
data class TargetApp(val label: String, val packageName: String, val launchIntent: Intent) {

    companion object {
        /**
         * Busca entre las apps con launcher (TV primero) la primera cuyo nombre o paquete matchee
         * [LauncherConfig.TARGET_APP_PATTERNS]. Sin package name fijo porque cada clon usa uno distinto.
         */
        fun find(context: Context): TargetApp? {
            val pm = context.packageManager
            val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
            for (category in categories) {
                val query = Intent(Intent.ACTION_MAIN).addCategory(category)
                for (info in pm.queryIntentActivities(query, 0)) {
                    val pkg = info.activityInfo.packageName
                    if (pkg == context.packageName) continue
                    val label = info.loadLabel(pm).toString()
                    val matches = LauncherConfig.TARGET_APP_PATTERNS.any {
                        label.contains(it, ignoreCase = true) || pkg.contains(it, ignoreCase = true)
                    }
                    if (!matches) continue
                    val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
                        ?: pm.getLaunchIntentForPackage(pkg)
                        ?: continue
                    return TargetApp(label, pkg, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            return null
        }
    }
}
