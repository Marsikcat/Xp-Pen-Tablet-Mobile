package com.neon.xppen.helper

import android.net.LocalServerSocket
import android.system.ErrnoException
import com.neon.xppen.core.Config
import com.neon.xppen.core.Mapping
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * Драйвер, который запускается через ADB с правами оболочки:
 *   CLASSPATH=<apk> app_process /system/bin com.neon.xppen.helper.HelperMain
 *
 * Забирает перо планшета у Android (EVIOCGRAB), пересчитывает координаты и нажим
 * по настройкам приложения и отдаёт их системе через виртуальное перо (/dev/uhid).
 */
object HelperMain {
    const val CONFIG_PATH = "/sdcard/Android/data/com.neon.xppen/files/config.json"

    @Volatile
    private var config = Config()

    @JvmStatic
    fun main(args: Array<String>) {
        HLog.open()
        val configPath = args.indexOf("--config").let { if (it >= 0 && it + 1 < args.size) args[it + 1] else CONFIG_PATH }

        if ("--selftest" in args) {
            selfTest(configPath)
            return
        }

        // Один экземпляр: абстрактный unix-сокет как блокировка (держим ссылку до конца процесса).
        lock = try {
            LocalServerSocket("com.neon.xppen.g640_helper")
        } catch (e: IOException) {
            HLog.i("Драйвер уже запущен, выходим")
            exitProcess(0)
        }

        HLog.i("Драйвер запущен (pid ${android.os.Process.myPid()}), настройки: $configPath")
        startConfigWatcher(configPath)
        startDisplayWatcher()

        var lastListing = ""
        var lastScan = 0L
        var notFoundLogged = false
        while (true) {
            if (!config.enabled) {
                Thread.sleep(300)
                continue
            }
            // getevent запускаем только при изменениях в /dev/input (и изредка на всякий случай).
            val listing = File("/dev/input").list()?.sorted()?.joinToString(",") ?: ""
            val now = System.currentTimeMillis()
            if (listing == lastListing && now - lastScan < 5000) {
                Thread.sleep(1000)
                continue
            }
            lastListing = listing
            lastScan = now
            val device = DeviceFinder.findPen()
            if (device == null) {
                if (!notFoundLogged) HLog.i("Планшет не найден, жду подключения")
                notFoundLogged = true
                Thread.sleep(1000)
                continue
            }
            notFoundLogged = false
            runSession(device)
            // После сессии (отключили планшет или выключили коррекцию) ищем заново.
            lastListing = ""
            Thread.sleep(500)
        }
    }

    @Suppress("unused")
    private var lock: LocalServerSocket? = null

    private fun runSession(info: PenDeviceInfo) {
        val ev = try {
            EvdevDevice(info.path)
        } catch (e: ErrnoException) {
            HLog.e("Не удалось открыть ${info.path}", e)
            Thread.sleep(1000)
            return
        }
        try {
            ev.grab()
        } catch (e: Exception) {
            HLog.e("Не удалось захватить ${info.path}", e)
            ev.close()
            Thread.sleep(2000)
            return
        }
        val pen = try {
            UhidPen()
        } catch (e: Exception) {
            HLog.e("Не удалось создать виртуальное перо", e)
            ev.close()
            Thread.sleep(2000)
            return
        }
        HLog.i(
            "Работаю с \"${info.name}\" (${info.path}): X ${info.xMin}..${info.xMax}, Y ${info.yMin}..${info.yMax}, " +
                "нажим ${info.pMin}..${info.pMax}, ${"%.0f×%.0f".format(info.widthMm, info.heightMm)} мм",
        )

        val s = PenState()
        val buf = ByteArray(EvdevDevice.EVENT_SIZE * 64)
        var dropped = false
        try {
            while (config.enabled) {
                val n = ev.read(buf, 250)
                var o = 0
                while (o + EvdevDevice.EVENT_SIZE <= n) {
                    val type = u16(buf, o + 16)
                    val code = u16(buf, o + 18)
                    val value = s32(buf, o + 20)
                    o += EvdevDevice.EVENT_SIZE
                    when (type) {
                        EvdevDevice.EV_ABS -> when (code) {
                            EvdevDevice.ABS_X -> s.x = value
                            EvdevDevice.ABS_Y -> s.y = value
                            EvdevDevice.ABS_PRESSURE -> s.p = value
                        }
                        EvdevDevice.EV_KEY -> when (code) {
                            EvdevDevice.BTN_TOUCH -> s.tip = value != 0
                            EvdevDevice.BTN_STYLUS -> s.barrel = value != 0
                            EvdevDevice.BTN_STYLUS2 -> s.barrel2 = value != 0
                            EvdevDevice.BTN_TOOL_PEN, EvdevDevice.BTN_TOOL_RUBBER -> s.inRange = value != 0
                        }
                        EvdevDevice.EV_SYN -> when (code) {
                            EvdevDevice.SYN_DROPPED -> dropped = true
                            EvdevDevice.SYN_REPORT -> if (dropped) dropped = false else emit(s, info, pen)
                        }
                    }
                }
            }
            HLog.i("Коррекция выключена — планшет отдан Android")
            if (s.sentInRange) pen.send(false, false, false, false, 0, 0, 0)
        } catch (e: ErrnoException) {
            HLog.i("Планшет отключён (${e.message})")
        } catch (e: Exception) {
            HLog.e("Ошибка в сессии", e)
        } finally {
            pen.close()
            ev.close()
        }
    }

