package com.neon.xppen.core

import org.json.JSONArray
import org.json.JSONObject

/** Как выбирается рабочая область планшета. */
enum class AreaMode {
    /** Наибольшая область по центру с пропорциями экрана. */
    FIT,

    /** Весь планшет, растянутый на экран (как без драйвера). */
    FULL,

    /** Своя область, заданная пользователем. */
    CUSTOM,
}

/** Прямоугольник в долях (0..1) от повёрнутого планшета: left, top, right, bottom. */
data class RectN(val l: Float, val t: Float, val r: Float, val b: Float) {
    val width get() = r - l
    val height get() = b - t

    fun toJson() = JSONArray().put(l.toDouble()).put(t.toDouble()).put(r.toDouble()).put(b.toDouble())

    companion object {
        val FULL = RectN(0f, 0f, 1f, 1f)

        fun fromJson(a: JSONArray?): RectN {
            if (a == null || a.length() != 4) return FULL
            val r = RectN(
                a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(),
                a.optDouble(2, 1.0).toFloat(), a.optDouble(3, 1.0).toFloat(),
            )
            return if (r.width < 0.02f || r.height < 0.02f) FULL else r
        }
    }
}

/** Настройки для одной ориентации экрана. */
data class OrientationConfig(
    /** Поворот планшета по часовой стрелке относительно обычного положения: 0, 90, 180, 270. */
    val rotation: Int = 0,
    val areaMode: AreaMode = AreaMode.FIT,
    val area: RectN = RectN.FULL,
    /** Для своей области: держать пропорции экрана. */
    val keepAspect: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("rotation", rotation)
        .put("areaMode", areaMode.name)
        .put("area", area.toJson())
        .put("keepAspect", keepAspect)

    companion object {
        fun fromJson(o: JSONObject?): OrientationConfig {
            if (o == null) return OrientationConfig()
            val rot = o.optInt("rotation", 0).let { if (it in setOf(0, 90, 180, 270)) it else 0 }
            val mode = runCatching { AreaMode.valueOf(o.optString("areaMode", "FIT")) }.getOrDefault(AreaMode.FIT)
            return OrientationConfig(rot, mode, RectN.fromJson(o.optJSONArray("area")), o.optBoolean("keepAspect", true))
        }
    }
}

/**
 * Кривая нажима: всё, что ниже [min], — ноль; [max] и выше — полный нажим;
 * между ними — степенная кривая с показателем [gamma] (меньше 1 — мягче, больше 1 — жёстче).
 */
data class PressureConfig(
    val min: Float = 0f,
    val max: Float = 1f,
    val gamma: Float = 1f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("min", min.toDouble())
        .put("max", max.toDouble())
        .put("gamma", gamma.toDouble())

    companion object {
        const val GAMMA_MIN = 0.2f
        const val GAMMA_MAX = 5f

        fun fromJson(o: JSONObject?): PressureConfig {
            if (o == null) return PressureConfig()
            val min = o.optDouble("min", 0.0).toFloat().coerceIn(0f, 0.9f)
            val max = o.optDouble("max", 1.0).toFloat().coerceIn(min + 0.05f, 1f)
            val gamma = o.optDouble("gamma", 1.0).toFloat().coerceIn(GAMMA_MIN, GAMMA_MAX)
            return PressureConfig(min, max, gamma)
        }
    }
}

data class Config(
    /** false — драйвер отпускает планшет, и Android работает с ним как обычно. */
    val enabled: Boolean = true,
    /** Подстраивать область под текущий поворот экрана. */
    val followRotation: Boolean = true,
    val portrait: OrientationConfig = OrientationConfig(),
    val landscape: OrientationConfig = OrientationConfig(),
    val pressure: PressureConfig = PressureConfig(),
) {
    fun forOrientation(landscapeScreen: Boolean) = if (landscapeScreen) landscape else portrait

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("enabled", enabled)
        .put("followRotation", followRotation)
        .put("portrait", portrait.toJson())
        .put("landscape", landscape.toJson())
        .put("pressure", pressure.toJson())

    companion object {
        fun fromJson(text: String): Config {
            val o = JSONObject(text)
            return Config(
                enabled = o.optBoolean("enabled", true),
                followRotation = o.optBoolean("followRotation", true),
                portrait = OrientationConfig.fromJson(o.optJSONObject("portrait")),
                landscape = OrientationConfig.fromJson(o.optJSONObject("landscape")),
                pressure = PressureConfig.fromJson(o.optJSONObject("pressure")),
            )
        }
    }
}
