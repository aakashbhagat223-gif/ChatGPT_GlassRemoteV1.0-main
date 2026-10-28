package com.glassremote.data.macro

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glassremote.domain.repository.TvRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.macroStore by preferencesDataStore("macros")

object MacroIds {
    const val STREMIO = "stremio"
    const val HDMI2 = "hdmi2"
    const val BACKLIGHT_OPEN = "backlight_open"
    const val BACKLIGHT_CLOSE = "backlight_close"

    val all = listOf(STREMIO, HDMI2, BACKLIGHT_OPEN, BACKLIGHT_CLOSE)

    fun title(id: String): String = when (id) {
        STREMIO -> "Stremio"
        HDMI2 -> "HDMI 2 input"
        BACKLIGHT_OPEN -> "Backlight: open"
        BACKLIGHT_CLOSE -> "Backlight: close"
        else -> id
    }

    /** Best guesses from the TV's quick-settings panel; tune with Test / Record. */
    fun default(id: String): String = when (id) {
        HDMI2 -> "SETTINGS WAIT 800 RIGHT OK WAIT 800 DOWN DOWN OK"
        BACKLIGHT_OPEN -> "SETTINGS WAIT 800 DOWN OK WAIT 800 DOWN OK"
        BACKLIGHT_CLOSE -> "BACK"
        else -> ""
    }
}

object MacroKeys {
    val names: Map<String, Int> = mapOf(
        "UP" to KeyEvent.KEYCODE_DPAD_UP,
        "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "OK" to KeyEvent.KEYCODE_DPAD_CENTER,
        "BACK" to KeyEvent.KEYCODE_BACK,
        "HOME" to KeyEvent.KEYCODE_HOME,
        "SETTINGS" to KeyEvent.KEYCODE_SETTINGS,
        "MENU" to KeyEvent.KEYCODE_MENU,
        "SEARCH" to KeyEvent.KEYCODE_SEARCH,
        "POWER" to KeyEvent.KEYCODE_POWER,
        "ASSIST" to KeyEvent.KEYCODE_ASSIST,
        "GUIDE" to KeyEvent.KEYCODE_GUIDE,
        "TV_INPUT" to KeyEvent.KEYCODE_TV_INPUT,
        "ALL_APPS" to KeyEvent.KEYCODE_ALL_APPS,
        "VOL_UP" to KeyEvent.KEYCODE_VOLUME_UP,
        "VOL_DOWN" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "MUTE" to KeyEvent.KEYCODE_VOLUME_MUTE,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "DEL" to KeyEvent.KEYCODE_DEL,
        "SPACE" to KeyEvent.KEYCODE_SPACE,
    )
    private val byCode: Map<Int, String> = names.entries.associate { it.value to it.key }

    fun nameOf(code: Int): String? = byCode[code] ?: when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (code - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (code - KeyEvent.KEYCODE_0)).toString()
        else -> null
    }

    fun codeOf(name: String): Int? {
        names[name]?.let { return it }
        if (name.length == 1) {
            val c = name[0]
            if (c in 'A'..'Z') return KeyEvent.KEYCODE_A + (c - 'A')
            if (c in '0'..'9') return KeyEvent.KEYCODE_0 + (c - '0')
        }
        return null
    }
}

sealed interface MacroStep {
    data class Press(val code: Int) : MacroStep
    data class Wait(val ms: Long) : MacroStep
}

/** A macro syntax/validation error with a user-readable message. */
class MacroParseException(message: String) : IllegalArgumentException(message)

/**
 * Parses text such as "SETTINGS WAIT 800 DOWN*2 OK".
 * Unknown commands and malformed repetitions are rejected instead of silently ignored.
 */
