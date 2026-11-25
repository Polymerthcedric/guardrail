package dev.fidelco.guardrail

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * One block screen, rendered identically whether it is shown as an
 * accessibility overlay window or as a preview activity. WYSIWYG: the words
 * and the enabled/disabled state follow the real reason and the real budget.
 */
object BlockScreen {

    private val AFFIRMATIONS = listOf(
        "The urge passes in about ninety seconds.",
        "This is the part that counts.",
        "Nothing here is urgent.",
        "Finish the thing you came to do."
    )

    fun create(
        ctx: Context,
        appIcon: Drawable,
        reason: String,
        lockdown: Boolean,
        strict: Boolean,
        left: Int,
        onHome: () -> Unit,
        onOpen: () -> Unit
    ): View {
        val ui = Ui(ctx)
        fun dp(n: Int) = ui.dp(n)

        val incognito = reason == GuardrailAccessibilityService.REASON_INCOGNITO

        val badge = ImageView(ctx).apply {
            setImageDrawable(appIcon)
            background = ui.shape(Ui.C.SURFACE2, dp(20), Ui.C.BORDER)
            clipToOutline = true
            setPadding(dp(15), dp(15), dp(15), dp(15))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val eyebrow = ui.eyebrow(
            when {
                lockdown -> "Lockdown"
                incognito -> "Private mode"
                reason == GuardrailAccessibilityService.REASON_LIMIT -> "Daily limit"
                reason == GuardrailAccessibilityService.REASON_USAGE_ACCESS -> "Setup needed"
                else -> "Focus window"
            },
            if (lockdown) Ui.C.DANGER else Ui.C.FAINT
        )

        val headline = ui.heading(
            when {
                lockdown -> "Clock tamper detected"
                incognito -> "Incognito is off"
                reason == GuardrailAccessibilityService.REASON_USAGE_ACCESS -> "Usage access is off"
                else -> "Focus time"
            },
            size = 24f
        ).apply {
            gravity = Gravity.CENTER
            maxWidth = dp(320)
        }

        val bodyText = when {
            lockdown ->
                "The clock moved out of sync, so every app stays locked. " +
                    "Restore the correct time, then clear the lockdown in Guardrail."
            incognito ->
                "Private tabs open a blank space with no filter. It stays off " +
                    "so browsing can't slip past the fence."
            reason == GuardrailAccessibilityService.REASON_USAGE_ACCESS ->
                "A daily limit is configured for this app, but Guardrail can't read screen time. " +
                    "Enable Usage access in Guardrail settings to continue."
            reason == GuardrailAccessibilityService.REASON_LIMIT ->
                "You've reached today's limit for this app. It resets at midnight."
            else ->
                "Restricted during your focus window " +
                    "(${Blocked.START_HOUR}:00\u2013${Blocked.END_HOUR}:00)."
        }
        val body = ui.body(bodyText).apply {
            gravity = Gravity.CENTER
            maxWidth = dp(320)
            setPadding(0, dp(10), 0, 0)
        }

        val affirmation = ui.body(
            AFFIRMATIONS[Math.floorMod(bodyText.hashCode(), AFFIRMATIONS.size)],
            size = 12.5f, color = Ui.C.FAINT
        ).apply {
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            gravity = Gravity.CENTER
            maxWidth = dp(320)
            setPadding(0, dp(16), 0, 0)
        }

        val goHome = ui.primary("Go to my home screen", onHome)

        val bypassReady = !lockdown && !strict && !incognito && left > 0
        val bypassLabel = when {
            lockdown -> "No bypass while locked"
            incognito -> "Incognito stays off"
            strict -> "Bypass locked (strict mode)"
            left <= 0 -> "No emergency unlocks left"
            else -> "Open anyway \u00b7 2 min"
        }
        val open: View = if (bypassReady) ui.ghost(bypassLabel, onOpen) else ui.ghostDisabled(bypassLabel)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.C.BG)
            setPadding(dp(28), dp(28), dp(28), dp(28))
            addView(badge, box(dp(72), dp(72)))
            addView(eyebrow, wrap(dp(22)))
            addView(headline, wrap(dp(8)))
            addView(body)
            addView(affirmation)
            addView(goHome, full(dp(28)))
            addView(open, full(dp(12)))
        }
    }

    private fun box(w: Int, h: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h)

    private fun wrap(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = top }

    private fun full(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = top }
}
