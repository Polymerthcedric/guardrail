package dev.fidelco.guardrail

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Guardrail's visual language in one place.
 *
 * Calm, near-monochrome dark surfaces, hairline borders, one accent, a strict
 * type scale and monospace for values. Nothing here knows about application
 * state — callers pass real values in, so what renders is always what the
 * system actually reports.
 */
class Ui(private val ctx: Context) {

    object C {
        val BG       = 0xFF0F1013.toInt()
        val SURFACE  = 0xFF16181D.toInt()
        val SURFACE2 = 0xFF1C1F25.toInt()
        val BORDER   = 0xFF262B33.toInt()
        val TEXT     = 0xFFE6E8EC.toInt()
        val DIM      = 0xFF9BA1AB.toInt()
        val FAINT    = 0xFF6B7280.toInt()
        val ACCENT   = 0xFF58C48A.toInt()
        val DANGER   = 0xFFE5736E.toInt()
        val ON_ACCENT = 0xFF07130C.toInt()

        fun a(color: Int, alpha: Float): Int =
            Color.argb((alpha * 255f).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))
    }

    private val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val mono: Typeface = Typeface.create("monospace", Typeface.NORMAL)

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    /** Round content to a comfortable reading column on larger screens. */
    fun contentWidth(preferred: Int = 640): Int =
        minOf(ctx.resources.displayMetrics.widthPixels - dp(40), dp(preferred)).coerceAtLeast(dp(280))

    // ---- surfaces ----

    fun shape(fill: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    fun ripple(fill: Int, radius: Int, tint: Int = C.TEXT): Drawable =
        RippleDrawable(ColorStateList.valueOf(C.a(tint, 0.10f)), shape(fill, radius), null)

    fun circle(fill: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    // ---- type ----

    fun eyebrow(text: String, color: Int = C.FAINT): TextView =
        TextView(ctx).apply {
            this.text = text.uppercase()
            setTextColor(color)
            textSize = 11f
            typeface = medium
            letterSpacing = 0.14f
        }

    fun heading(text: String, size: Float = 16f, color: Int = C.TEXT): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            typeface = medium
        }

    fun body(text: String, size: Float = 14f, color: Int = C.DIM): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            typeface = sans
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    fun mono(text: String, size: Float = 13f, color: Int = C.DIM): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            typeface = mono
        }

    // ---- structure ----

    fun hairline(): View =
        View(ctx).apply {
            setBackgroundColor(C.BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

    fun vline(): View =
        View(ctx).apply {
            setBackgroundColor(C.BORDER)
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
        }

    fun card(): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(C.SURFACE, dp(14), C.BORDER)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

    fun dot(color: Int, size: Int = 8): View =
        View(ctx).apply {
            background = circle(color)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }

    fun chevron(): TextView =
        TextView(ctx).apply {
            text = "\u203A"
            setTextColor(C.FAINT)
            textSize = 20f
            typeface = sans
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    // ---- interactive rows / buttons ----

    fun row(): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minimumHeight = dp(56)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(C.a(C.TEXT, 0.06f)),
                shape(Color.TRANSPARENT, dp(12)),
                null
            )
        }

    /** A quiet destructive callout used for the clock-tamper lockdown. */
    fun noticeCard(eyebrow: String, title: String, bodyText: String, action: String, onAction: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(0xFF1F1315.toInt(), dp(14), C.a(C.DANGER, 0.45f))
            setPadding(dp(18), dp(16), dp(18), dp(14))
            addView(eyebrow(eyebrow, C.DANGER))
            addView(heading(title, 16f), topMargin(8))
            addView(body(bodyText, 13f).apply { setPadding(0, dp(4), 0, 0) })
            addView(ghost(action, onAction), fullTopMargin(12))
        }

    private fun topMargin(margin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(margin) }

    private fun fullTopMargin(margin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(margin) }

    fun primary(text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(C.ON_ACCENT)
            textSize = 15f
            typeface = medium
            gravity = Gravity.CENTER
            minimumHeight = dp(50)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = ripple(C.ACCENT, dp(12), tint = Color.BLACK)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun ghost(text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(C.TEXT)
            textSize = 15f
            typeface = medium
            gravity = Gravity.CENTER
            minimumHeight = dp(50)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = RippleDrawable(
                ColorStateList.valueOf(C.a(C.TEXT, 0.10f)),
                shape(Color.TRANSPARENT, dp(12), C.BORDER),
                null
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /** Same footprint as [ghost] but inert, for states with no bypass. */
    fun ghostDisabled(text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(C.FAINT)
            textSize = 15f
            typeface = medium
            gravity = Gravity.CENTER
            minimumHeight = dp(50)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = shape(Color.TRANSPARENT, dp(12), C.a(C.BORDER, 0.6f))
            isEnabled = false
        }

    fun spacer(height: Int): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }

    fun marginLeft(v: View, margin: Int): View {
        (v.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(margin)
        return v
    }

    fun marginTop(v: View, margin: Int): View {
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(margin)
        v.layoutParams = lp
        return v
    }

    fun weight(v: View, w: Float): View {
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.width = 0
        lp.weight = w
        v.layoutParams = lp
        return v
    }

    fun sp(size: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, size, ctx.resources.displayMetrics
    )
}
