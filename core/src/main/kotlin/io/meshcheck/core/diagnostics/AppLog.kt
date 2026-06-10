package io.meshcheck.core.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tiny in-process log buffer the app can show to itself.
 *
 * The agent connection and the enrollment flow record their lifecycle here so a
 * contributor (or a developer) can see *why* the app is in the state it is —
 * which gateway it dialed, what the redeem endpoint returned, why a connection
 * dropped. Every entry is also mirrored to logcat.
 *
 * It lives in `:core` because both `:protocol` (the agent client) and `:data`
 * (enrollment) need to write to it, and `:app` needs to read it. The buffer is
 * a fixed-size ring — only the most recent [CAPACITY] entries are kept, so it
 * cannot grow without bound on a long-lived connection.
 *
 * The viewer that surfaces this is **debug-build only** (see the app module);
 * nothing here is secret on its own, but callers still redact credentials
 * before logging them.
 */
object AppLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val atMillis: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private const val CAPACITY = 500

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(CAPACITY)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** The buffered entries, oldest first. Observable from Compose. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun log(level: Level, tag: String, message: String) {
        when (level) {
            Level.DEBUG -> Log.d(tag, message)
            Level.INFO -> Log.i(tag, message)
            Level.WARN -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message)
        }
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > CAPACITY) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }
}
