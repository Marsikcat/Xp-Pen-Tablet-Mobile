package com.neon.xppen

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.PointF
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.neon.xppen.core.AreaMode
import com.neon.xppen.core.Config
import com.neon.xppen.core.Mapping
import com.neon.xppen.core.OrientationConfig
import com.neon.xppen.core.PressureConfig
import com.neon.xppen.core.RectN
import com.neon.xppen.ui.PressureCurveView
import com.neon.xppen.ui.TabletAreaView
import com.neon.xppen.ui.TestCanvasView
import java.io.File
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity(), InputManager.InputDeviceListener {

    private lateinit var store: ConfigStore
    private var config = Config()

    /** Какую ориентацию сейчас редактируем. */
    private var editLandscape = false

    /** Размер планшета, мм (берётся из InputDevice, по умолчанию G640). */
    private var tabletW = 160f
    private var tabletH = 100f
    private var tabletName: String? = null
    private var driverActive = false

    private lateinit var inputManager: InputManager
    private val handler = Handler(Looper.getMainLooper())
    private var updatingUi = false

    private lateinit var tabletStatus: TextView
    private lateinit var driverStatus: TextView
    private lateinit var startHint: View
    private lateinit var enabled: Switch
    private lateinit var followRotation: Switch
    private lateinit var orientationTabs: RadioGroup
    private lateinit var rotationGroup: RadioGroup
    private lateinit var areaModeGroup: RadioGroup
    private lateinit var tabletArea: TabletAreaView
    private lateinit var areaInfo: TextView
    private lateinit var customControls: View
    private lateinit var keepAspect: CheckBox
    private lateinit var curve: PressureCurveView
    private lateinit var gammaLabel: TextView
    private lateinit var gammaBar: SeekBar
    private lateinit var minLabel: TextView
    private lateinit var minBar: SeekBar
    private lateinit var maxLabel: TextView
    private lateinit var maxBar: SeekBar
    private lateinit var testInfo: TextView
    private lateinit var testCanvas: TestCanvasView

    private val saveRunnable = Runnable { store.save(config) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ConfigStore(getExternalFilesDir(null)!!)
        store.writeStartScript()
        config = store.load()
        inputManager = getSystemService(InputManager::class.java)

        bindViews()
        editLandscape = config.followRotation && isLandscapeNow()
        refreshDevices()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, handler)
        refreshDevices()
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        inputManager.unregisterInputDeviceListener(this)
        handler.removeCallbacks(saveRunnable)
        store.save(config)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (config.followRotation) editLandscape = isLandscapeNow()
        refreshUi()
    }

    override fun onInputDeviceAdded(deviceId: Int) = onDevicesChanged()
    override fun onInputDeviceRemoved(deviceId: Int) = onDevicesChanged()
    override fun onInputDeviceChanged(deviceId: Int) = onDevicesChanged()

    private fun onDevicesChanged() {
        refreshDevices()
        refreshUi()
    }

    // ---------- Устройства ----------

    private fun refreshDevices() {
        tabletName = null
        driverActive = false
        for (id in inputManager.inputDeviceIds) {
            val d = inputManager.getInputDevice(id) ?: continue
            if (d.vendorId == VIRTUAL_VENDOR && d.productId == VIRTUAL_PRODUCT) {
                driverActive = true
                continue
            }
            val isPen = d.supportsSource(InputDevice.SOURCE_STYLUS) && d.isExternal
            if (!isPen) continue
            if (tabletName != null && d.vendorId != XPPEN_VENDOR) continue
            tabletName = d.name
            val rx = d.getMotionRange(MotionEvent.AXIS_X)
            val ry = d.getMotionRange(MotionEvent.AXIS_Y)
            if (rx != null && ry != null && rx.resolution > 0f && ry.resolution > 0f) {
                // Диапазоны осей Android отдаёт в текущей ориентации экрана; у планшета длинная сторона — родная ось X.
                val a = rx.range / rx.resolution
                val b = ry.range / ry.resolution
                tabletW = maxOf(a, b)
                tabletH = minOf(a, b)
            }
        }
    }

    // ---------- Экран ----------

    private fun isLandscapeNow() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Размер экрана «как видит пользователь» сейчас. */
    private fun currentScreenSize(): Pair<Float, Float> {
        val b = windowManager.currentWindowMetrics.bounds
        return b.width().toFloat() to b.height().toFloat()
    }

    /** Пропорции экрана (ширина/высота) для редактируемой ориентации. */
    private fun screenAspect(landscape: Boolean): Float {
        val (w, h) = currentScreenSize()
        val portraitAspect = minOf(w, h) / maxOf(w, h)
        return if (landscape) 1f / portraitAspect else portraitAspect
    }

    private fun screenSizeFor(landscape: Boolean): Pair<Float, Float> {
        val (w, h) = currentScreenSize()
        val short = minOf(w, h)
        val long = maxOf(w, h)
        return if (landscape) long to short else short to long
    }

    private fun editedOc(): OrientationConfig = config.forOrientation(editLandscape && config.followRotation)

    private fun effectiveArea(oc: OrientationConfig, landscape: Boolean): RectN {
        val (w, h) = screenSizeFor(landscape)
        return Mapping.effectiveArea(oc, tabletW, tabletH, w, h)
    }

    // ---------- Изменение настроек ----------

    private fun update(final: Boolean = true, change: (Config) -> Config) {
        config = change(config)
        handler.removeCallbacks(saveRunnable)
        if (final) store.save(config) else handler.postDelayed(saveRunnable, 120)
        refreshUi()
    }

    private fun updateOc(final: Boolean = true, change: (OrientationConfig) -> OrientationConfig) = update(final) { c ->
        if (editLandscape && c.followRotation) c.copy(landscape = change(c.landscape))
        else c.copy(portrait = change(c.portrait))
    }

    private fun bindViews() {
        tabletStatus = findViewById(R.id.tabletStatus)
        driverStatus = findViewById(R.id.driverStatus)
        startHint = findViewById(R.id.startHint)
        enabled = findViewById(R.id.enabled)
        followRotation = findViewById(R.id.followRotation)
        orientationTabs = findViewById(R.id.orientationTabs)
        rotationGroup = findViewById(R.id.rotation)
        areaModeGroup = findViewById(R.id.areaMode)
        tabletArea = findViewById(R.id.tabletArea)
        areaInfo = findViewById(R.id.areaInfo)
        customControls = findViewById(R.id.customControls)
        keepAspect = findViewById(R.id.keepAspect)
        curve = findViewById(R.id.curve)
        gammaLabel = findViewById(R.id.gammaLabel)
        gammaBar = findViewById(R.id.gamma)
        minLabel = findViewById(R.id.minLabel)
        minBar = findViewById(R.id.min)
        maxLabel = findViewById(R.id.maxLabel)
        maxBar = findViewById(R.id.max)
        testInfo = findViewById(R.id.testInfo)
        testCanvas = findViewById(R.id.testCanvas)

        findViewById<TextView>(R.id.startCommand).text = START_COMMAND
        findViewById<Button>(R.id.copyCommand).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", START_COMMAND))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        enabled.setOnCheckedChangeListener { _, on -> if (!updatingUi) update { it.copy(enabled = on) } }
        followRotation.setOnCheckedChangeListener { _, on ->
            if (updatingUi) return@setOnCheckedChangeListener
            editLandscape = on && isLandscapeNow()
            update { it.copy(followRotation = on) }
        }
        orientationTabs.setOnCheckedChangeListener { _, id ->
            if (updatingUi) return@setOnCheckedChangeListener
            editLandscape = id == R.id.tabLandscape
            refreshUi()
        }
        rotationGroup.setOnCheckedChangeListener { _, id ->
            if (updatingUi) return@setOnCheckedChangeListener
            val rot = when (id) {
                R.id.rot90 -> 90
                R.id.rot180 -> 180
                R.id.rot270 -> 270
                else -> 0
            }
            updateOc { oc ->
                val next = oc.copy(rotation = rot)
                // Своя область задана в координатах повёрнутого планшета — при повороте начинаем с «по пропорциям».
                if (oc.areaMode == AreaMode.CUSTOM) next.copy(area = effectiveArea(next.copy(areaMode = AreaMode.FIT), editLandscapeForSize()))
                else next
            }
        }
        areaModeGroup.setOnCheckedChangeListener { _, id ->
            if (updatingUi) return@setOnCheckedChangeListener
            val mode = when (id) {
                R.id.modeFull -> AreaMode.FULL
                R.id.modeCustom -> AreaMode.CUSTOM
                else -> AreaMode.FIT
            }
            updateOc { oc ->
                if (mode == AreaMode.CUSTOM && oc.areaMode != AreaMode.CUSTOM) {
                    // Начинаем свою область с текущей, чтобы не прыгало.
                    oc.copy(areaMode = mode, area = effectiveArea(oc, editLandscapeForSize()))
                } else oc.copy(areaMode = mode)
            }
        }
        tabletArea.onAreaChanged = { area, final -> updateOc(final) { it.copy(area = area) } }
        keepAspect.setOnCheckedChangeListener { _, on ->
            if (updatingUi) return@setOnCheckedChangeListener
            updateOc { oc ->
                val next = oc.copy(keepAspect = on)
                if (on) next.copy(area = tabletArea.fitAspectInside(oc.area)) else next
            }
        }
        findViewById<Button>(R.id.areaReset).setOnClickListener {
            updateOc { oc -> oc.copy(area = effectiveArea(oc.copy(areaMode = AreaMode.FIT), editLandscapeForSize())) }
        }

        curve.onGammaChanged = { g, final -> update(final) { it.copy(pressure = it.pressure.copy(gamma = g)) } }
        gammaBar.setOnSeekBarChangeListener(seek { p, fromUser ->
            if (fromUser) update(false) { it.copy(pressure = it.pressure.copy(gamma = progressToGamma(p))) }
        })
        minBar.setOnSeekBarChangeListener(seek { p, fromUser ->
            if (!fromUser) return@seek
            update(false) { c ->
                val min = p / 100f
                c.copy(pressure = c.pressure.copy(min = min, max = maxOf(c.pressure.max, min + 0.05f)))
            }
        })
        maxBar.setOnSeekBarChangeListener(seek { p, fromUser ->
            if (!fromUser) return@seek
            update(false) { c ->
                val max = (40 + p) / 100f
                c.copy(pressure = c.pressure.copy(max = max, min = minOf(c.pressure.min, max - 0.05f)))
            }
        })
        findViewById<Button>(R.id.pressureReset).setOnClickListener { update { it.copy(pressure = PressureConfig()) } }

        testCanvas.onSample = { p, e -> onTestSample(p, e) }
        findViewById<Button>(R.id.testClear).setOnClickListener {
            testCanvas.clear()
            curve.livePoint = null
        }
    }

    /** Для расчёта области: какая ориентация экрана у редактируемой настройки. */
    private fun editLandscapeForSize() = editLandscape && config.followRotation

    private fun seek(onChange: (Int, Boolean) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = onChange(progress, fromUser)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {
            handler.removeCallbacks(saveRunnable)
            store.save(config)
        }
    }

    // ---------- Отрисовка состояния ----------

    private fun refreshUi() {
        updatingUi = true
        try {
            val name = tabletName
            tabletStatus.text = if (name != null) {
                getString(R.string.tablet_found, name, tabletW.roundToInt(), tabletH.roundToInt())
            } else getString(R.string.tablet_missing)

            val okColor = getColor(R.color.ok)
            val warnColor = getColor(R.color.warn)
            when {
                !config.enabled -> {
                    driverStatus.setText(R.string.driver_disabled)
                    driverStatus.setTextColor(getColor(R.color.text_secondary))
                    startHint.visibility = View.GONE
                }
                driverActive -> {
                    driverStatus.setText(R.string.driver_running)
                    driverStatus.setTextColor(okColor)
                    startHint.visibility = View.GONE
                }
                name == null -> {
                    driverStatus.setText(R.string.driver_waiting)
                    driverStatus.setTextColor(getColor(R.color.text_secondary))
                    startHint.visibility = View.VISIBLE
                }
                else -> {
                    driverStatus.setText(R.string.driver_not_running)
                    driverStatus.setTextColor(warnColor)
                    startHint.visibility = View.VISIBLE
                }
            }
            enabled.isChecked = config.enabled

            followRotation.isChecked = config.followRotation
            orientationTabs.visibility = if (config.followRotation) View.VISIBLE else View.GONE
            orientationTabs.check(if (editLandscape && config.followRotation) R.id.tabLandscape else R.id.tabPortrait)

            val oc = editedOc()
            rotationGroup.check(
                when (oc.rotation) {
                    90 -> R.id.rot90
                    180 -> R.id.rot180
                    270 -> R.id.rot270
                    else -> R.id.rot0
                },
            )
            areaModeGroup.check(
                when (oc.areaMode) {
                    AreaMode.FIT -> R.id.modeFit
                    AreaMode.FULL -> R.id.modeFull
                    AreaMode.CUSTOM -> R.id.modeCustom
                },
            )
            val landscape = editLandscapeForSize()
            val area = effectiveArea(oc, landscape)
            tabletArea.setup(tabletW, tabletH, oc.rotation, area, oc.areaMode == AreaMode.CUSTOM, oc.keepAspect, screenAspect(landscape))
            customControls.visibility = if (oc.areaMode == AreaMode.CUSTOM) View.VISIBLE else View.GONE
            keepAspect.isChecked = oc.keepAspect
            val (rw, rh) = Mapping.rotatedSize(tabletW, tabletH, oc.rotation)
            areaInfo.text = getString(R.string.area_info, area.width * rw, area.height * rh, area.width * area.height * 100f)

            val pc = config.pressure
            curve.pressure = pc
            gammaBar.progress = gammaToProgress(pc.gamma)
            val kind = when {
                pc.gamma < 0.95f -> getString(R.string.gamma_soft)
                pc.gamma > 1.05f -> getString(R.string.gamma_hard)
                else -> getString(R.string.gamma_linear)
            }
            gammaLabel.text = getString(R.string.gamma_label, pc.gamma, kind)
            minBar.progress = (pc.min * 100).roundToInt()
            minLabel.text = getString(R.string.min_label, (pc.min * 100).roundToInt())
            maxBar.progress = (pc.max * 100).roundToInt() - 40
            maxLabel.text = getString(R.string.max_label, (pc.max * 100).roundToInt())
        } finally {
            updatingUi = false
        }
    }

    // ---------- Перо в окне приложения ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        trackPen(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        trackPen(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    /** Показываем на схеме планшета, где сейчас перо (только когда работает драйвер и открыта текущая ориентация). */
    private fun trackPen(ev: MotionEvent) {
        if (!isFromDriver(ev)) return
        val gone = ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT || ev.actionMasked == MotionEvent.ACTION_CANCEL
        val landscapeNow = isLandscapeNow() && config.followRotation
        if (gone || landscapeNow != editLandscapeForSize()) {
            if (tabletArea.penPos != null) tabletArea.penPos = null
            return
        }
        val (w, h) = currentScreenSize()
        val s = (ev.rawX / w).coerceIn(0f, 1f)
        val t = (ev.rawY / h).coerceIn(0f, 1f)
        tabletArea.penPos = tabletArea.screenToTablet(s, t, effectiveArea(editedOc(), landscapeNow))
    }

    private fun isFromDriver(ev: MotionEvent) =
        ev.device?.let { it.vendorId == VIRTUAL_VENDOR && it.productId == VIRTUAL_PRODUCT } == true

    private fun onTestSample(out: Float, e: MotionEvent) {
        val fromDriver = isFromDriver(e)
        val tool = when (e.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> getString(R.string.tool_stylus)
            MotionEvent.TOOL_TYPE_FINGER -> getString(R.string.tool_finger)
            else -> getString(R.string.tool_other)
        }
        testInfo.text = getString(R.string.test_info, out, tool)
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        curve.livePoint = when {
            e.actionMasked == MotionEvent.ACTION_HOVER_MOVE || e.actionMasked == MotionEvent.ACTION_HOVER_EXIT || out <= 0f -> null
            // Через драйвер приходит уже пересчитанный нажим — восстанавливаем исходный для точки на графике.
            fromDriver -> PointF(Mapping.rawFromPressure(out, config.pressure), out)
            else -> PointF(out, out)
        }
    }

    companion object {
        const val XPPEN_VENDOR = 0x28bd
        const val VIRTUAL_VENDOR = 0x1209
        const val VIRTUAL_PRODUCT = 0x640A
        const val START_COMMAND = "adb shell sh /sdcard/Android/data/com.neon.xppen/files/start.sh"

        /** Ползунок 0..200 → gamma 0.2..5 (логарифмически, 100 — линейная). */
        fun progressToGamma(p: Int): Float = 5f.pow((p - 100) / 100f)

        fun gammaToProgress(g: Float): Int = (100 + 100 * ln(g) / ln(5f)).roundToInt().coerceIn(0, 200)
    }
}

