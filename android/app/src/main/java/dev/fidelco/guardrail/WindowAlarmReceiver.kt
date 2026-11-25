package dev.fidelco.guardrail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WindowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "window transition; refreshing scheduled restrictions")
        FocusScheduler.schedule(context)
    }

    companion object {
        private const val TAG = "Guardrail"
    }
}
