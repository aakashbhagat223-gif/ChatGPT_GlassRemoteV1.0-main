package com.glassremote.ui.remote

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassremote.data.adb.AdbManager
import com.glassremote.data.adb.NativeActions
import com.glassremote.data.macro.MacroIds
import com.glassremote.data.macro.MacroRecorder
import com.glassremote.data.macro.MacroRunner
import com.glassremote.data.macro.MacroStore
import com.glassremote.data.network.ImeBridge
import com.glassremote.data.network.ImeInfo
import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.KnownTargets
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
enum class ControlTarget { XIAOMI_TV, JIO_BOX }

class RemoteViewModel @Inject constructor(
    private val repo: TvRepository,
    private val store: PairedDeviceStore,
    private val macros: MacroStore,
    private val runner: MacroRunner,
    private val recorder: MacroRecorder,
    private val adb: AdbManager,
    private val nativeActions: NativeActions,
    private val settings: com.glassremote.data.repository.SettingsRepository,
) : ViewModel() {

    private val _controlTarget = MutableStateFlow(ControlTarget.XIAOMI_TV)
    val controlTarget: StateFlow<ControlTarget> = _controlTarget.asStateFlow()
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    fun selectControlTarget(target: ControlTarget) {
        _controlTarget.value = target
        if (target == ControlTarget.JIO_BOX) {
            viewModelScope.launch {
                if (settings.autoHdmi2OnJioFocus.first() && adb.state.value.connected) {
                    runCatching { nativeActions.watchJio() }
                        .onFailure { _actionError.value = it.message ?: "HDMI switch failed" }
                }
            }
        }
    }

    fun sendTextDirect(text: String) {
        viewModelScope.launch {
            adb.injectText(text).onFailure { _actionError.value = it.message ?: "Text injection failed" }
        }
    }

    fun watchJio() {
        viewModelScope.launch {
            runCatching { nativeActions.watchJio() }
                .onFailure { _actionError.value = it.message ?: "Watch Jio failed" }
        }
    }

    val status: StateFlow<RemoteStatus> = repo.status

    val saved: StateFlow<Boolean?> = store.lastDevice
        .map<TvDevice?, Boolean?> { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val deviceName: StateFlow<String> = store.lastDevice
        .map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val ime: StateFlow<ImeInfo> = ImeBridge.state

    private val _activeTarget = MutableStateFlow(KnownTargets.Xiaomi)
    val activeTarget: StateFlow<TvDevice> = _activeTarget.asStateFlow()

    private val _pairingDialog = MutableStateFlow(false)
    val pairingDialog: StateFlow<Boolean> = _pairingDialog.asStateFlow()
    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError.asStateFlow()

    private val _replaceMode = MutableStateFlow(true)
    val replaceMode: StateFlow<Boolean> = _replaceMode.asStateFlow()

    private var typed = ""
    @Volatile private var targetInitialized = false

    // Typing over ADB goes through one ordered queue so letters, backspace and Go keep their order.
    private val adbOps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val adbQueued = AtomicInteger(0)

    init {
        reconnect()
        viewModelScope.launch { for (op in adbOps) runCatching { op() } }
        viewModelScope.launch {
            var retries = 0
            repo.status.collect { s ->
                when (s.phase) {
                    LinkPhase.Ready -> retries = 0
                    LinkPhase.Disconnected -> if (retries < 3) {
                        retries++
                        delay(1_500)
                        reconnect()
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            ImeBridge.state
                .map { it.active to it.label }
                .distinctUntilChanged()
                .collect { typed = ImeBridge.state.value.value }
        }
    }

    fun selectTarget(target: TvDevice) {
        _controlTarget.value = if (target.id == KnownTargets.Jio.id || target.host == KnownTargets.Jio.host) ControlTarget.JIO_BOX else ControlTarget.XIAOMI_TV
        if (_activeTarget.value.host == target.host) return
        viewModelScope.launch {
            _activeTarget.value = target
            targetInitialized = true
            // Selection is not the same as pairing: do not mark a new target as paired here.
            runCatching { repo.connect(target) }
        }
    }

    fun beginPairingActiveTarget() {
        viewModelScope.launch {
            _pairingError.value = null
            runCatching { repo.beginPairing(_activeTarget.value) }
                .onSuccess { _pairingDialog.value = true }
                .onFailure { _pairingError.value = it.message ?: "Could not start pairing" }
        }
    }

    fun submitPairingCode(code: String) {
        viewModelScope.launch {
            _pairingError.value = null
            runCatching {
                repo.submitPairingCode(_activeTarget.value, code)
                repo.connect(_activeTarget.value)
            }.onSuccess {
                _pairingDialog.value = false
            }.onFailure {
                _pairingError.value = it.message ?: "Pairing failed"
            }
        }
    }

    fun dismissPairingDialog() {
        _pairingDialog.value = false
        _pairingError.value = null
    }

    fun reconnect() {
        viewModelScope.launch {
            val savedDevice = store.lastDevice.first() ?: return@launch
            if (!targetInitialized) {
                _activeTarget.value = KnownTargets.all.firstOrNull { it.host == savedDevice.host } ?: savedDevice
                targetInitialized = true
            }
            runCatching { repo.connect(_activeTarget.value) }
        }
    }

    fun key(code: Int) {
        recorder.onKey(code)
        val volumeKey = code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
        viewModelScope.launch {
            if (_controlTarget.value == ControlTarget.JIO_BOX && !volumeKey && adb.state.value.connected) {
                adb.injectGlobalAdbKey(code).onFailure { _actionError.value = it.message ?: "ADB key failed" }
            } else {
                runCatching { repo.sendKey(code, KeyDirection.Short) }
                    .onFailure { _actionError.value = it.message ?: "Remote key failed" }
            }
        }
    }

    fun launchApp(target: String) {
        viewModelScope.launch { runCatching { repo.launchApp(target) } }
    }

    fun runMacro(id: String) {
        viewModelScope.launch {
            runCatching {
                if (id == MacroIds.HDMI2 &&
                    _activeTarget.value.host == KnownTargets.Xiaomi.host &&
                    adb.state.value.connected
                ) {
                    val input = nativeActions.savedHdmi2()
                    if (input.isNotBlank()) {
                        nativeActions.switchInput(input)
                        return@launch
                    }
                }
                runner.run(macros.get(id))
            }
        }
    }

    fun openStremio() {
        viewModelScope.launch {
            runCatching {
                if (adb.state.value.connected) {
                    nativeActions.launchPackage("com.stremio.one")
                    return@launch
                }
                val macro = macros.get(MacroIds.STREMIO).trim()
                when {
                    macro.isBlank() -> repo.launchApp("stremio://")
                    macro.contains("://") || macro.startsWith("intent:") -> repo.launchApp(macro)
                    else -> runner.run(macro)
                }
            }
        }
    }

    // ---- Keypad text input ----

    fun toggleReplaceMode() { _replaceMode.value = !_replaceMode.value }

    private fun viaAdb(block: suspend () -> Unit) {
        adbQueued.incrementAndGet()
        adbOps.trySend {
            try {
                block()
            } finally {
                adbQueued.decrementAndGet()
            }
        }
    }

    fun typeChar(ch: Char) {
        val c = ch.lowercaseChar()
        val info = ImeBridge.state.value
        val letter = c in 'a'..'z' || c == ' '
        when {
            info.active -> {
                if (_replaceMode.value) {
                    typed += c.toString()
                    sendText(typed)
                } else {
                    sendText(c.toString())
                }
            }
            // The TV's remote link drops letter keys; ADB's `input text` doesn't.
            letter && adb.state.value.connected -> viaAdb { nativeActions.typeText(c.toString()) }
            else -> keyForChar(c)?.let { key(it) }
        }
    }

    fun backspace() {
        when {
            ImeBridge.state.value.active && _replaceMode.value && typed.isNotEmpty() -> {
                typed = typed.dropLast(1)
                sendText(typed)
            }
            adbQueued.get() > 0 && adb.state.value.connected ->
                viaAdb { nativeActions.keyEvent(KeyEvent.KEYCODE_DEL) }
            else -> key(KeyEvent.KEYCODE_DEL)
        }
    }

    fun submitText() {
        typed = ""
        if (adbQueued.get() > 0 && adb.state.value.connected) {
            viaAdb { nativeActions.keyEvent(KeyEvent.KEYCODE_ENTER) }
        } else {
            key(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun keyForChar(c: Char): Int? = when (c) {
        ' ' -> KeyEvent.KEYCODE_SPACE
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
        else -> null
    }

    private fun sendText(text: String) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { ImeBridge.sendText(text) } }
    }

    fun forget() {
        viewModelScope.launch { repo.resetPairing() }
    }
}