/** Настройки лежат в Android/data приложения: оттуда их может прочитать драйвер, запущенный через ADB. */
class ConfigStore(private val dir: File) {
    private val file = File(dir, "config.json")

    fun load(): Config = try {
        if (file.exists()) Config.fromJson(file.readText()) else Config()
    } catch (e: Exception) {
        Config()
    }

    fun save(config: Config) {
        runCatching {
            val tmp = File(dir, "config.json.tmp")
            tmp.writeText(config.toJson().toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(config.toJson().toString(2))
                tmp.delete()
            }
        }
    }

    /** Скрипт запуска для `adb shell sh .../start.sh` — путь к APK меняется после каждой установки, поэтому ищем его через pm. */
    fun writeStartScript() {
        runCatching { File(dir, "start.sh").writeText(START_SCRIPT) }
    }

    companion object {
        val START_SCRIPT = """
            |#!/system/bin/sh
            |# Запуск драйвера Xp Pen Tablet Mobile: adb shell sh /sdcard/Android/data/com.neon.xppen/files/start.sh
            |APK=${'$'}(pm path com.neon.xppen | head -n 1 | cut -d: -f2)
            |if [ -z "${'$'}APK" ]; then
            |  echo "FAIL: приложение com.neon.xppen не установлено"
            |  exit 1
            |fi
            |OLD=${'$'}(pidof g640_helper)
            |if [ -n "${'$'}OLD" ]; then
            |  kill ${'$'}OLD 2>/dev/null
            |  sleep 0.5
            |fi
            |CLASSPATH="${'$'}APK" setsid nohup app_process /system/bin --nice-name=g640_helper com.neon.xppen.helper.HelperMain "${'$'}@" > /dev/null 2>&1 < /dev/null &
            |sleep 1.5
            |if pidof g640_helper > /dev/null; then
            |  echo "OK: драйвер запущен"
            |else
            |  echo "FAIL: драйвер не запустился, журнал: /data/local/tmp/g640_helper.log"
            |  tail -n 20 /data/local/tmp/g640_helper.log 2>/dev/null
            |  exit 1
            |fi
            |""".trimMargin()
    }
}
