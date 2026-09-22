package dev.androiduse.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    val bg = Color.WHITE
    val surface = Color.rgb(245,245,245)
    val border = Color.rgb(228,228,228)
    val ink = Color.rgb(28,28,28)
    val muted = Color.rgb(110,110,110)
    val accent = Color.rgb(28,28,28)
    val dark = Color.WHITE
    val warning = Color.rgb(138,87,18)
    val error = Color.rgb(179,38,30)
}
fun Context.dp(v: Int) = (resources.displayMetrics.density * v).toInt()
fun background(color: Int, radius: Float = 18f, border: Int? = null): GradientDrawable = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; border?.let { setStroke(1, it) }
}
fun Context.label(text: String, size: Float = 15f, color: Int = Palette.ink, bold: Boolean = false): TextView = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setLineSpacing(dp(3).toFloat(), 1f)
}
fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
fun LinearLayout.gap(n: Int) { addView(View(context), LinearLayout.LayoutParams(1, context.dp(n))) }
fun LinearLayout.fill(view: View, height: Int = -2) { addView(view, LinearLayout.LayoutParams(-1, if (height < 0) height else context.dp(height))) }
fun Context.action(text: String, primary: Boolean = false, click: () -> Unit): Button = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 15f; minHeight = dp(48)
    setTextColor(if (primary) Palette.dark else Palette.accent)
    background = background(if (primary) Palette.accent else Palette.surface, dp(14).toFloat(), if (primary) null else Palette.border)
    setPadding(dp(16), dp(10), dp(16), dp(10)); setOnClickListener { click() }
}

class ControlOverlay(private val service: PhoneAccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val box = service.row()
    private val title = service.label("Android Use", 11f, Palette.accent, true)
    private var shown = false
    init {
        box.setPadding(service.dp(10), service.dp(4), service.dp(4), service.dp(4))
        box.background = background(Palette.bg, service.dp(16).toFloat(), Palette.border)
        title.maxLines = 2
        box.addView(title, LinearLayout.LayoutParams(service.dp(116), -2))
        box.addView(service.action("Ⅱ") { AgentService.current?.togglePause() }.apply { contentDescription = "Pause or resume agent"; minWidth = 0 }, LinearLayout.LayoutParams(service.dp(45), service.dp(42)))
        box.addView(service.action("■") { AgentService.current?.stopRun() }.apply { contentDescription = "Stop agent"; minWidth = 0; setTextColor(Palette.error) }, LinearLayout.LayoutParams(service.dp(45), service.dp(42)))
    }
    fun show() {
        if (shown) return
        val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = service.dp(8); y = service.dp(8) }
        wm.addView(box, params); shown = true
    }
    fun update(text: String) { title.text = text }
    fun setHidden(hidden: Boolean) { box.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }
    fun close() { if (shown) { wm.removeView(box); shown = false } }
}
