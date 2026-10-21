package com.neon.xppen.helper

/** Описание пера планшета, найденного в /dev/input. */
data class PenDeviceInfo(
    val path: String,
    val name: String,
    val vendor: Int,
    val product: Int,
    val xMin: Int, val xMax: Int, val xRes: Int,
    val yMin: Int, val yMax: Int, val yRes: Int,
    val pMin: Int, val pMax: Int,
) {
    /** Размер рабочей поверхности в мм (если ядро не знает разрешение — в условных единицах). */
    val widthMm: Float get() = (xMax - xMin).toFloat() / (if (xRes > 0 && yRes > 0) xRes else 1)
    val heightMm: Float get() = (yMax - yMin).toFloat() / (if (xRes > 0 && yRes > 0) yRes else 1)
}

/** Ищет USB-перо (планшет) через `getevent -il` — у оболочки нет доступа к sysfs и EVIOCGNAME из Java. */
object DeviceFinder {
    private const val XPPEN_VENDOR = 0x28bd

    private val reDevice = Regex("""add device \d+: (\S+)""")
    private val reBus = Regex("""bus:\s+([0-9a-fA-F]+)""")
    private val reVendor = Regex("""vendor\s+([0-9a-fA-F]+)""")
    private val reProduct = Regex("""product\s+([0-9a-fA-F]+)""")
    private val reName = Regex("""name:\s+"(.*)"""")
    private val reAbs = Regex(
        """(ABS_X|ABS_Y|ABS_PRESSURE)\s*:\s*value\s*-?\d+,\s*min\s*(-?\d+),\s*max\s*(-?\d+),\s*fuzz\s*-?\d+,\s*flat\s*-?\d+,\s*resolution\s*(-?\d+)""",
    )

    fun findPen(): PenDeviceInfo? {
        val text = try {
            val p = ProcessBuilder("getevent", "-il").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (e: Exception) {
            HLog.e("getevent", e)
            return null
        }
        return parse(text).sortedByDescending { it.vendor == XPPEN_VENDOR }.firstOrNull()
    }

    fun parse(text: String): List<PenDeviceInfo> = text.split("add device").drop(1).mapNotNull { body ->
        val block = "add device$body"
        val path = reDevice.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        val bus = reBus.find(block)?.groupValues?.get(1)?.toIntOrNull(16) ?: return@mapNotNull null
        val vendor = reVendor.find(block)?.groupValues?.get(1)?.toIntOrNull(16) ?: 0
        val product = reProduct.find(block)?.groupValues?.get(1)?.toIntOrNull(16) ?: 0
        val name = reName.find(block)?.groupValues?.get(1) ?: ""
        val hasPenTool = block.contains("BTN_DIGI") || block.contains("BTN_TOOL_PEN")
        val abs = reAbs.findAll(block).associate { m ->
            m.groupValues[1] to Triple(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
        }
        val x = abs["ABS_X"]
        val y = abs["ABS_Y"]
        val p = abs["ABS_PRESSURE"]
        val isOurs = vendor == UhidPen.VENDOR && product == UhidPen.PRODUCT
        // Только USB-устройства: встроенные дигитайзеры (S Pen и т.п.) не трогаем.
        if (bus != 0x03 || isOurs || !hasPenTool || x == null || y == null || p == null) return@mapNotNull null
        if (x.second <= x.first || y.second <= y.first || p.second <= p.first) return@mapNotNull null
        PenDeviceInfo(path, name, vendor, product, x.first, x.second, x.third, y.first, y.second, y.third, p.first, p.second)
    }
}
