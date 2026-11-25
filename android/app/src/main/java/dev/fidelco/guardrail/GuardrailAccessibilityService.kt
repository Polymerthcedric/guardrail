package dev.fidelco.guardrail

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper
import java.time.LocalTime

class GuardrailAccessibilityService : AccessibilityService() {

    private val prefs by lazy { getSharedPreferences("guardrail", MODE_PRIVATE) }
    private var overlay: BlockOverlay? = null
    private var lastIncognitoBlock = 0L
    private var lastIncognitoScan = 0L
    private var foregroundPackage: String? = null
    private var overlayTarget: String? = null
    private var overlayReason: String? = null
    private var overlayShownAt = 0L
    private val limitHandler = Handler(Looper.getMainLooper())
    private val limitCheck = object : Runnable {
        override fun run() {
            val pkg = foregroundPackage ?: return
            if (pkg !in Blocked.blockedApps) return
            val reason = if (bypassed(pkg)) null else blockReason(pkg)
            if (reason != null) showOverlay(pkg, reason) else dismissOverlay()
            limitHandler.postDelayed(this, LIMIT_RECHECK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onDestroy() {
        limitHandler.removeCallbacks(limitCheck)
        dismissOverlay()
        foregroundPackage = null
        if (instance === this) instance = null
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "evt pkg=${event.packageName} cls=${event.className} text=${event.text}")
        }
        Blocked.checkClockTamper(prefs)
        val pkg = event.packageName?.toString() ?: return
        if (pkg in SETTINGS_PKGS && eventMentionsThisApp(event)) {
            // The user opened our App Info page to Force Stop / disable the fence.
            Log.w(TAG, "settings opened on guardrail \u2014 kicking home")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        if (pkg == packageName) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && overlay?.isShowing() != true) {
                clearForegroundGate()
            }
            return
        }
        if (pkg in BROWSERS) {
            val marker = incognitoMarker()
            if (marker != null) {
                blockIncognito(pkg, marker)
                return
            }
        }
        if (overlayReason == REASON_INCOGNITO) {
            if (pkg == overlayTarget) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    System.currentTimeMillis() - overlayShownAt >= INCOGNITO_HOME_GRACE_MS
                ) dismissOverlay() else return
            } else if (System.currentTimeMillis() - overlayShownAt < INCOGNITO_HOME_GRACE_MS) {
                return
            } else {
                dismissOverlay()
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundPackage = pkg
            limitHandler.removeCallbacks(limitCheck)
            if (pkg in Blocked.blockedApps) {
                limitHandler.postDelayed(limitCheck, LIMIT_RECHECK_MS)
            }
        } else if (pkg != foregroundPackage) return

