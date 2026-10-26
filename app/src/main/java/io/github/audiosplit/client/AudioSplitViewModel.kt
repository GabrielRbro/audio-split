package io.github.audiosplit.client

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import io.github.audiosplit.BuildConfig
import io.github.audiosplit.ipc.IAudioSplitService
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

data class OutputDevice(val key: String, val label: String, val type: Int)
data class InstalledTarget(val packageName: String, val installed: Boolean)

data class BackendSnapshot(
    val raw: String,
    val phase: String,
    val code: String,
    val message: String,
    val active: Boolean,
    val preflightPassed: Boolean,
    val devices: List<OutputDevice>,
    val packages: List<InstalledTarget>,
) {
    val bluetoothDevices: List<OutputDevice> get() = devices.filter { it.type in setOf(8, 26, 27) }

    companion object {
        fun parse(raw: String): BackendSnapshot {
            val json = JSONObject(raw)
            require(json.getInt("schemaVersion") == 1) { "Versão de diagnóstico incompatível." }
            val devices = json.optJSONArray("devices") ?: JSONArray()
            val packages = json.optJSONArray("packages") ?: JSONArray()
            return BackendSnapshot(
                raw = raw,
                phase = json.getString("phase"),
                code = json.getString("code"),
                message = json.getString("message"),
                active = json.getBoolean("active"),
                preflightPassed = json.optJSONObject("details")?.optBoolean("preflightPassed", false) == true,
                devices = List(devices.length()) { index ->
                    val item = devices.getJSONObject(index)
                    OutputDevice(item.getString("key"), item.optString("label", "Bluetooth"), item.getInt("type"))
                },
                packages = List(packages.length()) { index ->
                    val item = packages.getJSONObject(index)
                    InstalledTarget(item.getString("packageName"), item.getBoolean("installed"))
                },
            )
        }
    }
}

data class ListeningObservations(
    val bluetoothExclusive: Boolean = false,
    val phoneExclusive: Boolean = false,
    val simultaneousFiveMinutes: Boolean = false,
    val note: String = "",
)

data class AudioSplitUiState(
    val shizukuMessage: String = "Inicie o Shizuku pela depuração sem fio para começar.",
    val shizukuAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val backendConnected: Boolean = false,
    val connecting: Boolean = false,
    val busy: Boolean = false,
    val testMode: Boolean = false,
    val selectedBluetoothKey: String? = null,
    val snapshot: BackendSnapshot? = null,
    val observations: ListeningObservations = ListeningObservations(),
    val operationError: String? = null,
    val exportMessage: String? = null,
) {
    val active: Boolean get() = snapshot?.active == true
    val controlsEnabled: Boolean get() = backendConnected && !busy && !connecting
    val canApply: Boolean get() {
        val current = snapshot ?: return false
        return controlsEnabled && !active && current.preflightPassed
            && current.code in setOf("READY", "RESTORED") && targetsInstalled
            && current.bluetoothDevices.any { it.key == selectedBluetoothKey }
    }
    val targetsInstalled: Boolean get() {
        val expected = if (testMode) TEST_PACKAGES else MEDIA_PACKAGES
        return expected.all { target -> snapshot?.packages?.any { it.packageName == target && it.installed } == true }
    }

    companion object {
        val TEST_PACKAGES = listOf("io.github.audiosplit.tone.bluetooth", "io.github.audiosplit.tone.phone")
        val MEDIA_PACKAGES = listOf("com.spotify.music", "com.instagram.android")
    }
}

