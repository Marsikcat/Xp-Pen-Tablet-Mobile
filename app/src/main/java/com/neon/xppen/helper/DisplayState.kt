package com.neon.xppen.helper

import android.os.IBinder
import java.lang.reflect.Method

/** Текущий поворот и натуральный (портретный) размер основного экрана. Обновляется опросом. */
object DisplayState {
    @Volatile
    var rotation = 0
        private set

    @Volatile
    var naturalWidth = 1080
        private set

    @Volatile
    var naturalHeight = 2340
        private set

    private var dmg: Any? = null
    private var getDisplayInfo: Method? = null
    private var windowManager: Any? = null
    private var getRotation: Method? = null
    private var failedLogged = false

    fun refresh() {
        if (refreshFromDisplayManager()) return
        refreshRotationFromWindowManager()
    }

    private fun refreshFromDisplayManager(): Boolean = try {
        if (getDisplayInfo == null) {
            val cls = Class.forName("android.hardware.display.DisplayManagerGlobal")
            dmg = cls.getMethod("getInstance").invoke(null)
            getDisplayInfo = cls.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        }
        val info = getDisplayInfo!!.invoke(dmg, 0)!!
        val c = info.javaClass
        val rot = c.getField("rotation").getInt(info)
        val w = c.getField("logicalWidth").getInt(info)
        val h = c.getField("logicalHeight").getInt(info)
        if (w > 0 && h > 0) {
            if (rot % 2 == 1) {
                naturalWidth = h
                naturalHeight = w
            } else {
                naturalWidth = w
                naturalHeight = h
            }
        }
        rotation = rot and 3
        true
    } catch (e: Exception) {
        if (!failedLogged) {
            HLog.e("DisplayManagerGlobal недоступен, использую IWindowManager", e)
            failedLogged = true
        }
        getDisplayInfo = null
        false
    }

    private fun refreshRotationFromWindowManager() {
        try {
            if (getRotation == null) {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java).invoke(null, "window") as IBinder
                windowManager = Class.forName("android.view.IWindowManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                getRotation = windowManager!!.javaClass.getMethod("getDefaultDisplayRotation")
            }
            rotation = (getRotation!!.invoke(windowManager) as Int) and 3
        } catch (e: Exception) {
            getRotation = null
        }
    }

    /** Размер экрана так, как его сейчас видит пользователь. */
    fun userSize(rot: Int): Pair<Float, Float> =
        if (rot % 2 == 1) naturalHeight.toFloat() to naturalWidth.toFloat()
        else naturalWidth.toFloat() to naturalHeight.toFloat()
}
