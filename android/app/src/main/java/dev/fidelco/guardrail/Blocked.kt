package dev.fidelco.guardrail

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

object Blocked {

    private const val TAG = "Guardrail"

    private val defaultRestrictedApps = setOf(
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",
        "com.instagram.android",
        "com.facebook.katana",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.netflix.mediaclient",
        "tv.twitch.android.app",
        "com.snapchat.android",
        "com.pinterest"
    )

    const val KEY_RESTRICTED_APPS = "restricted_apps"

    fun restrictedApps(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_RESTRICTED_APPS, null)?.toSet() ?: defaultRestrictedApps

    fun initializeRestrictedApps(prefs: SharedPreferences, installedPackages: Set<String>) {
        if (!prefs.contains(KEY_RESTRICTED_APPS)) {
            setRestrictedApps(prefs, defaultRestrictedApps.intersect(installedPackages))
        }
    }

    fun setRestrictedApps(prefs: SharedPreferences, packages: Set<String>) {
        prefs.edit().putStringSet(KEY_RESTRICTED_APPS, packages.toSet()).apply()
    }

    val domains = setOf(
        "youtube.com", "m.youtube.com", "youtu.be",
        "pornhub.com", "xvideos.com", "xnxx.com",
        "facebook.com", "instagram.com", "tiktok.com",
        "reddit.com", "twitter.com", "x.com",
        "netflix.com", "twitch.tv", "snapchat.com"
    )

    const val START_HOUR = 7
    const val END_HOUR = 19

    private val DISPLAY_NAMES = mapOf(
        "com.google.android.youtube" to "YouTube",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.twitter.android" to "X (Twitter)",
        "com.reddit.frontpage" to "Reddit",
        "com.netflix.mediaclient" to "Netflix",
        "tv.twitch.android.app" to "Twitch",
        "com.snapchat.android" to "Snapchat",
        "com.pinterest" to "Pinterest"
    )

    fun displayName(pkg: String): String = DISPLAY_NAMES[pkg] ?: pkg

    private const val WIN_DEFAULT = ""
    private const val WIN_OFF = "OFF"

    data class Window(val start: Int, val end: Int)

    fun inWindow(now: LocalTime = LocalTime.now()): Boolean {
        val m = minutes(now)
        return m >= START_HOUR * 60 && m < END_HOUR * 60
    }

    fun windowActive(pkg: String, prefs: SharedPreferences, now: LocalTime = LocalTime.now()): Boolean =
        windowsFor(pkg, prefs).any { it.contains(minutes(now)) }

    fun windowsFor(pkg: String, prefs: SharedPreferences): List<Window> {
        val raw = prefs.getString(keyWin(pkg), WIN_DEFAULT) ?: WIN_DEFAULT
        return when (raw) {
            WIN_DEFAULT -> listOf(Window(START_HOUR * 60, END_HOUR * 60))
            WIN_OFF -> emptyList()
            else -> raw.split(",").mapNotNull { spec ->
                val parts = spec.split("-")
                val s = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val e = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                if (e == s) null else Window(s, e)
            }
        }
    }

    fun setCustomWindows(pkg: String, prefs: SharedPreferences, windows: List<Window>) {
        val value = when {
            windows.isEmpty() -> WIN_OFF
            windows == listOf(Window(START_HOUR * 60, END_HOUR * 60)) -> WIN_DEFAULT
            else -> windows.distinct().filter { it.end != it.start }.joinToString(",") { "${it.start}-${it.end}" }
        }
        prefs.edit().putString(keyWin(pkg), value).apply()
    }

    fun limitMinutes(pkg: String, prefs: SharedPreferences): Int =
        prefs.getInt(keyLimit(pkg), 0)

    fun setLimitMinutes(pkg: String, prefs: SharedPreferences, minutes: Int) {
        prefs.edit().putInt(keyLimit(pkg), minutes.coerceIn(0, 1440)).apply()
    }

    const val EMERGENCY_DAILY = 3
    const val EMERGENCY_GRACE_MS = 120_000L

    fun emergenciesLeft(prefs: SharedPreferences): Int {
        val today = LocalDate.now().toString()
        val savedDay = prefs.getString(KEY_EMERG_DAY, null)
        val savedDate = savedDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (savedDate == null) {
            prefs.edit()
                .putString(KEY_EMERG_DAY, today)
                .putInt(KEY_EMERG_LEFT, EMERGENCY_DAILY)
                .apply()
        } else if (LocalDate.now().isAfter(savedDate)) {
            // Moving the time zone or clock backwards must not refill the daily
            // emergency budget. A backwards local-date shift keeps the current
            // budget until the calendar catches up with the last reset date.
            prefs.edit()
                .putString(KEY_EMERG_DAY, today)
                .putInt(KEY_EMERG_LEFT, EMERGENCY_DAILY)
                .apply()
        }
        return prefs.getInt(KEY_EMERG_LEFT, EMERGENCY_DAILY)
    }

