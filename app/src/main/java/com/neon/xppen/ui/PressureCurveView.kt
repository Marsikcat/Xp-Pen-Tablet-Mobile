package com.neon.xppen.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.neon.xppen.R
import com.neon.xppen.core.Mapping
import com.neon.xppen.core.PressureConfig
import kotlin.math.ln
import kotlin.math.pow

/** График кривой нажима. Вертикальное перетаскивание меняет крутизну (gamma). */
class PressureCurveView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var pressure = PressureConfig()
        set(value) {
            field = value
            invalidate()
        }

    /** Текущая точка: (сырой нажим, итоговый нажим), обе 0..1. */
    var livePoint: PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    var onGammaChanged: ((Float, Boolean) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val plot = RectF()
    private val path = Path()

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.grid)
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    private val bgPaint = Paint().apply { color = context.getColor(R.color.canvas_bg) }
    private val linearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.grid)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * dp, 6 * dp), 0f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.warn) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textSize = 11f * dp
    }

    override fun onDraw(canvas: Canvas) {
        val padL = 8f * dp
        val padR = 8f * dp
        val padT = 8f * dp
        val padB = 20f * dp
        plot.set(padL, padT, width - padR, height - padB)
        canvas.drawRect(plot, bgPaint)
        for (i in 1..3) {
            val x = plot.left + plot.width() * i / 4f
            val y = plot.top + plot.height() * i / 4f
            canvas.drawLine(x, plot.top, x, plot.bottom, framePaint)
            canvas.drawLine(plot.left, y, plot.right, y, framePaint)
        }
        canvas.drawRect(plot, framePaint)
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.top, linearPaint)

        path.reset()
        val steps = 120
        for (i in 0..steps) {
            val x = i / steps.toFloat()
            val y = Mapping.pressure(x, pressure)
            val px = plot.left + x * plot.width()
            val py = plot.bottom - y * plot.height()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, curvePaint)

        val hx = pressure.min + 0.5f * (pressure.max - pressure.min)
        val hy = 0.5f.pow(pressure.gamma)
        canvas.drawCircle(plot.left + hx * plot.width(), plot.bottom - hy * plot.height(), 8f * dp, handlePaint)

        livePoint?.let { p ->
            canvas.drawCircle(plot.left + p.x * plot.width(), plot.bottom - p.y * plot.height(), 6f * dp, livePaint)
        }

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("нажатие пера →", plot.right, height - 5f * dp, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("↑ результат", plot.left + 4f * dp, plot.top + 14f * dp, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.y, false)
            }
            MotionEvent.ACTION_MOVE -> update(e.y, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                update(e.y, true)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun update(y: Float, final: Boolean) {
        if (plot.height() <= 0f) return
        val out = ((plot.bottom - y) / plot.height()).coerceIn(0.03f, 0.97f)
        val gamma = (ln(out) / ln(0.5f)).coerceIn(PressureConfig.GAMMA_MIN, PressureConfig.GAMMA_MAX)
        pressure = pressure.copy(gamma = gamma)
        onGammaChanged?.invoke(gamma, final)
    }
}
