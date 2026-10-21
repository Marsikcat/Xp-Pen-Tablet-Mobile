package com.neon.xppen.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.neon.xppen.R

/** Поле для проверки: рисует линии, толщина которых зависит от нажима. */
class TestCanvasView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Вызывается на каждое событие пера/пальца: итоговый нажим и само событие. */
    var onSample: ((Float, MotionEvent) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private val bounds = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.canvas_bg) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val stylusColor = context.getColor(R.color.accent)
    private val fingerColor = context.getColor(R.color.text_secondary)
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.warn)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }

    private var lastX = 0f
    private var lastY = 0f
    private var lastP = 0f
    private var hoverX = -1f
    private var hoverY = -1f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val old = bitmap
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmapCanvas = Canvas(b).also { c -> old?.let { c.drawBitmap(it, 0f, 0f, null) } }
        bitmap = b
        old?.recycle()
    }

    fun clear() {
        bitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = 12f * dp
        canvas.drawRoundRect(bounds, r, r, bgPaint)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (hoverX >= 0f) canvas.drawCircle(hoverX, hoverY, 8f * dp, hoverPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = bitmapCanvas ?: return false
        val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        strokePaint.color = if (stylus) stylusColor else fingerColor
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                hoverX = -1f
                lastX = e.x
                lastY = e.y
                lastP = e.pressure
                segment(c, lastX, lastY, lastP)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) {
                    segment(c, e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalPressure(i))
                }
                segment(c, e.x, e.y, e.pressure)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        onSample?.invoke(if (e.actionMasked == MotionEvent.ACTION_UP) 0f else e.pressure, e)
        invalidate()
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            hoverX = -1f
        } else {
            hoverX = e.x
            hoverY = e.y
        }
        onSample?.invoke(0f, e)
        invalidate()
        return true
    }

    private fun segment(c: Canvas, x: Float, y: Float, p: Float) {
        // Ширина по среднему нажиму на отрезке, чтобы линия менялась плавно.
        strokePaint.strokeWidth = (1f + 14f * ((p + lastP) / 2f).coerceIn(0f, 1f)) * dp
        c.drawLine(lastX, lastY, x, y, strokePaint)
        lastX = x
        lastY = y
        lastP = p
    }
}
