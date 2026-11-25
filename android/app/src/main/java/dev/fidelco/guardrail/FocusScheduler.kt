package dev.fidelco.guardrail

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

object FocusScheduler {

    private const val CHANNEL = "focus"
    private const val REQ_REFRESH = 1

    fun schedule(ctx: Context) {
        ensureChannel(ctx)
        val now = LocalDateTime.now()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = nextTransition(ctx, now)
        if (next == null) am.cancel(alarm(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(next), alarm(ctx))
        val active = Blocked.blockedApps.any { Blocked.windowActive(it, ctx.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE), now.toLocalTime()) }
        if (active && gateActive(ctx)) show(ctx) else cancel(ctx)
    }

    fun show(ctx: Context) {
        if (!gateActive(ctx)) {
            cancel(ctx)
            return
        }
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val content = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Restrictions active")
            .setContentText("A scheduled app restriction is active \u00b7 tap to manage")
            .setContentIntent(content)
            .setOngoing(true)
            .build()
        nm.notify(NOTIF_ID, notification)
    }

    fun cancel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }

    private fun alarm(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            REQ_REFRESH,
            Intent(ctx, WindowAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun gateActive(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val a11y = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any {
            it.resolveInfo.serviceInfo.packageName == ctx.packageName &&
                it.resolveInfo.serviceInfo.name == GuardrailAccessibilityService::class.java.name
        }
        return a11y
    }

    private fun nextTransition(ctx: Context, now: LocalDateTime): LocalDateTime? {
        val prefs = ctx.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
        return Blocked.blockedApps.flatMap { pkg ->
            Blocked.windowsFor(pkg, prefs).flatMap { window -> listOf(window.start, window.end) }
        }.distinct().map { minute ->
            val target = now.toLocalDate().atStartOfDay().plusMinutes(minute.toLong())
            if (target.isAfter(now)) target else target.plusDays(1)
        }.minOrNull()
    }

    private fun millis(ldt: LocalDateTime): Long =
        ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Focus window", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while the focus window is active"
                }
            )
        }
    }

    private const val NOTIF_ID = 2
}
