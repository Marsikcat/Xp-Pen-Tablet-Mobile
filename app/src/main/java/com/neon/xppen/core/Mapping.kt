package com.neon.xppen.core

import kotlin.math.pow

/**
 * Пересчёт координат и нажима. Общий для драйвера (helper) и для предпросмотра в приложении.
 *
 * Системы координат (все нормированы в 0..1):
 *  - планшет «родной»: как его видит ядро, длинная сторона по X;
 *  - планшет «как лежит»: после поворота, который задал пользователь;
 *  - экран «как видит пользователь»: с учётом текущего поворота экрана;
 *  - экран «физический»: натуральная (портретная) ориентация матрицы — в ней работает виртуальное перо,
 *    а Android сам поворачивает её под текущую ориентацию.
 */
object Mapping {

    /** Размер планшета (мм) после поворота на [rotation] градусов. */
    fun rotatedSize(w: Float, h: Float, rotation: Int): Pair<Float, Float> =
        if (rotation == 90 || rotation == 270) h to w else w to h

    /** Точка родного планшета → точка повёрнутого планшета (поворот по часовой стрелке). */
    fun rotateTablet(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        90 -> (1f - v) to u
        180 -> (1f - u) to (1f - v)
        270 -> v to (1f - u)
        else -> u to v
    }

    /** Обратное к [rotateTablet]. */
    fun unrotateTablet(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        90 -> y to (1f - x)
        180 -> (1f - x) to (1f - y)
        270 -> (1f - y) to x
        else -> x to y
    }

    /** Наибольший прямоугольник по центру планшета [tabW]×[tabH] с пропорциями экрана [scrW]×[scrH]. */
    fun fitRect(tabW: Float, tabH: Float, scrW: Float, scrH: Float): RectN {
        val tabAspect = tabW / tabH
        val scrAspect = scrW / scrH
        return if (tabAspect > scrAspect) {
            val w = scrAspect / tabAspect
            RectN((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        } else {
            val h = tabAspect / scrAspect
            RectN(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        }
    }

    /**
     * Итоговая область в долях повёрнутого планшета.
     * [tabW]×[tabH] — родной размер планшета в мм, [scrW]×[scrH] — экран так, как его видит пользователь.
     */
    fun effectiveArea(oc: OrientationConfig, tabW: Float, tabH: Float, scrW: Float, scrH: Float): RectN {
        val (rw, rh) = rotatedSize(tabW, tabH, oc.rotation)
        return when (oc.areaMode) {
            AreaMode.FIT -> fitRect(rw, rh, scrW, scrH)
            AreaMode.FULL -> RectN.FULL
            AreaMode.CUSTOM -> oc.area
        }
    }

    /**
     * Точка экрана «как видит пользователь» → физическая точка матрицы.
     * [displayRotation] — Surface.ROTATION_0..3.
     */
    fun toNatural(s: Float, t: Float, displayRotation: Int): Pair<Float, Float> = when (displayRotation) {
        1 -> (1f - t) to s
        2 -> (1f - s) to (1f - t)
        3 -> t to (1f - s)
        else -> s to t
    }

    /** Нормированный сырой нажим 0..1 → нажим после кривой 0..1. */
    fun pressure(n: Float, pc: PressureConfig): Float {
        if (n <= pc.min) return 0f
        val m = ((n - pc.min) / (pc.max - pc.min).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
        return m.pow(pc.gamma)
    }

    /** Обратное к [pressure]: по выходному нажиму восстановить сырой (для точки на графике). */
    fun rawFromPressure(out: Float, pc: PressureConfig): Float {
        if (out <= 0f) return pc.min
        return pc.min + (pc.max - pc.min) * out.coerceIn(0f, 1f).pow(1f / pc.gamma)
    }
}
