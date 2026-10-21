package com.neon.xppen.helper

import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.FileDescriptor

/**
 * Виртуальное HID-перо через /dev/uhid. Для Android это обычный USB-дигитайзер с нажимом,
 * координаты задаются в физической (портретной) ориентации экрана, 0..[LOGICAL_MAX].
 */
class UhidPen : Closeable {
    private val fd: FileDescriptor = Os.open("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0)
    private val report = ByteArray(4 + 2 + REPORT_SIZE)

    @Volatile
    private var closed = false
    private val reader = Thread(::readLoop, "uhid-reader")

    init {
        create()
        reader.isDaemon = true
        reader.start()
    }

    private fun create() {
        val name = NAME.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(4 + 128 + 64 + 64 + 2 + 2 + 4 * 4 + DESCRIPTOR.size)
        var o = 0
        o = putInt(buf, o, UHID_CREATE2)
        System.arraycopy(name, 0, buf, o, minOf(name.size, 127))
        o += 128 + 64 + 64 // name, phys, uniq
        o = putShort(buf, o, DESCRIPTOR.size)
        o = putShort(buf, o, BUS_USB)
        o = putInt(buf, o, VENDOR)
        o = putInt(buf, o, PRODUCT)
        o = putInt(buf, o, 0x0100) // version
        o = putInt(buf, o, 0) // country
        System.arraycopy(DESCRIPTOR, 0, buf, o, DESCRIPTOR.size)
        write(buf)
    }

    /** Отправить состояние пера. [x], [y] — 0..LOGICAL_MAX, [pressure] — 0..PRESSURE_MAX. */
    fun send(tip: Boolean, barrel: Boolean, barrel2: Boolean, inRange: Boolean, x: Int, y: Int, pressure: Int) {
        var bits = 0
        if (tip) bits = bits or 0x01
        if (barrel) bits = bits or 0x02
        if (barrel2) bits = bits or 0x04
        // 0x08 — Invert (ластик), пока не используется
        if (inRange) bits = bits or 0x10
        var o = putInt(report, 0, UHID_INPUT2)
        o = putShort(report, o, REPORT_SIZE)
        report[o++] = REPORT_ID.toByte()
        report[o++] = bits.toByte()
        o = putShort(report, o, x)
        o = putShort(report, o, y)
        putShort(report, o, pressure)
        write(report)
    }

    private fun write(b: ByteArray) {
        Os.write(fd, b, 0, b.size)
    }

    /** Ядро может запросить feature/get-report — отвечаем ошибкой, иначе оно ждёт таймаут. */
    private fun readLoop() {
        val buf = ByteArray(4380)
        val pfd = arrayOf(StructPollfd().also {
            it.fd = fd
            it.events = OsConstants.POLLIN.toShort()
        })
        try {
            while (!closed) {
                pfd[0].revents = 0
                if (Os.poll(pfd, 500) <= 0 || closed) continue
                val n = Os.read(fd, buf, 0, buf.size)
                if (n < 4) continue
                when (val type = getInt(buf, 0)) {
                    UHID_START -> HLog.i("uhid: start")
                    UHID_OPEN -> HLog.i("uhid: open (Android подключил виртуальное перо)")
                    UHID_CLOSE -> HLog.i("uhid: close")
                    UHID_STOP -> HLog.i("uhid: stop")
                    UHID_GET_REPORT -> {
                        val reply = ByteArray(12)
                        var o = putInt(reply, 0, UHID_GET_REPORT_REPLY)
                        o = putInt(reply, o, getInt(buf, 4))
                        putShort(reply, o, EIO)
                        write(reply)
                    }
                    UHID_SET_REPORT -> {
                        val reply = ByteArray(10)
                        var o = putInt(reply, 0, UHID_SET_REPORT_REPLY)
                        o = putInt(reply, o, getInt(buf, 4))
                        putShort(reply, o, EIO)
                        write(reply)
                    }
                    else -> if (type != UHID_OUTPUT) HLog.i("uhid: event $type")
                }
            }
        } catch (e: Exception) {
            if (!closed) HLog.e("uhid reader", e)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { write(ByteArray(4).also { putInt(it, 0, UHID_DESTROY) }) }
        runCatching { Os.close(fd) }
    }

    companion object {
        const val NAME = "Xp Pen Tablet Mobile (virtual pen)"
        const val VENDOR = 0x1209
        const val PRODUCT = 0x640A
        const val LOGICAL_MAX = 32767
        const val PRESSURE_MAX = 8191

        private const val BUS_USB = 0x03
        private const val EIO = 5
        private const val REPORT_ID = 1
        private const val REPORT_SIZE = 8

        private const val UHID_DESTROY = 1
        private const val UHID_START = 2
        private const val UHID_STOP = 3
        private const val UHID_OPEN = 4
        private const val UHID_CLOSE = 5
        private const val UHID_OUTPUT = 6
        private const val UHID_GET_REPORT = 9
        private const val UHID_GET_REPORT_REPLY = 10
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val UHID_SET_REPORT = 13
        private const val UHID_SET_REPORT_REPLY = 14

        /** Digitizer / Pen: кнопки, X, Y (16 бит, 0..32767), нажим (0..8191). */
        private val DESCRIPTOR = intArrayOf(
            0x05, 0x0D,             // Usage Page (Digitizer)
            0x09, 0x02,             // Usage (Pen)
            0xA1, 0x01,             // Collection (Application)
            0x85, REPORT_ID,        //   Report ID
            0x09, 0x20,             //   Usage (Stylus)
            0xA1, 0x00,             //   Collection (Physical)
            0x09, 0x42,             //     Usage (Tip Switch)
            0x09, 0x44,             //     Usage (Barrel Switch)
            0x09, 0x5A,             //     Usage (Secondary Barrel Switch)
            0x09, 0x3C,             //     Usage (Invert)
            0x09, 0x32,             //     Usage (In Range)
            0x15, 0x00,             //     Logical Minimum (0)
            0x25, 0x01,             //     Logical Maximum (1)
            0x75, 0x01,             //     Report Size (1)
            0x95, 0x05,             //     Report Count (5)
            0x81, 0x02,             //     Input (Data, Var, Abs)
            0x95, 0x03,             //     Report Count (3)
            0x81, 0x03,             //     Input (Const) — выравнивание до байта
            0x05, 0x01,             //     Usage Page (Generic Desktop)
            0x09, 0x30,             //     Usage (X)
            0x09, 0x31,             //     Usage (Y)
            0x15, 0x00,             //     Logical Minimum (0)
            0x26, 0xFF, 0x7F,       //     Logical Maximum (32767)
            0x75, 0x10,             //     Report Size (16)
            0x95, 0x02,             //     Report Count (2)
            0x81, 0x02,             //     Input (Data, Var, Abs)
            0x05, 0x0D,             //     Usage Page (Digitizer)
            0x09, 0x30,             //     Usage (Tip Pressure)
            0x26, 0xFF, 0x1F,       //     Logical Maximum (8191)
            0x95, 0x01,             //     Report Count (1)
            0x81, 0x02,             //     Input (Data, Var, Abs)
            0xC0,                   //   End Collection
            0xC0,                   // End Collection
        ).map { it.toByte() }.toByteArray()

        private fun putInt(b: ByteArray, o: Int, v: Int): Int {
            b[o] = v.toByte()
            b[o + 1] = (v shr 8).toByte()
            b[o + 2] = (v shr 16).toByte()
            b[o + 3] = (v shr 24).toByte()
            return o + 4
        }

        private fun putShort(b: ByteArray, o: Int, v: Int): Int {
            b[o] = v.toByte()
            b[o + 1] = (v shr 8).toByte()
            return o + 2
        }

        private fun getInt(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    }
}