    fun useEmergency(prefs: SharedPreferences): Boolean {
        val left = emergenciesLeft(prefs)
        if (left <= 0) return false
        prefs.edit().putInt(KEY_EMERG_LEFT, left - 1).apply()
        return true
    }

    fun strictBypass(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_STRICT, false)

    fun setStrictBypass(prefs: SharedPreferences, strict: Boolean) {
        prefs.edit().putBoolean(KEY_STRICT, strict).apply()
    }

    private const val KEY_EMERG_DAY = "emerg_day"
    private const val KEY_EMERG_LEFT = "emerg_left"
    private const val KEY_STRICT = "strict_no_bypass"

    fun describeWindow(pkg: String, prefs: SharedPreferences): String {
        val wins = windowsFor(pkg, prefs)
        return when {
            wins.isEmpty() -> "disabled"
            wins == listOf(Window(START_HOUR * 60, END_HOUR * 60)) -> "every day 7:00–19:00"
            else -> wins.joinToString(", ") { w ->
                val start = LocalTime.MIN.plusMinutes(w.start.toLong())
                val end = LocalTime.MIN.plusMinutes(w.end.toLong())
                "${hhmm(start)}–${hhmm(end)}"
            }
        }
    }

    private fun hhmm(t: LocalTime): String =
        String.format(Locale.getDefault(), "%02d:%02d", t.hour, t.minute).replaceFirst(Regex("^0"), "")

    private fun minutes(t: LocalTime): Int = t.hour * 60 + t.minute

    private fun Window.contains(m: Int): Boolean =
        if (end > start) m >= start && m < end else m >= start || m < end

    private fun keyWin(pkg: String) = "win_$pkg"
    private fun keyLimit(pkg: String) = "limit_$pkg"

    fun domainBlocked(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return domains.any { h == it || h.endsWith(".$it") }
    }

    // ---- Clock tamper guard ----
    // An anchor (wall clock vs. elapsedRealtime) is stored on every clean check.
    // If the wall clock jumps more than CLOCK_TAMPER_MS relative to the uptime
    // clock since the last clean sample, the device is put into lockdown.
    private const val KEY_ANCH_WALL = "clock_anchor_wall"
    private const val KEY_ANCH_UPTIME = "clock_anchor_uptime"
    private const val KEY_LOCKDOWN = "clock_lockdown"

    // 90s: wide enough for NTP nudges, small enough to catch a manual time set.
    const val CLOCK_TAMPER_MS = 90_000L

    fun isLockdown(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_LOCKDOWN, false)

    fun checkClockTamper(
        prefs: SharedPreferences,
        nowWall: Long = System.currentTimeMillis(),
        nowUptime: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val wall = prefs.getLong(KEY_ANCH_WALL, Long.MIN_VALUE)
        val up = prefs.getLong(KEY_ANCH_UPTIME, Long.MIN_VALUE)

        if (wall == Long.MIN_VALUE) {
            prefs.edit().putLong(KEY_ANCH_WALL, nowWall).putLong(KEY_ANCH_UPTIME, nowUptime).apply()
            return false
        }

        if (up > nowUptime) {
            // elapsedRealtime reset → device rebooted. Re-anchor; a reboot is not
            // itself proof of tampering, but it must not silently clear a lockdown.
            prefs.edit().putLong(KEY_ANCH_WALL, nowWall).putLong(KEY_ANCH_UPTIME, nowUptime).apply()
            return prefs.getBoolean(KEY_LOCKDOWN, false)
        }

        val expected = wall + (nowUptime - up)
        val drifted = nowWall - expected
        if (kotlin.math.abs(drifted) > CLOCK_TAMPER_MS) {
            if (!prefs.getBoolean(KEY_LOCKDOWN, false)) {
                prefs.edit().putBoolean(KEY_LOCKDOWN, true).apply()
                Log.w(TAG, "clock tamper detected: wall=$nowWall expected=$expected")
            }
            return true
        }

        if (prefs.getBoolean(KEY_LOCKDOWN, false)) {
            prefs.edit().putBoolean(KEY_LOCKDOWN, false).apply()
            Log.i(TAG, "clock back in sync; lockdown cleared")
        }
        prefs.edit().putLong(KEY_ANCH_WALL, nowWall).putLong(KEY_ANCH_UPTIME, nowUptime).apply()
        return false
    }

    fun clearLockdown(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_LOCKDOWN, false)
            .remove(KEY_ANCH_WALL)
            .remove(KEY_ANCH_UPTIME)
            .apply()
    }
}
