package dev.fidelco.guardrail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) {
            FocusScheduler.schedule(context)
            return
        }
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "boot/update $action")
        FocusScheduler.schedule(context)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wanted = prefs.getBoolean(PREFS_VPN_WANTED, false) && prefs.getBoolean(PREFS_VPN_RUNNING, false)
        if (!wanted) return
        try {
            context.startForegroundService(Intent(context, DnsVpnService::class.java))
            Log.i(TAG, "resumed vpn after boot")
        } catch (e: Exception) {
            Log.w(TAG, "vpn resume failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Guardrail"
        const val PREFS = "guardrail"
        const val PREFS_VPN_WANTED = "vpn_wanted"
        const val PREFS_VPN_RUNNING = "vpn_running"
    }
}
