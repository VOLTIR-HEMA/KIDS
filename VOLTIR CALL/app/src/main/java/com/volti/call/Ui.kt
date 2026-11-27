package com.volti.call

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun roundBg(color: Int, radius: Float, strokeW: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeW > 0) setStroke(strokeW, strokeColor)
    }

private val AVATAR_COLORS = intArrayOf(
    0xFF00A884.toInt(), 0xFF53BDEB.toInt(), 0xFF7F66FF.toInt(),
    0xFFE67E22.toInt(), 0xFFEE5A5A.toInt(), 0xFF3B7BBF.toInt(),
)

fun nameColor(name: String): Int {
    val h = name.hashCode()
    return AVATAR_COLORS[(if (h < 0) -h else h) % AVATAR_COLORS.size]
}

fun avatarView(ctx: Context, name: String, size: Int = 46): TextView = TextView(ctx).apply {
    text = if (name.isBlank()) "?" else name.trim().take(1).uppercase()
    setTextColor(Color.WHITE)
    textSize = (size / 2.4f)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(nameColor(name)) }
    layoutParams = LinearLayout.LayoutParams(ctx.dp(size), ctx.dp(size))
}

fun label(ctx: Context, text: String, size: Float = 15f, color: Int = Color.parseColor("#111B21"), bold: Boolean = false): TextView =
    TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun column(ctx: Context, vararg children: View): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it) }
    }

fun row(ctx: Context, vararg children: View): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        children.forEach { addView(it) }
    }

fun spacer(ctx: Context, weight: Float = 1f): View =
    View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, weight) }

fun gap(ctx: Context, w: Int = 0, h: Int = 0): View =
    View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(w), ctx.dp(h)) }

fun iconButton(ctx: Context, emoji: String, onClick: () -> Unit): TextView =
    TextView(ctx).apply {
        text = emoji
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        setOnClickListener { onClick() }
    }

fun pillButton(ctx: Context, text: String, filled: Boolean = true, onClick: () -> Unit): TextView =
    TextView(ctx).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(if (filled) Color.WHITE else Color.parseColor("#00A884"))
        background = roundBg(
            if (filled) Color.parseColor("#00A884") else Color.TRANSPARENT,
            ctx.dp(22).toFloat(),
            if (filled) 0 else ctx.dp(1),
            Color.parseColor("#00A884"),
        )
        setPadding(ctx.dp(16), ctx.dp(9), ctx.dp(16), ctx.dp(9))
        isClickable = true
        setOnClickListener { onClick() }
    }

fun topBar(ctx: Context, title: String, subtitle: String = "", onBack: (() -> Unit)? = null, actions: List<TextView> = emptyList()): LinearLayout {
    val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#008069"))
        setPadding(ctx.dp(6), ctx.dp(10), ctx.dp(6), ctx.dp(10))
    }
    if (onBack != null) {
        bar.addView(TextView(ctx).apply {
            text = "‹"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(ctx.dp(10), 0, ctx.dp(6), ctx.dp(3))
            setOnClickListener { onBack() }
        })
    }
    val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(8), 0, 0, 0)
    }
    col.addView(label(ctx, title, 18f, Color.WHITE, true))
    if (subtitle.isNotBlank()) col.addView(label(ctx, subtitle, 12f, Color.parseColor("#D6F5EC")))
    bar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    actions.forEach { act -> act.setTextColor(Color.WHITE); bar.addView(act) }
    return bar
}

fun divider(ctx: Context): View =
    View(ctx).apply {
        setBackgroundColor(Color.parseColor("#E3E6E8"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1))
    }

fun editText(ctx: Context, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
    EditText(ctx).apply {
        this.hint = hint
        this.inputType = inputType
        setTextSize(15f)
        setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        background = roundBg(Color.parseColor("#F0F2F5"), ctx.dp(20).toFloat())
    }

fun askText(ctx: Context, title: String, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT, onOk: (String) -> Unit) {
    val input = editText(ctx, hint, inputType)
    val wrap = FrameLayout(ctx).apply {
        setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0)
        addView(input)
    }
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setView(wrap)
        .setPositiveButton("موافق") { _, _ -> onOk(input.text.toString().trim()) }
        .setNegativeButton("إلغاء", null)
        .show()
}

fun confirm(ctx: Context, title: String, message: String, onYes: () -> Unit) {
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("نعم") { _, _ -> onYes() }
        .setNegativeButton("إلغاء", null)
        .show()
}

fun badgeDot(ctx: Context, count: Int): TextView? {
    if (count <= 0) return null
    return TextView(ctx).apply {
        text = if (count > 99) "99+" else count.toString()
        textSize = 11f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#25D366")) }
        val s = ctx.dp(20)
        layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = ctx.dp(4) }
    }
}

fun onlineDot(ctx: Context): View =
    View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#25D366"))
            setStroke(ctx.dp(2), Color.WHITE)
        }
        layoutParams = LinearLayout.LayoutParams(ctx.dp(12), ctx.dp(12))
    }

/** أيقونات التنقل السفلية مرسومة برمجياً (بدون إيموجي). */
class NavIcon(ctx: Context, private val kind: Int, startColor: Int) : View(ctx) {

    var color: Int = startColor
        set(value) {
            field = value
            invalidate()
        }

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }

    init {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(25), ctx.dp(25))
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h)
        paint.color = color
        paint.style = android.graphics.Paint.Style.FILL
        when (kind) {
            0 -> { // محادثة
                canvas.drawRoundRect(0.10f * s, 0.14f * s, 0.90f * s, 0.72f * s, 0.16f * s, 0.16f * s, paint)
                val tail = android.graphics.Path().apply {
                    moveTo(0.26f * s, 0.70f * s)
                    lineTo(0.26f * s, 0.92f * s)
                    lineTo(0.48f * s, 0.72f * s)
                    close()
                }
                canvas.drawPath(tail, paint)
            }
            1 -> { // تحديثات
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 0.10f * s
                canvas.drawCircle(0.5f * s, 0.5f * s, 0.34f * s, paint)
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(0.5f * s, 0.5f * s, 0.10f * s, paint)
            }
            2 -> { // مجتمعات
                canvas.drawCircle(0.36f * s, 0.32f * s, 0.14f * s, paint)
                canvas.drawRoundRect(0.14f * s, 0.52f * s, 0.58f * s, 0.86f * s, 0.16f * s, 0.16f * s, paint)
                val bg = android.graphics.Paint(paint).apply { alpha = 200 }
                canvas.drawCircle(0.70f * s, 0.30f * s, 0.12f * s, bg)
                canvas.drawRoundRect(0.52f * s, 0.50f * s, 0.88f * s, 0.79f * s, 0.14f * s, 0.14f * s, bg)
            }
            else -> { // مكالمات
                canvas.save()
                canvas.rotate(-38f, 0.5f * s, 0.5f * s)
                canvas.drawRoundRect(0.14f * s, 0.35f * s, 0.36f * s, 0.65f * s, 0.10f * s, 0.10f * s, paint)
                canvas.drawRoundRect(0.64f * s, 0.35f * s, 0.86f * s, 0.65f * s, 0.10f * s, 0.10f * s, paint)
                canvas.drawRect(0.30f * s, 0.43f * s, 0.70f * s, 0.57f * s, paint)
                canvas.restore()
            }
        }
    }
}

fun navIcon(ctx: Context, kind: Int, color: Int): NavIcon = NavIcon(ctx, kind, color)

