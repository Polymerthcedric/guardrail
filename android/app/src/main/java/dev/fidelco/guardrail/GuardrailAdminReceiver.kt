package dev.fidelco.guardrail

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class GuardrailAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, GuardrailAdminReceiver::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "active as device ADMIN only; owner ops skipped")
            return
        }
        dpm.setUninstallBlocked(admin, context.packageName, true)
        dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, "0")
        Log.i(TAG, "device owner hardening applied")
    }

    companion object {
        private const val TAG = "Guardrail"
    }
}