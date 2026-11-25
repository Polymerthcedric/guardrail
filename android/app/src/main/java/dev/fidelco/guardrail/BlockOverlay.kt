package dev.fidelco.guardrail

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The block barrier as an accessibility overlay window. realme UI T (RMX3834,
 * Android 15) aborts background startActivity() from a service, but
 * accessibility overlay windows are exempt, so this is the reliable way to cover a
 * restricted app.
 */
class BlockOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var busy = false
    private var visiblePackage: String? = null
    private var visibleReason: String? = null

    fun isShowing(): Boolean = view != null

    fun show(pkg: String, reason: String) {
        main.post {
            if (busy) return@post
            if (view != null && visiblePackage == pkg && visibleReason == reason) return@post
            busy = true
            try {
                val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                Blocked.checkClockTamper(prefs)
                val lockdown = Blocked.isLockdown(prefs)
                val strict = Blocked.strictBypass(prefs)
                val left = Blocked.emergenciesLeft(prefs)

                val content = BlockScreen.create(
                    ctx = service,
                    appIcon = appIcon(pkg),
                    reason = reason,
                    windowDescription = Blocked.describeWindow(pkg, prefs),
                    lockdown = lockdown,
                    strict = strict,
                    left = left,
                    onHome = {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        Log.i(TAG, "went home")
                        dismiss()
                    },
                    onOpen = {
                        if (Blocked.useEmergency(prefs)) {
                            (service as? GuardrailAccessibilityService)?.grantBypass(pkg)
                            Log.i(TAG, "emergency used: $pkg left=${Blocked.emergenciesLeft(prefs)}")
                            dismiss()
                        }
                    }
                )

                view?.let { runCatching { wm.removeView(it) } }
                wm.addView(content, layoutParams())
                view = content
                visiblePackage = pkg
                visibleReason = reason
                Log.i(TAG, "overlay shown pkg=$pkg reason=$reason lockdown=$lockdown")
            } catch (e: Exception) {
                Log.w(TAG, "overlay failed: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun dismiss() {
        main.post {
            view?.let { runCatching { wm.removeView(it) } }
            view = null
            visiblePackage = null
            visibleReason = null
            Log.i(TAG, "overlay dismissed")
        }
    }

    private fun layoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setTitle("GuardrailBlock")
        }

    private fun appIcon(pkg: String): Drawable = try {
        service.packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        service.packageManager.defaultActivityIcon
    }

    companion object {
        private const val TAG = "Guardrail"
        private const val PREFS = "guardrail"
    }
}
