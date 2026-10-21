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
import com.neon.xppen.core.RectN
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Схема планшета (с учётом поворота) и рабочей области на нём. В режиме «своя область» её можно двигать и менять. */
class TabletAreaView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Родной размер планшета, мм. */
    var tabletW = 160f
        private set
    var tabletH = 100f
        private set
    var tabletRotation = 0
        private set
    var area: RectN = RectN.FULL
        private set
    var editable = false
        private set
    var keepAspect = true
        private set

    /** Ширина/высота экрана в выбранной ориентации. */
    var screenAspect = 1080f / 2340f
        private set

    /** Положение пера в долях повёрнутого планшета (null — не показывать). */
    var penPos: PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    var onAreaChanged: ((RectN, Boolean) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val tabletRect = RectF()
    private val areaPx = RectF()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.tablet) }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tablet_edge)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }
    private val areaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        alpha = 70
    }
    private val areaStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 13f * dp
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textSize = 12f * dp
        textAlign = Paint.Align.CENTER
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_secondary) }
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.warn) }
    private val markPath = Path()

    fun setup(tabletW: Float, tabletH: Float, rotation: Int, area: RectN, editable: Boolean, keepAspect: Boolean, screenAspect: Float) {
        this.tabletW = tabletW
        this.tabletH = tabletH
        this.tabletRotation = rotation
        this.area = area
        this.editable = editable
        this.keepAspect = keepAspect
        this.screenAspect = screenAspect
        invalidate()
    }

    /** Отношение ширины к высоте области в долях планшета, при котором в мм она имеет пропорции экрана. */
    private fun normAspect(): Float {
        val (rw, rh) = Mapping.rotatedSize(tabletW, tabletH, tabletRotation)
        return screenAspect * rh / rw
    }

    /** Наибольший прямоугольник с пропорциями экрана внутри [r]. */
    fun fitAspectInside(r: RectN): RectN {
        val k = normAspect()
        var w = r.width
        var h = w / k
        if (h > r.height) {
            h = r.height
            w = h * k
        }
        val cx = (r.l + r.r) / 2f
        val cy = (r.t + r.b) / 2f
        return RectN(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val (rw, rh) = Mapping.rotatedSize(tabletW, tabletH, tabletRotation)
        val pad = 22f * dp
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        val scale = min(availW / rw, availH / rh)
        val w = rw * scale
        val h = rh * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        tabletRect.set(left, top, left + w, top + h)

        val r = 10f * dp
        canvas.drawRoundRect(tabletRect, r, r, bodyPaint)
        canvas.drawRoundRect(tabletRect, r, r, edgePaint)
        drawTopMark(canvas)

        areaPx.set(
            left + area.l * w, top + area.t * h,
            left + area.r * w, top + area.b * h,
        )
        canvas.drawRect(areaPx, areaFill)
        canvas.drawRect(areaPx, areaStroke)

        val mmW = area.width * rw
        val mmH = area.height * rh
        val label = "%.0f × %.0f мм".format(mmW, mmH)
        if (areaPx.width() > textPaint.measureText(label) + 8 * dp && areaPx.height() > 20 * dp) {
            canvas.drawText(label, areaPx.centerX(), areaPx.centerY() + textPaint.textSize / 3f, textPaint)
        }

        if (editable) {
            val hr = 7f * dp
            canvas.drawCircle(areaPx.left, areaPx.top, hr, handlePaint)
            canvas.drawCircle(areaPx.right, areaPx.top, hr, handlePaint)
            canvas.drawCircle(areaPx.right, areaPx.bottom, hr, handlePaint)
            canvas.drawCircle(areaPx.left, areaPx.bottom, hr, handlePaint)
        }

        penPos?.let { p ->
            canvas.drawCircle(left + p.x * w, top + p.y * h, 5f * dp, penPaint)
        }
    }

    /** Треугольник «верх» у родного верхнего края планшета (после поворота он может оказаться сбоку или снизу). */
    private fun drawTopMark(canvas: Canvas) {
        val s = 7f * dp
        val gap = 4f * dp
        val t = tabletRect
        markPath.reset()
        val textOff = 14f * dp
        when (tabletRotation) {
            90 -> { // верх справа
                val x = t.right + gap
                val y = t.centerY()
                markPath.moveTo(x + s, y); markPath.lineTo(x, y - s); markPath.lineTo(x, y + s)
            }
            180 -> { // верх снизу
                val x = t.centerX()
                val y = t.bottom + gap
                markPath.moveTo(x, y + s); markPath.lineTo(x - s, y); markPath.lineTo(x + s, y)
            }
            270 -> { // верх слева
                val x = t.left - gap
                val y = t.centerY()
                markPath.moveTo(x - s, y); markPath.lineTo(x, y - s); markPath.lineTo(x, y + s)
            }
            else -> {
                val x = t.centerX()
                val y = t.top - gap
                markPath.moveTo(x, y - s); markPath.lineTo(x - s, y); markPath.lineTo(x + s, y)
            }
        }
        markPath.close()
        canvas.drawPath(markPath, markPaint)
        val (lx, ly) = when (tabletRotation) {
            90 -> t.right - textOff to t.centerY() - s - gap
            180 -> t.centerX() + 4 * s to t.bottom + gap + s
            270 -> t.left + textOff to t.centerY() - s - gap
            else -> t.centerX() + 4 * s to t.top - gap
        }
        canvas.drawText("верх", lx, ly, labelPaint)
    }

    // ---- Редактирование ----

    private var drag = Drag.NONE
    private var corner = 0
    private var startX = 0f
    private var startY = 0f
    private var startArea = area

    private enum class Drag { NONE, MOVE, CORNER }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editable || tabletRect.width() <= 0f) return false
        val nx = (e.x - tabletRect.left) / tabletRect.width()
        val ny = (e.y - tabletRect.top) / tabletRect.height()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cornersPx = listOf(
                    areaPx.left to areaPx.top, areaPx.right to areaPx.top,
                    areaPx.right to areaPx.bottom, areaPx.left to areaPx.bottom,
                )
                val hit = cornersPx.indexOfFirst { (cx, cy) -> hypot(e.x - cx, e.y - cy) < 28f * dp }
                drag = when {
                    hit >= 0 -> {
                        corner = hit
                        Drag.CORNER
                    }
                    areaPx.contains(e.x, e.y) -> Drag.MOVE
                    else -> Drag.NONE
                }
                if (drag == Drag.NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = nx
                startY = ny
                startArea = area
            }
            MotionEvent.ACTION_MOVE -> {
                area = when (drag) {
                    Drag.MOVE -> moved(nx - startX, ny - startY)
                    Drag.CORNER -> resized(nx, ny)
                    Drag.NONE -> return false
                }
                invalidate()
                onAreaChanged?.invoke(area, false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != Drag.NONE) onAreaChanged?.invoke(area, true)
                drag = Drag.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun moved(dx: Float, dy: Float): RectN {
        val a = startArea
        val cdx = dx.coerceIn(-a.l, 1f - a.r)
        val cdy = dy.coerceIn(-a.t, 1f - a.b)
        return RectN(a.l + cdx, a.t + cdy, a.r + cdx, a.b + cdy)
    }

    private fun resized(nx: Float, ny: Float): RectN {
        val a = startArea
        // Противоположный угол неподвижен.
        val dirX = if (corner == 0 || corner == 3) -1f else 1f
        val dirY = if (corner == 0 || corner == 1) -1f else 1f
        val ox = if (dirX < 0) a.r else a.l
        val oy = if (dirY < 0) a.b else a.t
        val maxW = if (dirX < 0) ox else 1f - ox
        val maxH = if (dirY < 0) oy else 1f - oy
        val minSize = 0.08f
        var w = max(0f, (nx - ox) * dirX).coerceIn(minSize, maxW)
        var h = max(0f, (ny - oy) * dirY).coerceIn(minSize, maxH)
        if (keepAspect) {
            val k = normAspect()
            if (w / k > h) h = w / k else w = h * k
            if (w > maxW) {
                w = maxW
                h = w / k
            }
            if (h > maxH) {
                h = maxH
                w = h * k
            }
        }
        val l = if (dirX < 0) ox - w else ox
        val t = if (dirY < 0) oy - h else oy
        return RectN(l, t, l + w, t + h)
    }

    /** Перевод точки экрана (доли) обратно на планшет для показа положения пера. */
    fun screenToTablet(s: Float, t: Float, effective: RectN): PointF =
        PointF(effective.l + s * effective.width, effective.t + t * effective.height)
}
