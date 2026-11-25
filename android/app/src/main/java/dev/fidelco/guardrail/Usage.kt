package dev.fidelco.guardrail

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import java.util.Calendar

object Usage {

    private const val CACHE_TTL_MS = 5_000L

    private val SYSTEM_EXCLUDE = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.launcher3",
        "com.android.deskclock",
        "com.android.soundrecorder",
        "com.google.android.captiveportallogin"
    )

    private var cacheTime = 0L
    private var cacheResult: Map<String, Long>? = null

    fun invalidate() {
        cacheResult = null
        cacheTime = 0L
    }

    fun hasAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun todayBegin(): Long = Calendar.getInstance().run {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    fun todayPerApp(ctx: Context): Map<String, Long> {
        if (!hasAccess(ctx)) return emptyMap()
        val now = System.currentTimeMillis()
        if (now - cacheTime < CACHE_TTL_MS) return cacheResult ?: emptyMap()
        val excluded = excluded(ctx)
        val usage = HashMap<String, Long>()
        val sessions = HashMap<String, Long>()
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(todayBegin(), end)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                if (pkg in excluded) continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    UsageEvents.Event.MOVE_TO_FOREGROUND ->
                        if (sessions[pkg] == null) sessions[pkg] = e.timeStamp

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        val start = sessions.remove(pkg) ?: continue
                        val delta = e.timeStamp - start
                        if (delta > 0) usage.merge(pkg, delta, Long::plus)
                    }
                }
            }
            val end2 = System.currentTimeMillis()
            sessions.forEach { (pkg, start) ->
                val delta = end2 - start
                if (delta > 0) usage.merge(pkg, delta, Long::plus)
            }
            cacheResult = usage
            cacheTime = end2
            return usage
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    private fun excluded(ctx: Context): Set<String> {
        val out = HashSet<String>()
        out.add(ctx.packageName)
        out.addAll(SYSTEM_EXCLUDE)
        try {
            val home = ctx.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0
            )?.activityInfo?.packageName
            if (home != null) out.add(home)
        } catch (e: Exception) {
        }
        return out
    }

    fun todayFor(ctx: Context, pkg: String): Long =
        todayPerApp(ctx)[pkg] ?: 0L

    fun todayTotal(ctx: Context): Long =
        todayPerApp(ctx).values.sum()

    fun mmss(ms: Long): String {
        val m = ms / 60_000
        return if (m < 60) "${m}m" else "${m / 60}h ${String.format("%02d", m % 60)}m"
    }
}
