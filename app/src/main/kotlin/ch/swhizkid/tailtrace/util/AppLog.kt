package ch.swhizkid.tailtrace.util

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process ring buffer of app logs. Every line is also forwarded to logcat
 * so `adb logcat` and the in-app Logs screen stay in sync.
 *
 * Reading system logcat from the app requires READ_LOGS (not granted to a
 * normal or even typical priv-app), so this is the source of truth for the UI.
 */
object AppLog {

    enum class Level { D, I, W, E }

    data class Line(
        val id: Long,
        val t: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private const val CAP = 500
    private val seq = AtomicLong(0L)
    private val lock = Any()
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    fun d(tag: String, msg: String) = emit(Level.D, tag, msg)
    fun i(tag: String, msg: String) = emit(Level.I, tag, msg)
    fun w(tag: String, msg: String) = emit(Level.W, tag, msg)
    fun e(tag: String, msg: String, err: Throwable? = null) {
        val full = if (err?.message != null) "$msg: ${err.message}" else msg
        emit(Level.E, tag, full)
        if (err != null) toLogcat(Level.E, tag, msg, err)
    }

    fun clear() {
        synchronized(lock) { _lines.value = emptyList() }
    }

    private fun emit(level: Level, tag: String, msg: String) {
        toLogcat(level, tag, msg)
        val line = Line(
            id = seq.incrementAndGet(),
            t = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = msg
        )
        synchronized(lock) {
            val cur = _lines.value
            _lines.value = if (cur.size < CAP) cur + line else cur.drop(cur.size + 1 - CAP) + line
        }
    }

    /** android.util.Log is unmocked in JVM unit tests and throws. */
    private fun toLogcat(level: Level, tag: String, msg: String, err: Throwable? = null) {
        try {
            when (level) {
                Level.D -> if (err != null) Log.d(tag, msg, err) else Log.d(tag, msg)
                Level.I -> if (err != null) Log.i(tag, msg, err) else Log.i(tag, msg)
                Level.W -> if (err != null) Log.w(tag, msg, err) else Log.w(tag, msg)
                Level.E -> if (err != null) Log.e(tag, msg, err) else Log.e(tag, msg)
            }
        } catch (_: RuntimeException) {
            // JVM unit tests
        }
    }
}