        if (pkg !in Blocked.blockedApps || bypassed(pkg)) {
            dismissOverlay()
            return
        }
        val reason = blockReason(pkg)
        if (reason == null) {
            dismissOverlay()
            return
        }
        Log.i(TAG, "gate $pkg reason=$reason")
        showOverlay(pkg, reason)
    }

    /**
     * Chrome renders private tabs in the same activity as normal tabs, so the
     * only reliable tell is the incognito UI itself. Normal Chrome also keeps a
     * *visible* node with content-description "New Incognito tab" (its
     * pre-inflated overflow menu), so content-descriptions are ignored. Only
     * two signals are trusted: an incognito-specific view id, or the incognito
     * new-tab-page heading. The heading uses a typographic apostrophe
     * ("You've gone Incognito"), so we match the apostrophe-free tail
     * "gone incognito". Returns what matched, for logging.
     */
    private fun incognitoMarker(): String? {
        val now = System.currentTimeMillis()
        if (now - lastIncognitoScan < 250) return null
        lastIncognitoScan = now
        val root = rootInActiveWindow ?: return null
        val debug = debugIncognito()
        val candidates: MutableList<String>? = if (debug) ArrayList() else null
        val strong = findIncognito(root, 0, candidates)
        if (debug) {
            if (!candidates.isNullOrEmpty()) Log.i(TAG, "incognito candidates: $candidates")
            return null
        }
        return strong
    }

    private fun findIncognito(node: AccessibilityNodeInfo, depth: Int, candidates: MutableList<String>?): String? {
        if (depth > 40) return null
        if (node.isVisibleToUser) {
            val id = node.viewIdResourceName
            val desc = node.contentDescription?.toString()
            val text = node.text?.toString()
            val mentions = id?.contains("incognito", true) == true ||
                desc?.contains("incognito", true) == true ||
                text?.contains("incognito", true) == true
            if (mentions) {
                candidates?.add("id=$id desc=$desc text=$text")
                id?.takeIf { it.contains("incognito", true) }?.let { return "id=$it" }
                text?.lowercase()?.replace('\u2019', '\'')?.trim()
                    ?.takeIf { it == "you've gone incognito" }?.let { return "ntp" }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findIncognito(child, depth + 1, candidates)?.let { return it }
        }
        return null
    }

    private fun debugIncognito(): Boolean =
        try {
            Settings.Global.getInt(contentResolver, "guardrail_debug", 0) == 1
        } catch (_: Exception) {
            false
        }

    private fun blockIncognito(pkg: String, marker: String) {
        val now = System.currentTimeMillis()
        if (now - lastIncognitoBlock < 1200) return
        lastIncognitoBlock = now
        foregroundPackage = pkg
        limitHandler.removeCallbacks(limitCheck)
        Log.i(TAG, "incognito detected in $pkg ($marker) \u2014 kicking home")
        performGlobalAction(GLOBAL_ACTION_HOME)
        showOverlay(pkg, REASON_INCOGNITO)
    }

    private fun eventMentionsThisApp(event: AccessibilityEvent): Boolean {
        val label = appLabel.lowercase()
        if (event.text.any { it.toString().lowercase().contains(label) }) return true
        return event.contentDescription?.toString()?.lowercase()?.contains(label) == true
    }

    private val appLabel: String by lazy {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            "guardrail"
        }
    }

    private fun blockReason(pkg: String): String? {
        if (Blocked.isLockdown(prefs)) return REASON_LOCKDOWN
        val now = LocalTime.now()
        val scheduled = Blocked.windowActive(pkg, prefs, now)
        if (scheduled) return REASON_WINDOW
        val limit = Blocked.limitMinutes(pkg, prefs)
        if (limit > 0 && !Usage.hasAccess(this)) return REASON_USAGE_ACCESS
        val used = if (limit > 0) Usage.todayFor(this, pkg) else 0L
        val over = limit > 0 && used >= limit * 60_000L
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "schedule $pkg now=$now window=$scheduled limit=$limit used=$used")
        return if (over) REASON_LIMIT else null
    }

    private fun clearForegroundGate() {
        foregroundPackage = null
        limitHandler.removeCallbacks(limitCheck)
        dismissOverlay()
    }

    override fun onInterrupt() {
    }

    private fun bypassed(pkg: String): Boolean =
        !Blocked.isLockdown(prefs) && !Blocked.strictBypass(prefs) &&
            prefs.getLong(BYPASS_PREFIX + pkg, 0L) > System.currentTimeMillis()

    fun grantBypass(pkg: String) {
        prefs.edit().putLong(BYPASS_PREFIX + pkg, System.currentTimeMillis() + Blocked.EMERGENCY_GRACE_MS).apply()
        Log.i(TAG, "bypass granted for $pkg")
    }

    private fun showOverlay(pkg: String, reason: String) {
        try {
            if (overlayReason != reason || overlayTarget != pkg) overlayShownAt = System.currentTimeMillis()
            overlayTarget = pkg
            overlayReason = reason
            (overlay ?: BlockOverlay(this).also { overlay = it }).show(pkg, reason)
        } catch (e: Exception) {
            Log.w(TAG, "overlay failed: ${e.message}")
        }
    }

    private fun dismissOverlay() {
        overlayReason = null
        overlayTarget = null
        overlay?.dismiss()
    }

    companion object {
        private const val TAG = "Guardrail"
        const val BYPASS_PREFIX = "bypass_"
        const val REASON_WINDOW = "window"
        const val REASON_LIMIT = "limit"
        const val REASON_LOCKDOWN = "lockdown"
        const val REASON_INCOGNITO = "incognito"
        const val REASON_USAGE_ACCESS = "usage_access"
        private const val LIMIT_RECHECK_MS = 5_000L
        private const val INCOGNITO_HOME_GRACE_MS = 1_500L
        private val BROWSERS = setOf("com.android.chrome")
        private val SETTINGS_PKGS = setOf(
            "com.android.settings"
        )
        @Volatile
        var instance: GuardrailAccessibilityService? = null
            private set
    }
}
