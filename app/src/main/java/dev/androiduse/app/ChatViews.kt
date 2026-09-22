package dev.androiduse.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Small vector controls keep the UI crisp at every Android display density. */
class ChatIcon(context: Context, var symbol: String, description: String, private val filled: Boolean = false, click: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        contentDescription = description; isFocusable = true; isClickable = true
        setOnClickListener { click() }
        if (!filled) {
            val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = attrs.getDrawable(0); attrs.recycle()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.translate(width / 2f, height / 2f)
        val unit = context.dp(24) / 24f
        canvas.scale(unit, unit)
        paint.color = if (filled) Palette.ink else Palette.bg; paint.style = Paint.Style.FILL
        if (filled) canvas.drawCircle(0f, 0f, 19f, paint)
        paint.color = if (filled) Palette.bg else Palette.ink
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1,y1,x2,y2,paint)
        when (symbol) {
            "menu" -> { line(-9f,-4f,9f,-4f); line(-9f,4f,3f,4f) }
            "new" -> {
                canvas.drawPath(Path().apply { moveTo(1f,-8f); lineTo(-8f,-8f); lineTo(-8f,9f); lineTo(9f,9f); lineTo(9f,0f) },paint)
                canvas.drawPath(Path().apply { moveTo(-2f,3f); lineTo(-1f,-2f); lineTo(8f,-11f); lineTo(12f,-7f); lineTo(3f,2f); close() },paint)
            }
            "send" -> { line(0f,8f,0f,-8f); line(-6f,-2f,0f,-8f); line(0f,-8f,6f,-2f) }
            "stop" -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(-6f,-6f,6f,6f,1.5f,1.5f,paint) }
            "back" -> { line(8f,0f,-8f,0f); line(-8f,0f,-1f,-7f); line(-8f,0f,-1f,7f) }
            "settings" -> {
                for (i in 0..2) { val y = -7f + i*7; line(-10f,y,10f,y); val x = if(i==1) 4f else -4f
                    paint.style=Paint.Style.FILL; paint.color=Palette.bg; canvas.drawCircle(x,y,3f,paint)
                    paint.style=Paint.Style.STROKE; paint.color=Palette.ink; canvas.drawCircle(x,y,3f,paint)
                }
            }
            "more" -> { paint.style=Paint.Style.FILL; for (i in -1..1) canvas.drawCircle(i*7f,0f,1.5f,paint) }
        }
        canvas.restore()
    }
    fun show(symbol: String, description: String) { this.symbol=symbol; contentDescription=description; invalidate() }
}

/** A horizontal gesture opens history without stealing vertical chat scrolling. */
class ChatDrawer(context: Context) : FrameLayout(context) {
    lateinit var page: View
    lateinit var panel: View
    private val shade = View(context).apply { setBackgroundColor(0x55000000); visibility=GONE }
    var isOpen = false; private set
    var onOpening: () -> Unit = {}
    private var downX=0f; private var downY=0f; private var dragging=false; private var outside=false
    private var starting=0f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    fun attach(page: View, panel: View) {
        this.page=page; this.panel=panel
        addView(page,LayoutParams(-1,-1)); addView(shade,LayoutParams(-1,-1))
        addView(panel,LayoutParams((resources.displayMetrics.widthPixels*.84f).toInt().coerceAtMost(context.dp(360)),-1))
        panel.visibility=GONE; panel.elevation=context.dp(5).toFloat()
    }
    fun open() { onOpening(); settle(true) }
    fun close() { settle(false) }
    private fun settle(open: Boolean) {
        panel.animate().cancel(); shade.animate().cancel()
        if(open && panel.visibility!=VISIBLE) { panel.translationX=-panel.layoutParams.width.toFloat(); shade.alpha=0f }
        panel.visibility=VISIBLE; shade.visibility=VISIBLE; isOpen=open
        page.importantForAccessibility=if(open) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        panel.animate().translationX(if(open) 0f else -panel.layoutParams.width.toFloat()).setDuration(190).withEndAction {
            if(!isOpen) { panel.visibility=GONE; shade.visibility=GONE }
        }.start()
        shade.animate().alpha(if(open) 1f else 0f).setDuration(190).start()
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=event.x; downY=event.y; dragging=false; outside=isOpen && event.x>panel.width; starting=if(isOpen) 1f else 0f; if(outside) return true }
            MotionEvent.ACTION_MOVE -> {
                val dx=event.x-downX; val dy=event.y-downY
                if(abs(dx)>slop*2 && abs(dx)>abs(dy)*1.5f && (isOpen || (dx>0 && downY<height-context.dp(100)))) {
                    if(!isOpen) onOpening()
                    panel.animate().cancel(); shade.animate().cancel(); panel.visibility=VISIBLE; shade.visibility=VISIBLE
                    dragging=true; return true
                }
            }
        }
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if(dragging) {
                val progress=(starting+(event.x-downX)/panel.layoutParams.width).coerceIn(0f,1f)
                panel.translationX=-(1-progress)*panel.layoutParams.width; shade.alpha=progress
            }
            MotionEvent.ACTION_UP -> { if(dragging) settle(starting+(event.x-downX)/panel.layoutParams.width > .25f) else if(outside) close(); performClick(); dragging=false }
            MotionEvent.ACTION_CANCEL -> { settle(isOpen); dragging=false }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
