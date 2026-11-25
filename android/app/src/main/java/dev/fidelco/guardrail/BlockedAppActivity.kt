package dev.fidelco.guardrail

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log

/**
 * The same block screen as the a11y overlay, shown as an activity. Used for the
 * in-app "Focus preview" so the design can be inspected without triggering a
 * real block.
 */
class BlockedAppActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        val reason = intent.getStringExtra(EXTRA_REASON) ?: GuardrailAccessibilityService.REASON_WINDOW
        val prefs = getSharedPreferences("guardrail", MODE_PRIVATE)

        Blocked.checkClockTamper(prefs)
        val lockdown = Blocked.isLockdown(prefs)
        val strict = Blocked.strictBypass(prefs)
        val left = Blocked.emergenciesLeft(prefs)

        setContentView(
            BlockScreen.create(
                ctx = this,
                appIcon = appIcon(pkg),
                reason = reason,
                windowDescription = Blocked.describeWindow(pkg, prefs),
                lockdown = lockdown,
                strict = strict,
                left = left,
                onHome = {
                    val ok = GuardrailAccessibilityService.instance
                        ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true
                    if (!ok) moveTaskToBack(true)
                    Log.i(TAG, "went home")
                    finish()
                },
                onOpen = {
                    if (Blocked.useEmergency(prefs)) {
                        GuardrailAccessibilityService.instance?.grantBypass(pkg)
                        Log.i(TAG, "emergency used: $pkg left=${Blocked.emergenciesLeft(prefs)}")
                        finish()
                    }
                }
            )
        )
    }

    private fun appIcon(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        packageManager.defaultActivityIcon
    }

    companion object {
        private const val TAG = "Guardrail"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_REASON = "reason"
    }
}
