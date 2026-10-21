package com.neon.xppen.helper

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал драйвера: logcat + файл, который можно посмотреть через adb. */
object HLog {
    const val FILE = "/data/local/tmp/g640_helper.log"
    private const val TAG = "G640Helper"
    private const val MAX_SIZE = 256 * 1024

    private var writer: FileWriter? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun open() {
        runCatching {
            val f = File(FILE)
            writer = FileWriter(f, f.length() < MAX_SIZE)
        }
    }

    @Synchronized
    fun i(msg: String) {
        Log.i(TAG, msg)
        val line = "${time.format(Date())} $msg"
        println(line)
        writer?.let { w ->
            runCatching {
                w.write(line)
                w.write("\n")
                w.flush()
            }
        }
    }

    fun e(msg: String, t: Throwable? = null) = i(if (t != null) "ERROR $msg: $t" else "ERROR $msg")
}