    private fun emit(s: PenState, info: PenDeviceInfo, pen: UhidPen) {
        val inRange = s.inRange || s.tip
        if (!inRange && !s.sentInRange) return
        val cfg = config

        val rot = if (cfg.followRotation) DisplayState.rotation else 0
        val (scrW, scrH) = DisplayState.userSize(rot)
        val oc = cfg.forOrientation(scrW > scrH)

        val u = ((s.x - info.xMin).toFloat() / (info.xMax - info.xMin)).coerceIn(0f, 1f)
        val v = ((s.y - info.yMin).toFloat() / (info.yMax - info.yMin)).coerceIn(0f, 1f)
        val (ur, vr) = Mapping.rotateTablet(u, v, oc.rotation)
        val area = Mapping.effectiveArea(oc, info.widthMm, info.heightMm, scrW, scrH)
        val sx = ((ur - area.l) / area.width).coerceIn(0f, 1f)
        val sy = ((vr - area.t) / area.height).coerceIn(0f, 1f)
        val (px, py) = Mapping.toNatural(sx, sy, rot)

        val n = ((s.p - info.pMin).toFloat() / (info.pMax - info.pMin)).coerceIn(0f, 1f)
        val tip = s.tip && n > cfg.pressure.min
        val pressure = if (tip) {
            (Mapping.pressure(n, cfg.pressure) * UhidPen.PRESSURE_MAX).roundToInt().coerceIn(1, UhidPen.PRESSURE_MAX)
        } else 0

        pen.send(
            tip, s.barrel, s.barrel2, inRange,
            (px * UhidPen.LOGICAL_MAX).roundToInt(), (py * UhidPen.LOGICAL_MAX).roundToInt(), pressure,
        )
        s.sentInRange = inRange
    }

    private fun startConfigWatcher(path: String) {
        val t = Thread({
            val f = File(path)
            var stamp = Long.MIN_VALUE
            var missingLogged = false
            while (true) {
                try {
                    val mod = if (f.exists()) f.lastModified() * 31 + f.length() else -1L
                    if (mod != stamp) {
                        stamp = mod
                        if (mod == -1L) {
                            if (!missingLogged) HLog.i("Файл настроек не найден — значения по умолчанию")
                            missingLogged = true
                            config = Config()
                        } else {
                            config = Config.fromJson(f.readText())
                            missingLogged = false
                            HLog.i("Настройки загружены: ${config.toJson()}")
                        }
                    }
                } catch (e: Exception) {
                    HLog.e("Не удалось прочитать настройки", e)
                }
                Thread.sleep(300)
            }
        }, "config-watcher")
        t.isDaemon = true
        t.start()
    }

    private fun startDisplayWatcher() {
        DisplayState.refresh()
        HLog.i("Экран: ${DisplayState.naturalWidth}×${DisplayState.naturalHeight}, поворот ${DisplayState.rotation}")
        val t = Thread({
            var last = DisplayState.rotation
            while (true) {
                Thread.sleep(250)
                DisplayState.refresh()
                if (DisplayState.rotation != last) {
                    last = DisplayState.rotation
                    HLog.i("Поворот экрана: $last")
                }
            }
        }, "display-watcher")
        t.isDaemon = true
        t.start()
    }

    private fun selfTest(configPath: String) {
        DisplayState.refresh()
        println("Экран: ${DisplayState.naturalWidth}×${DisplayState.naturalHeight}, поворот ${DisplayState.rotation}")
        val f = File(configPath)
        if (f.canRead()) {
            println("Настройки: $configPath — читаются: ${f.readText()}")
            config = Config.fromJson(f.readText())
        } else {
            println("Настройки: $configPath — нет доступа или файла")
        }
        val d = DeviceFinder.findPen()
        println("Перо: $d")
        if (d != null) {
            for (rot in 0..3) {
                val (w, h) = DisplayState.userSize(rot)
                val oc = config.forOrientation(w > h)
                val a = Mapping.effectiveArea(oc, d.widthMm, d.heightMm, w, h)
                println("поворот $rot: экран ${w.toInt()}×${h.toInt()}, область $a")
            }
        }
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun s32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or (b[o + 3].toInt() shl 24)

    private class PenState {
        var x = 0
        var y = 0
        var p = 0
        var tip = false
        var barrel = false
        var barrel2 = false
        var inRange = false
        var sentInRange = false
    }
}
