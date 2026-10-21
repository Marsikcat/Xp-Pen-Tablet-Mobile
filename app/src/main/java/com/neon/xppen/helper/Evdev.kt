package com.neon.xppen.helper

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Устройство /dev/input/eventN, которое драйвер забирает себе (EVIOCGRAB), чтобы Android его не обрабатывал. */
class EvdevDevice(path: String) : Closeable {
    private val fd: FileDescriptor = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
    private val pollFds = arrayOf(StructPollfd().also {
        it.fd = fd
        it.events = OsConstants.POLLIN.toShort()
    })

    /** Монопольный захват: пока fd открыт, события получает только этот процесс. */
    fun grab() {
        Ioctl.int(fd, EVIOCGRAB)
    }

    /**
     * Читает события в [buf] (по 24 байта на input_event). Возвращает число байт, 0 — если за [timeoutMs] ничего не пришло.
     * Бросает ErrnoException, если устройство отключили.
     */
    fun read(buf: ByteArray, timeoutMs: Int): Int {
        pollFds[0].revents = 0
        if (Os.poll(pollFds, timeoutMs) <= 0) return 0
        val re = pollFds[0].revents.toInt()
        if (re and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
            throw ErrnoException("poll", OsConstants.ENODEV)
        }
        return Os.read(fd, buf, 0, buf.size)
    }

    override fun close() {
        runCatching { Os.close(fd) }
    }

    companion object {
        const val EVENT_SIZE = 24 // struct input_event на 64-битном Android
        private const val EVIOCGRAB = 0x40044590

        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_ABS = 0x03
        const val SYN_REPORT = 0
        const val SYN_DROPPED = 3
        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val ABS_PRESSURE = 0x18
        const val BTN_TOOL_PEN = 0x140
        const val BTN_TOOL_RUBBER = 0x141
        const val BTN_TOUCH = 0x14a
        const val BTN_STYLUS = 0x14b
        const val BTN_STYLUS2 = 0x14c
    }
}

/** ioctl через скрытый libcore.io.Os (публичного API для ioctl нет). */
private object Ioctl {
    private val os: Any by lazy { Class.forName("libcore.io.Libcore").getField("os").get(null)!! }
    private val method: Method by lazy {
        Class.forName("libcore.io.Os").methods.first { it.name == "ioctlInt" }
    }

    fun int(fd: FileDescriptor, cmd: Int): Int {
        try {
            val m = method
            val r = if (m.parameterTypes.size == 2) {
                m.invoke(os, fd, cmd)
            } else {
                // Старые версии Android: ioctlInt(fd, cmd, Int32Ref/MutableInt)
                val ref = m.parameterTypes[2].getConstructor(Int::class.javaPrimitiveType).newInstance(1)
                m.invoke(os, fd, cmd, ref)
            }
            return r as Int
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }
}