/** Owns the client Binder for the life of the ViewModel, including rotations and background use. */
class AudioSplitViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ipc = Mutex()
    private val lifecycleToken = Binder()
    private var remote: IAudioSplitService? = null
    private var serviceDeath: IBinder.DeathRecipient? = null
    private var bindingTimeout: Job? = null
    private var refreshJob: Job? = null
    private var binding = false
    private var closed = false
    private val args = Shizuku.UserServiceArgs(
        ComponentName(application.packageName, "io.github.audiosplit.engine.AudioSplitUserService"),
    ).processNameSuffix("audio_split").daemon(false).version(BuildConfig.VERSION_CODE).tag("audio-split-android16")

    private val _state = MutableStateFlow(AudioSplitUiState(
        testMode = savedState["testMode"] ?: false,
        selectedBluetoothKey = savedState["bluetoothKey"],
    ))
    val state = _state.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { scope.launch { connect() } }
    private val binderDead = Shizuku.OnBinderDeadListener {
        scope.launch { endPrivilegedSession("O Shizuku foi desconectado. Confira o áudio padrão antes de reiniciar o teste.") }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request == PERMISSION_REQUEST) scope.launch {
            if (result == PackageManager.PERMISSION_GRANTED) connect()
            else _state.update { it.copy(permissionGranted = false, shizukuMessage = "Acesso ao Shizuku não autorizado. Você pode tentar novamente.") }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null || closed) return
            scope.launch {
                val candidate = IAudioSplitService.Stub.asInterface(service)
                detachRemote()
                remote = candidate
                val death = IBinder.DeathRecipient {
                    scope.launch {
                        if (remote?.asBinder() === service) disconnected("O motor de áudio foi encerrado. A restauração precisa ser conferida no aparelho.")
                    }
                }
                serviceDeath = death
                try {
                    ipc.withLock {
                        val report = withContext(Dispatchers.IO) {
                            service.linkToDeath(death, 0)
                            candidate.attachClient(lifecycleToken, requireNotNull(Shizuku.getBinder()) {
                                "A conexão com o Shizuku foi perdida."
                            })
                            candidate.diagnose()
                        }
                        if (remote?.asBinder() !== service) return@withLock
                        bindingTimeout?.cancel()
                        binding = false
                        _state.update { it.copy(backendConnected = true, connecting = false, shizukuMessage = "Shizuku conectado · sessão sem root", operationError = null) }
                        accept(report)
                    }
                } catch (error: Exception) {
                    disconnected("Não foi possível iniciar o motor: ${describe(error)}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            scope.launch { disconnected("O motor perdeu a conexão. Confira a restauração e conecte novamente.") }
        }

        override fun onBindingDied(name: ComponentName?) {
            scope.launch { disconnected("A conexão com o motor expirou. Conecte novamente para diagnosticar.") }
        }
    }

    init {
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        // Revocation can happen while another media app is in front. Keep this check independent
        // of the screen's foreground-only status polling, without starting a playback service.
        scope.launch {
            while (true) {
                delay(5_000)
                if (remote == null) continue
                val allowed = withContext(Dispatchers.IO) {
                    runCatching { Shizuku.pingBinder() && hasCurrentShizukuPermission() }.getOrDefault(false)
                }
                if (!allowed) endPrivilegedSession("O acesso ao Shizuku foi removido. Confira a restauração antes de autorizar novamente.")
            }
        }
    }

    fun connect() {
        if (closed || binding || remote?.asBinder()?.isBinderAlive == true) return
        scope.launch {
            try {
                val available = withContext(Dispatchers.IO) { Shizuku.pingBinder() }
                if (!available) {
                    _state.update { it.copy(shizukuAvailable = false, permissionGranted = false, shizukuMessage = "Inicie o Shizuku pela depuração sem fio e toque em Conectar.") }
                    return@launch
                }
                val allowed = withContext(Dispatchers.IO) { hasCurrentShizukuPermission() }
                _state.update { it.copy(shizukuAvailable = true, permissionGranted = allowed, operationError = null) }
                if (!allowed) {
                    _state.update { it.copy(shizukuMessage = "Shizuku disponível. Autorize o acesso deste aplicativo para continuar.") }
                    return@launch
                }
                val uid = withContext(Dispatchers.IO) { Shizuku.getUid() }
                if (closed) return@launch
                if (uid != SHELL_UID) {
                    _state.update { it.copy(shizukuMessage = "Este protótipo usa o Shizuku iniciado pela depuração sem fio (UID 2000). Reinicie-o nesse modo.") }
                    return@launch
                }
                // Check once more after suspension so repeated foreground events cannot double-bind.
                if (binding || remote?.asBinder()?.isBinderAlive == true) return@launch
                binding = true
                _state.update { it.copy(connecting = true, shizukuMessage = "Conectando ao motor de áudio…") }
                Shizuku.bindUserService(args, connection)
                bindingTimeout?.cancel()
                bindingTimeout = scope.launch {
                    delay(20_000)
                    if (binding && !_state.value.backendConnected) {
                        runCatching { Shizuku.unbindUserService(args, connection, true) }
                        disconnected("O motor não respondeu. Confira a versão do Shizuku, as opções de segurança da depuração do HyperOS e tente conectar novamente.")
                    }
                }
            } catch (error: Exception) {
                binding = false
                _state.update { it.copy(connecting = false, operationError = "Não foi possível conectar: ${describe(error)}") }
            }
        }
    }

    fun authorize() {
        scope.launch {
            try {
                if (withContext(Dispatchers.IO) { hasCurrentShizukuPermission() }) connect()
                else Shizuku.requestPermission(PERMISSION_REQUEST)
            } catch (error: Exception) {
                _state.update { it.copy(operationError = "Inicie o Shizuku antes de autorizar. ${describe(error)}") }
            }
        }
    }

    fun setTestMode(value: Boolean) {
        if (_state.value.active || _state.value.busy) return
        savedState["testMode"] = value
        _state.update { it.copy(testMode = value, observations = ListeningObservations(), exportMessage = null) }
    }

    fun selectBluetooth(key: String) {
        if (_state.value.active || _state.value.busy) return
        if (_state.value.snapshot?.bluetoothDevices?.none { it.key == key } != false) return
        savedState["bluetoothKey"] = key
        _state.update { it.copy(selectedBluetoothKey = key, observations = ListeningObservations()) }
    }

    fun diagnose() { operate { it.diagnose() } }

    fun apply() {
        val current = _state.value
        if (!current.canApply) return
        val key = current.selectedBluetoothKey ?: return
        _state.update { it.copy(observations = ListeningObservations(), exportMessage = null) }
        operate { it.applyRoutes(current.testMode, key) }
    }

    fun restore() { operate { it.restore() } }

    fun refresh() {
        if (!_state.value.busy && _state.value.backendConnected && refreshJob?.isActive != true) {
            refreshJob = operate(visibleBusy = false) { it.getStatus() }
        }
    }

    fun updateObservations(value: ListeningObservations) {
        _state.update { it.copy(observations = value) }
    }

    fun export(uri: Uri) {
        val current = _state.value
        val timestamp = Instant.now().toString()
        val report = JSONObject()
            .put("reportSchemaVersion", 1)
            .put("exportedAt", timestamp)
            .put("prototype", "Audio Split ${BuildConfig.VERSION_NAME}")
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT).put("build", Build.DISPLAY).put("securityPatch", Build.VERSION.SECURITY_PATCH))
            .put("clientConnectedAtExport", current.backendConnected)
            .put("testMode", current.testMode)
            .put("backendSnapshot", current.snapshot?.let { JSONObject(it.raw) } ?: JSONObject.NULL)
            .put("clientError", current.operationError ?: JSONObject.NULL)
            .put("userObservations", JSONObject()
                .put("source", "manual_user_report")
                .put("bluetoothExclusive", current.observations.bluetoothExclusive)
                .put("phoneExclusive", current.observations.phoneExclusive)
                .put("simultaneousForFiveMinutes", current.observations.simultaneousFiveMinutes)
                .put("notes", current.observations.note))
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) {
                        "Não foi possível abrir o arquivo escolhido."
                    }
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(report.toString(2)) }
                }
                _state.update { it.copy(exportMessage = "Diagnóstico salvo no arquivo escolhido.") }
            } catch (error: Exception) {
                _state.update { it.copy(exportMessage = "Não foi possível salvar: ${describe(error)}") }
            }
        }
    }

    private fun operate(visibleBusy: Boolean = true, action: (IAudioSplitService) -> String): Job? {
        if (closed || _state.value.busy) return null
        return scope.launch {
            ipc.withLock {
                val target = remote
                if (target == null || !target.asBinder().isBinderAlive) {
                    disconnected("Motor desconectado. Conecte novamente para continuar.")
                    return@withLock
                }
                if (visibleBusy) _state.update { it.copy(busy = true, operationError = null, exportMessage = null) }
                try {
                    val report = withContext(Dispatchers.IO) { action(target) }
                    if (remote === target) accept(report)
                } catch (error: Exception) {
                    if (!target.asBinder().isBinderAlive) disconnected("O motor foi desconectado. Confira a restauração no aparelho.")
                    else _state.update { it.copy(operationError = "Falha ao consultar o motor: ${describe(error)}") }
                } finally {
                    if (visibleBusy) _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun accept(report: String) {
        val snapshot = BackendSnapshot.parse(report)
        val current = _state.value
        val outputs = snapshot.bluetoothDevices
        val selected = current.selectedBluetoothKey.takeIf { key -> outputs.any { it.key == key } }
            ?: outputs.singleOrNull()?.key
        savedState["bluetoothKey"] = selected
        val justDisconnected = snapshot.code == "DISCONNECTED" && current.snapshot?.code != "DISCONNECTED"
        _state.update { it.copy(snapshot = snapshot, selectedBluetoothKey = selected,
            observations = if (justDisconnected) ListeningObservations() else it.observations) }
    }

    private fun disconnected(reason: String) {
        bindingTimeout?.cancel()
        detachRemote()
        binding = false
        _state.update { it.copy(backendConnected = false, connecting = false, busy = false,
            shizukuMessage = reason, operationError = null, observations = ListeningObservations()) }
    }

    private suspend fun endPrivilegedSession(reason: String) {
        val target = remote
        _state.update { it.copy(backendConnected = false, permissionGranted = false) }
        ipc.withLock {
            // A UserService may briefly outlive its Shizuku parent. Use the already-held Binder
            // to restore before discarding it; failure is kept explicit in the last snapshot.
            if (target != null) {
                val report = withContext(Dispatchers.IO) { runCatching { target.restore() }.getOrNull() }
                if (report != null && remote === target) runCatching { accept(report) }
            }
            disconnected(reason)
        }
    }

    private fun detachRemote() {
        val binder = remote?.asBinder()
        val recipient = serviceDeath
        remote = null
        serviceDeath = null
        if (binder != null && recipient != null) runCatching { binder.unlinkToDeath(recipient, 0) }
    }

    /** The convenience API caches grants; ask the server to detect revocation while it lives. */
    private fun hasCurrentShizukuPermission(): Boolean {
        val binder = Shizuku.getBinder() ?: return false
        return IShizukuService.Stub.asInterface(binder).checkSelfPermission()
    }

    override fun onCleared() {
        closed = true
        bindingTimeout?.cancel()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        // Finish/Back ends this screen's session; rotations do not clear a retained ViewModel.
        scope.launch {
            ipc.withLock {
                val target = remote
                withContext(Dispatchers.IO) { runCatching { target?.restore() } }
                runCatching { Shizuku.unbindUserService(args, connection, true) }
                detachRemote()
            }
            scope.cancel()
        }
        super.onCleared()
    }

    private fun describe(error: Exception): String = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(200)}"

    companion object {
        private const val PERMISSION_REQUEST = 73
        private const val SHELL_UID = 2000
    }
}