fun parseMacro(text: String): List<MacroStep> {
    val tokens = text.uppercase().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
    if (tokens.size > 500) throw MacroParseException("Macro contains too many tokens (maximum 500)")
    val out = mutableListOf<MacroStep>()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        if (token == "WAIT") {
            val raw = tokens.getOrNull(i + 1)
                ?: throw MacroParseException("WAIT requires a duration in milliseconds")
            val ms = raw.toLongOrNull()
                ?: throw MacroParseException("Invalid WAIT duration: $raw")
            if (ms !in 0L..10_000L) throw MacroParseException("WAIT duration must be 0–10000 ms")
            out += MacroStep.Wait(ms)
            i += 2
            continue
        }

        if (token.startsWith("WAIT") && token.drop(4).isNotEmpty()) {
            val ms = token.drop(4).toLongOrNull()
                ?: throw MacroParseException("Invalid command: $token")
            if (ms !in 0L..10_000L) throw MacroParseException("WAIT duration must be 0–10000 ms")
            out += MacroStep.Wait(ms)
            i++
            continue
        }

        val name = token.substringBefore('*')
        val countText = token.substringAfter('*', "1")
        val count = countText.toIntOrNull()
            ?: throw MacroParseException("Invalid repeat count in: $token")
        if (count !in 1..100) throw MacroParseException("Repeat count must be 1–100: $token")
        val code = MacroKeys.codeOf(name)
            ?: throw MacroParseException("Unknown macro command: $name")
        repeat(count) { out += MacroStep.Press(code) }
        if (out.size > 200) throw MacroParseException("Macro contains too many steps (maximum 200)")
        i++
    }
    if (out.isEmpty()) throw MacroParseException("Macro contains no executable steps")
    return out
}

@Singleton
class MacroRunner @Inject constructor(private val repo: TvRepository) {
    private val lock = Mutex()

    /** One macro at a time; [stepDelayMs] is the pause after every key press. */
    suspend fun run(text: String, stepDelayMs: Long = 200L) {
        val steps = parseMacro(text)
        lock.withLock {
            for (s in steps) {
                when (s) {
                    is MacroStep.Press -> {
                        repo.sendKey(s.code)
                        delay(stepDelayMs)
                    }
                    is MacroStep.Wait -> delay(s.ms)
                }
            }
        }
    }
}

@Singleton
class MacroStore @Inject constructor(@ApplicationContext private val ctx: Context) {
    private fun key(id: String) = stringPreferencesKey("macro_$id")
    private val unitsKey = intPreferencesKey("backlight_units_per_press")

    suspend fun get(id: String): String = ctx.macroStore.data.first()[key(id)] ?: MacroIds.default(id)
    fun observe(id: String): Flow<String> = ctx.macroStore.data.map { it[key(id)] ?: MacroIds.default(id) }
    suspend fun set(id: String, text: String) { ctx.macroStore.edit { it[key(id)] = text.trim() } }
    suspend fun reset(id: String) { ctx.macroStore.edit { it.remove(key(id)) } }

    val unitsFlow: Flow<Int> = ctx.macroStore.data.map { it[unitsKey] ?: 1 }
    suspend fun setUnits(v: Int) { ctx.macroStore.edit { it[unitsKey] = v.coerceIn(1, 20) } }
}

/** Captures the keys you press on the Remote tab while a recording is active. */
@Singleton
class MacroRecorder @Inject constructor() {
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()
    private val buffer = mutableListOf<String>()
    private var last = 0L

    @Synchronized
    fun start(id: String) {
        buffer.clear()
        last = 0L
        _target.value = id
    }

    @Synchronized
    fun onKey(code: Int) {
        if (_target.value == null) return
        val name = MacroKeys.nameOf(code) ?: return
        val now = SystemClock.elapsedRealtime()
        if (last != 0L && now - last > 1_200L) {
            buffer.add("WAIT " + ((now - last).coerceAtMost(3_000L) / 100L * 100L))
        }
        buffer.add(name)
        last = now
    }

    /** Returns (macro id, recorded text) or null if nothing was recording. */
    @Synchronized
    fun stop(): Pair<String, String>? {
        val id = _target.value ?: return null
        _target.value = null
        return id to buffer.joinToString(" ")
    }
}
