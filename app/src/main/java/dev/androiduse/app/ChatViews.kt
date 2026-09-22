package dev.androiduse.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/** Small vector controls keep the UI crisp at every Android display density. */
class ChatIcon(context: Context, var symbol: String, description: String, private val filled: Boolean = false, click: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        contentDescription = description; isFocusable = true; isClickable = true
        setOnClickListener { haptic(); click() }
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
            "attach" -> { line(-8f,0f,8f,0f); line(0f,-8f,0f,8f) }
            "close" -> { line(-5f,-5f,5f,5f); line(-5f,5f,5f,-5f) }
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

/** One progress value drives drag, scrim and parallax, including interrupted animations. */
class ChatDrawer(context: Context) : FrameLayout(context) {
    lateinit var page: View
    lateinit var panel: View
    private val shade = View(context).apply { setBackgroundColor(0x44000000); visibility=GONE }
    var isOpen = false; private set
    var onOpening: () -> Unit = {}
    private var progress=0f
    private var animation: ValueAnimator?=null
    private var velocity: VelocityTracker?=null
    private var downX=0f; private var downY=0f; private var dragging=false; private var outside=false
    private var starting=0f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val minFling=context.dp(450)
    private val easing=PathInterpolator(.22f,1f,.36f,1f)
    fun attach(page: View, panel: View) {
        this.page=page; this.panel=panel
        addView(page,LayoutParams(-1,-1)); addView(shade,LayoutParams(-1,-1))
        addView(panel,LayoutParams((resources.displayMetrics.widthPixels*.84f).toInt().coerceAtMost(context.dp(360)),-1))
        panel.elevation=context.dp(5).toFloat(); drawProgress(0f)
    }
    fun open() { onOpening(); settle(true) }
    fun close() { settle(false) }
    private fun drawProgress(value: Float) {
        progress=value.coerceIn(0f,1f)
        panel.translationX=-(1-progress)*panel.layoutParams.width
        page.translationX=context.dp(24)*progress
        shade.alpha=progress
        panel.visibility=if(progress>0f) VISIBLE else GONE
        shade.visibility=panel.visibility
        page.importantForAccessibility=if(progress>0f) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
    private fun settle(open: Boolean, fromGesture: Boolean=false) {
        animation?.cancel()
        if(fromGesture && isOpen!=open) haptic()
        isOpen=open
        val target=if(open) 1f else 0f
        if(!ValueAnimator.areAnimatorsEnabled()) { drawProgress(target); return }
        animation=ValueAnimator.ofFloat(progress,target).apply {
            duration=(160+140*abs(target-progress)).toLong(); interpolator=easing
            addUpdateListener { drawProgress(it.animatedValue as Float) }; start()
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) { velocity?.recycle(); velocity=VelocityTracker.obtain() }
        velocity?.addMovement(event)
        val result=super.dispatchTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) { velocity?.recycle(); velocity=null }
        return result
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX=event.x; downY=event.y; dragging=false; starting=progress
                outside=progress>0f && event.x>panel.layoutParams.width*progress
                if(outside) return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx=event.x-downX; val dy=event.y-downY
                if(abs(dx)>slop*2 && abs(dx)>abs(dy)*1.5f && (progress>0f || (dx>0 && downY<height-context.dp(100)))) {
                    if(progress==0f) onOpening()
                    animation?.cancel(); starting=progress; dragging=true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx=event.x-downX
                if(!dragging && abs(dx)>slop*2 && abs(dx)>abs(event.y-downY)*1.5f) {
                    if(progress==0f) onOpening()
                    animation?.cancel(); dragging=true
                }
                if(dragging) drawProgress(starting+dx/panel.layoutParams.width)
            }
            MotionEvent.ACTION_UP -> {
                if(dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val speed=velocity?.xVelocity ?: 0f
                    settle(if(abs(speed)>minFling) speed>0 else progress>.5f,fromGesture=true)
                } else if(outside) { haptic(); close(); performClick() }
                dragging=false
            }
            MotionEvent.ACTION_CANCEL -> { settle(isOpen); dragging=false }
        }
        return true
    }
    override fun onDetachedFromWindow() { animation?.cancel(); velocity?.recycle(); velocity=null; super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}
