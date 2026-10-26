package io.github.audiosplit.engine

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import io.github.audiosplit.core.PlaybackRouteObservation
import io.github.audiosplit.core.RouteMismatchMonitor
import io.github.audiosplit.core.SessionController
import io.github.audiosplit.core.SessionLease
import io.github.audiosplit.core.SessionLifecycle
import io.github.audiosplit.core.SessionStopCause
import io.github.audiosplit.core.SessionStep
import io.github.audiosplit.ipc.IAudioSplitService
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.exitProcess

/** Runs in Shizuku's shell UserService process, never in the application's normal UID. */
class AudioSplitUserService(context: Context) : IAudioSplitService.Stub() {
    private val lock = Any()
    private val audioContext = ShellAudioContext.create(context)
    private val shellContext = audioContext.context
    private val audio = requireNotNull(shellContext.getSystemService(AudioManager::class.java)) {
        "The shell context did not provide AudioManager."
    }
    private val session = SessionController()
    private val lifecycle = SessionLifecycle(session)
    private val thread = HandlerThread("audio-split-lifecycle").apply { start() }
    private val handler = Handler(thread.looper)
    private var client: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var privilegedServer: IBinder? = null
    private var serverDeathRecipient: IBinder.DeathRecipient? = null
    private var backend: Android16AudioPolicy? = null
    private var selectedBluetooth: String? = null
    private var sessionTestMode = false
    private var closing = false
    private var destroyed = false
    private var phase = "idle"
    private var code = "READY"
    private var message = "Backend conectado. Execute o diagnóstico antes de aplicar."
    private var lastError: String? = null
    private var cleanupErrors: List<String> = emptyList()
    private var operationStage = "idle"
    private var preflightPassed = false
    private var routeMonitor: RouteMismatchMonitor? = null
    private var sessionStartedElapsedMs = 0L
    private var lastRequestedRoutes: Map<Int, String> = emptyMap()
    private val playbackHistory = ArrayDeque<JSONObject>()
    private var routeFailure: JSONObject? = null

    private val poll = object : Runnable {
        override fun run() {
            synchronized(lock) {
                handler.removeCallbacks(this)
                if (destroyed) return
                withShellIdentity {
                    try {
                        if (session.snapshot().active) {
                            val alive = client?.isBinderAlive == true
                            val outputAvailable = closing || !alive || devices().any { key(it) == selectedBluetooth }
                            val stop = lifecycle.inspect(alive, outputAvailable, closing,
                                privilegedSessionAlive = privilegedServer?.isBinderAlive == true)
                            if (stop != null) {
                                val reason = when (stop.cause) {
                                    SessionStopCause.CLIENT_DISCONNECTED -> "O processo do aplicativo foi desconectado."
                                    SessionStopCause.PRIVILEGED_SESSION_DISCONNECTED -> "O Shizuku foi encerrado."
                                    SessionStopCause.OUTPUT_DISCONNECTED -> "O dispositivo Bluetooth foi desconectado ou alterado."
                                    SessionStopCause.CLEANUP_RETRY -> "A sessão foi encerrada."
                                }
                                if (stop.cause == SessionStopCause.CLEANUP_RETRY && routeFailure != null) {
                                    reportRestoration(stop.restoration, "blocked", "ROUTE_MISMATCH", ROUTE_MISMATCH_MESSAGE)
                                } else {
                                    reportRestoration(stop.restoration, "disconnected", "DISCONNECTED",
                                        "$reason As rotas temporárias foram removidas.")
                                }
                            } else if (!closing && session.snapshot().active) {
                                inspectPlaybackRoutes()
                            }
                        }
                    } catch (error: Exception) {
                        lastError = describe(error)
                        closeSession("O monitoramento de áudio perdeu acesso ao sistema.")
                    } catch (error: LinkageError) {
                        lastError = describe(error)
                        closeSession("O monitoramento de áudio encontrou uma API incompatível.")
                    }
                }
                if (!destroyed) handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handler.removeCallbacks(poll)
            handler.post(poll)
        }

        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
    }

    init {
        // The polling fallback also works when an OEM does not deliver these callbacks reliably.
        runCatching { audio.registerAudioDeviceCallback(deviceCallback, handler) }
        handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    override fun diagnose(): String = execute("diagnostic") {
        operationStage = "preflight"
        val issue = preflight(requireBluetooth = true)
        if (issue != null) {
            setState("diagnostic", issue.first, issue.second)
        } else if (!session.snapshot().active) {
            setState("diagnostic", "READY", "Permissões e APIs disponíveis. O roteamento e a reprodução simultânea ainda precisam de teste real.")
        }
        envelope(includePlayback = true)
    }

    override fun applyRoutes(testMode: Boolean, bluetoothDeviceKey: String?): String = execute("apply") {
        operationStage = "preflight"
        preflight(requireBluetooth = true)?.let {
            setState("blocked", it.first, it.second)
            return@execute envelope()
        }
        if (client?.isBinderAlive != true || privilegedServer?.isBinderAlive != true) {
            setState("blocked", "DISCONNECTED", "Reconecte o aplicativo ao backend antes de aplicar.")
            return@execute envelope()
        }
        operationStage = "resolve_outputs"
        val outputs = devices()
        val bluetooth = outputs.singleOrNull { isBluetooth(it) && key(it) == bluetoothDeviceKey }
        if (bluetooth == null) {
            setState("blocked", "NO_BLUETOOTH", "Selecione um dispositivo Bluetooth disponível no diagnóstico atual.")
            return@execute envelope()
        }
        val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker == null) {
            setState("blocked", "UNSUPPORTED", "O sistema não expôs uma saída de alto-falante integrada.")
            return@execute envelope()
        }
        operationStage = "resolve_packages"
        val targets = if (testMode) TEST_PACKAGES else MEDIA_PACKAGES
        val firstUid = packageUid(targets[0])
        val secondUid = packageUid(targets[1])
        if (firstUid == null || secondUid == null || firstUid == secondUid) {
            setState("blocked", "MISSING_APP", "Instale os dois aplicativos do modo escolhido; eles precisam ter UIDs distintos.")
            return@execute envelope()
        }
        if (session.snapshot().active && !restoreInternal("restored", "RESTORED", "Sessão anterior removida.")) {
            return@execute envelope()
        }
        operationStage = "prepare_mixes"
        val policy = policyBackend().prepare(firstUid, bluetooth, secondUid, speaker)
        selectedBluetooth = key(bluetooth)
        sessionTestMode = testMode
        closing = false
        lastError = null
        cleanupErrors = emptyList()
        lastRequestedRoutes = mapOf(firstUid to key(bluetooth), secondUid to key(speaker))
        playbackHistory.clear()
        routeFailure = null
        val result = session.begin(listOf(
            step("audio_policy_cleanup") { SessionLease { policy.unregister() } },
            step("bluetooth_uid_mix") {
                operationStage = "register_bluetooth_mix"
                policy.registerFirst()
                SessionLease {}
            },
            step("phone_uid_mix") {
                operationStage = "attach_phone_mix"
                policy.attachSecond()
                SessionLease {}
            },
        ))
        if (result.applied) {
            routeMonitor = RouteMismatchMonitor(lastRequestedRoutes)
            sessionStartedElapsedMs = SystemClock.elapsedRealtime()
            operationStage = "policy_registered"
            setState("applied_unverified", "APPLIED_UNVERIFIED", "Política registrada. Confirme as saídas e que os dois aplicativos continuam tocando ao mesmo tempo.")
        } else {
            lastError = describe(result.error)
            cleanupErrors = result.cleanupFailures.map { "${it.name}: ${describe(it.error)}" }
            closing = result.snapshot.active
            if (!result.snapshot.active) selectedBluetooth = null
            val failureCode = when (result.error) {
                is SecurityException -> "PERMISSION_DENIED"
                is ReflectiveOperationException, is LinkageError, is UnsupportedOperationException -> "UNSUPPORTED"
                else -> "ERROR"
            }
            setState(if (result.snapshot.active) "cleanup_failed" else "blocked",
                failureCode,
                if (result.snapshot.active) "Falha ao aplicar e ao desfazer. O backend continuará tentando remover a política."
                else "O sistema recusou o roteamento. A política temporária foi removida.")
        }
        envelope(includePlayback = true)
    }

    override fun getStatus(): String = execute("status") { envelope(includePlayback = true) }

    override fun restore(): String = execute("restore") {
        operationStage = "restore_policy"
        restoreInternal("restored", "RESTORED", "Política temporária removida. O sistema retomou o roteamento padrão.")
        envelope(includePlayback = true)
    }

    override fun attachClient(token: IBinder?, shizukuToken: IBinder?) {
        authorize()
        requireNotNull(token) { "A client lifecycle token is required." }
        requireNotNull(shizukuToken) { "The privileged server lifecycle token is required." }
        synchronized(lock) {
            check(!destroyed) { "The backend was destroyed." }
            withShellIdentity {
                if (client === token && privilegedServer === shizukuToken) return@withShellIdentity
                if (client != null && session.snapshot().active) closeSession("O cliente da sessão foi substituído.")
                unlinkClient()
                val recipient = IBinder.DeathRecipient {
                    handler.post {
                        synchronized(lock) {
                            if (client === token) {
                                withShellIdentity { closeSession("O processo do aplicativo foi encerrado.") }
                                unlinkClient()
                            }
                        }
                    }
                }
                token.linkToDeath(recipient, 0)
                val serverRecipient = IBinder.DeathRecipient {
                    handler.post {
                        synchronized(lock) {
                            if (privilegedServer === shizukuToken) {
                                withShellIdentity { closeSession("O Shizuku foi encerrado.") }
                                unlinkClient()
                            }
                        }
                    }
                }
                try {
                    shizukuToken.linkToDeath(serverRecipient, 0)
                } catch (error: Exception) {
                    runCatching { token.unlinkToDeath(recipient, 0) }
                    throw error
                }
                client = token
                deathRecipient = recipient
                privilegedServer = shizukuToken
                serverDeathRecipient = serverRecipient
            }
        }
    }

    override fun destroy() {
        authorize(allowShell = true)
        synchronized(lock) {
            withShellIdentity {
                destroyed = true
                closing = true
                handler.removeCallbacks(poll)
                runCatching { audio.unregisterAudioDeviceCallback(deviceCallback) }
                restoreInternal("destroyed", "DISCONNECTED", "Backend encerrado.")
                unlinkClient()
                // Binder death also causes AudioService to release policies owned by this process.
                handler.postDelayed({ thread.quitSafely(); exitProcess(0) }, 150L)
            }
        }
    }

    private fun execute(operation: String, body: () -> String): String {
        try {
            authorize()
        } catch (_: SecurityException) {
            return minimalError("PERMISSION_DENIED", "O chamador não é o aplicativo autorizado.")
        }
        return synchronized(lock) {
            withShellIdentity {
                if (destroyed) return@withShellIdentity minimalError("DISCONNECTED", "O backend foi encerrado.")
                try {
                    body()
                } catch (error: Exception) {
                    reportOperationFailure(operation, error)
                    envelopeSafely()
                } catch (error: LinkageError) {
                    reportOperationFailure(operation, error)
                    envelopeSafely()
                }
            }
        }
    }

    /** If applying failed after registration (including while building its reply), undo it. */
    private fun reportOperationFailure(operation: String, error: Throwable) {
        lastError = describe(error)
        val errorCode = when (error) {
            is SecurityException -> "PERMISSION_DENIED"
            is ReflectiveOperationException, is UnsupportedOperationException, is LinkageError -> "UNSUPPORTED"
            else -> "ERROR"
        }
        if (operation == "apply" && session.snapshot().active) {
            val cleanup = session.restore()
            cleanupErrors = cleanup.failures.map { "${it.name}: ${describe(it.error)}" }
            closing = !cleanup.restored
            if (cleanup.restored) {
                selectedBluetooth = null
                routeMonitor = null
            }
            setState(if (cleanup.restored) "blocked" else "cleanup_failed", errorCode,
                if (cleanup.restored) "A aplicação falhou. As rotas temporárias foram removidas; consulte o diagnóstico."
                else "A aplicação falhou e a limpeza ainda está pendente. O backend continuará tentando remover a política.")
        } else {
            setState(operation, errorCode, "A operação não pôde ser concluída. Consulte os detalhes do diagnóstico.")
        }
    }

    private fun authorize(allowShell: Boolean = false) {
        val caller = Binder.getCallingUid()
        if (allowShell && caller == SHELL_UID) return
        val expected = packageUid(APP_PACKAGE)
        if (expected == null || caller != expected) throw SecurityException("Unauthorized caller UID")
    }

    private inline fun <T> withShellIdentity(body: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        return try { body() } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun preflight(requireBluetooth: Boolean): Pair<String, String>? {
        preflightPassed = false
        if (Process.myUid() != SHELL_UID) {
            return "PERMISSION_DENIED" to "O motor iniciou com UID ${Process.myUid()}; esperado shell (2000). Reinicie o Shizuku pela depuração sem fio."
        }
        if (!audioContext.ready) {
            lastError = audioContext.error
            return "CONTEXT_UNAVAILABLE" to "Não foi possível preparar o contexto de áudio do Android. Exporte o diagnóstico para identificar a incompatibilidade."
        }
        if (!hasRoutingPermission()) {
            return "PERMISSION_DENIED" to "O sistema não concedeu MODIFY_AUDIO_ROUTING ao processo shell."
        }
        if (Build.VERSION.SDK_INT != 36) return "UNSUPPORTED" to "Esta prova de conceito suporta somente Android 16 (API 36)."
        try {
            policyBackend()
        } catch (error: Exception) {
            lastError = describe(error)
            return "UNSUPPORTED" to "As APIs internas de roteamento não estão acessíveis nesta versão do sistema."
        } catch (error: LinkageError) {
            lastError = describe(error)
            return "UNSUPPORTED" to "As APIs internas de roteamento são incompatíveis com esta versão."
        }
        if (requireBluetooth && devices().none(::isBluetooth)) {
            return "NO_BLUETOOTH" to "Conecte e ative uma caixa ou fone Bluetooth para áudio de mídia."
        }
        preflightPassed = true
        return null
    }

    private fun hasRoutingPermission(): Boolean = shellContext.checkPermission(
        ROUTING_PERMISSION, Process.myPid(), Process.myUid(),
    ) == PackageManager.PERMISSION_GRANTED

    private fun policyBackend(): Android16AudioPolicy = backend
        ?: Android16AudioPolicy(shellContext, audio).also { backend = it }

    private fun closeSession(reason: String) {
        closing = true
        restoreInternal("disconnected", "DISCONNECTED", "$reason As rotas temporárias foram removidas.")
    }

    private fun restoreInternal(successPhase: String, successCode: String, successMessage: String): Boolean {
        val result = session.restore()
        return reportRestoration(result, successPhase, successCode, successMessage)
    }

    private fun reportRestoration(result: io.github.audiosplit.core.RestoreResult,
        successPhase: String, successCode: String, successMessage: String): Boolean {
        cleanupErrors = result.failures.map { "${it.name}: ${describe(it.error)}" }
        if (result.restored) {
            selectedBluetooth = null
            routeMonitor = null
            closing = false
            setState(successPhase, successCode, successMessage)
        } else {
            closing = true
            setState("cleanup_failed", "ERROR", "Ainda não foi possível remover toda a política. O backend tentará novamente.")
        }
        return result.restored
    }

    private fun devices(): List<AudioDeviceInfo> = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || isBluetooth(it) }

    private fun isBluetooth(device: AudioDeviceInfo): Boolean = device.type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )

    private fun key(device: AudioDeviceInfo): String = "${device.type}:${device.address}:${device.id}"

    private fun packageUid(packageName: String): Int? = try {
        shellContext.packageManager.getApplicationInfo(packageName, 0).uid
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun playbackDiagnostics(): JSONArray {
        val output = JSONArray()
        try {
            val targets = (MEDIA_PACKAGES + TEST_PACKAGES).mapNotNull(::packageUid).toSet()
            for (configuration in audio.activePlaybackConfigurations) {
                val uid = runCatching { configuration.javaClass.getMethod("getClientUid").invoke(configuration) as Int }.getOrNull()
                if (uid == null || uid !in targets) continue
                val item = JSONObject().put("uid", uid).put("usage", configuration.audioAttributes.usage)
                // API 36 deprecated the old singular accessor: it was never reliably populated.
                // The system-only plural API can also be absent behind an OEM feature flag.
                val routed = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    configuration.javaClass.getMethod("getAudioDeviceInfos").invoke(configuration) as List<AudioDeviceInfo>
                }
                val observed = routed.getOrNull().orEmpty()
                item.put("observedDeviceKeys", JSONArray(observed.map(::key)))
                    .put("observedDeviceKey", observed.singleOrNull()?.let(::key) ?: JSONObject.NULL)
                    .put("routeObservationAvailable", routed.isSuccess && observed.isNotEmpty())
                routed.exceptionOrNull()?.let { item.put("routeObservationError", describe(it)) }
                runCatching {
                    configuration.javaClass.getMethod("isActive").invoke(configuration) as Boolean
                }.getOrNull()?.let { item.put("active", it) }
                runCatching {
                    configuration.javaClass.getMethod("isMuted").invoke(configuration) as Boolean
                }.getOrNull()?.let { item.put("muted", it) }
                runCatching {
                    configuration.javaClass.getMethod("getPlayerState").invoke(configuration) as Int
                }.getOrNull()?.let { item.put("playerState", it) }
                runCatching {
                    configuration.javaClass.getMethod("getMutedBy").invoke(configuration) as Int
                }.getOrNull()?.let { item.put("mutedBy", it) }
                for ((method, field) in listOf("getSessionId" to "sessionId", "getPlayerInterfaceId" to "playerId")) {
                    runCatching { configuration.javaClass.getMethod(method).invoke(configuration) as Int }
                        .getOrNull()?.let { item.put(field, it) }
                }
                output.put(item)
            }
        } catch (error: Exception) {
            output.put(JSONObject().put("diagnosticError", describe(error)))
        }
        return output
    }

    /** Observe only the selected apps; never infer a route from the requested mix. */
    private fun inspectPlaybackRoutes() {
        val monitor = routeMonitor ?: return
        val observed = playbackDiagnostics()
        val targetEntries = JSONArray()
        val samples = mutableListOf<PlaybackRouteObservation>()
        var observationFailed = false
        for (index in 0 until observed.length()) {
            val entry = observed.getJSONObject(index)
            if (entry.has("diagnosticError")) observationFailed = true
            val uid = entry.optInt("uid", -1)
            if (uid !in monitor.expectedRoutes) continue
            targetEntries.put(entry)
            // The installed policy matches MEDIA only. Notifications and calls are outside its scope.
            if (entry.optInt("usage", -1) != AudioAttributes.USAGE_MEDIA) continue
            val keys = entry.optJSONArray("observedDeviceKeys")
            val devices = if (entry.optBoolean("routeObservationAvailable", false) && keys != null) {
                (0 until keys.length()).map { keys.getString(it) }.toSet()
            } else null
            samples += PlaybackRouteObservation(uid,
                if (entry.has("active")) entry.getBoolean("active") else null, devices)
        }
        val snapshot = JSONObject().put("elapsedSinceApplyMs", SystemClock.elapsedRealtime() - sessionStartedElapsedMs)
            .put("generatedAtEpochMs", System.currentTimeMillis()).put("players", targetEntries)
        if (observationFailed) snapshot.put("observationFailed", true)
        playbackHistory.addLast(snapshot)
        while (playbackHistory.size > MAX_PLAYBACK_HISTORY) playbackHistory.removeFirst()
        // A failed read invalidates the sample even if it returned some earlier entries.
        val mismatch = monitor.sample(SystemClock.elapsedRealtime() - sessionStartedElapsedMs,
            if (observationFailed) emptyList() else samples)
        if (!mismatch) return
        routeFailure = JSONObject().put("code", "ROUTE_MISMATCH")
            .put("generatedAtEpochMs", System.currentTimeMillis()).put("players", targetEntries)
        lastError = "Actual playback devices differ from the requested per-app routes in three consecutive observations."
        operationStage = "restore_after_route_mismatch"
        closing = true
        restoreInternal("blocked", "ROUTE_MISMATCH", ROUTE_MISMATCH_MESSAGE)
    }

    private fun envelope(includePlayback: Boolean = false): String {
        val outputs = JSONArray()
        for (device in devices()) outputs.put(JSONObject()
            .put("key", key(device)).put("label", device.productName.toString())
            .put("type", device.type))
        val packages = JSONArray()
        for (name in MEDIA_PACKAGES + TEST_PACKAGES) {
            val uid = packageUid(name)
            val info = runCatching { shellContext.packageManager.getPackageInfo(name, 0) }.getOrNull()
            packages.put(JSONObject().put("packageName", name)
                .put("uid", uid ?: JSONObject.NULL).put("installed", uid != null)
                .put("versionName", info?.versionName ?: JSONObject.NULL)
                .put("versionCode", info?.longVersionCode ?: JSONObject.NULL))
        }
        val details = JSONObject()
            .put("generatedAtEpochMs", System.currentTimeMillis())
            .put("sdk", Build.VERSION.SDK_INT)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("buildIncremental", Build.VERSION.INCREMENTAL)
            .put("hyperOsVersion", systemProperty("ro.mi.os.version.name") ?: JSONObject.NULL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("buildDisplay", Build.DISPLAY)
            .put("effectiveUid", Process.myUid())
            .put("contextReady", audioContext.ready)
            .put("contextError", audioContext.error ?: JSONObject.NULL)
            .put("contextPackage", shellContext.packageName)
            .put("contextOpPackage", shellContext.opPackageName)
            .put("inheritedContextPackage", audioContext.inheritedPackage ?: JSONObject.NULL)
            .put("inheritedOpPackage", audioContext.inheritedOpPackage ?: JSONObject.NULL)
            .put("inheritedAttributionUid", audioContext.inheritedAttributionUid ?: JSONObject.NULL)
            .put("inheritedAttributionPackage", audioContext.inheritedAttributionPackage ?: JSONObject.NULL)
            .put("attributionUid", shellContext.attributionSource.uid)
            .put("attributionPackage", shellContext.attributionSource.packageName)
            .put("routingPermission", hasRoutingPermission())
            .put("preflightPassed", preflightPassed)
            .put("clientAttached", client?.isBinderAlive == true)
            .put("privilegedSessionAlive", privilegedServer?.isBinderAlive == true)
            .put("testMode", sessionTestMode)
            .put("selectedBluetoothKey", selectedBluetooth ?: JSONObject.NULL)
            .put("requestedRoutes", JSONArray(lastRequestedRoutes.map { (uid, device) ->
                JSONObject().put("uid", uid).put("deviceKey", device)
            }))
            .put("routeFailure", routeFailure ?: JSONObject.NULL)
            .put("playbackHistory", JSONArray(playbackHistory.toList()))
            .put("routeMismatchDetection", "both_apps_active_complete_devices_three_samples_after_grace")
            .put("policyMechanism", "uid_media_render")
            .put("outputIsolationVerified", false)
            .put("retainedResources", JSONArray(session.snapshot().retainedResources))
            .put("routeVerified", false)
            .put("simultaneousPlaybackVerified", false)
            .put("captureUsed", false)
            .put("focusPolicyModified", false)
            .put("operationStage", operationStage)
            .put("lastError", lastError ?: JSONObject.NULL)
            .put("cleanupErrors", JSONArray(cleanupErrors))
        if (includePlayback) details.put("playbackObservations", playbackDiagnostics())
        return JSONObject().put("schemaVersion", 1).put("phase", phase)
            .put("active", session.snapshot().active).put("code", code).put("message", message)
            .put("details", details).put("devices", outputs).put("packages", packages).toString()
    }

    private fun envelopeSafely(): String = try { envelope() } catch (_: Exception) {
        minimalError(code, message, session.snapshot().active)
    }

    /** Read one known, non-secret build property; never execute caller-supplied commands. */
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, name) as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun minimalError(errorCode: String, text: String, active: Boolean = false): String = JSONObject()
        .put("schemaVersion", 1).put("phase", "error").put("active", active)
        .put("code", errorCode).put("message", text).put("details", JSONObject())
        .put("devices", JSONArray()).put("packages", JSONArray()).toString()

    private fun unlinkClient() {
        val previous = client
        val recipient = deathRecipient
        val previousServer = privilegedServer
        val previousServerRecipient = serverDeathRecipient
        client = null
        deathRecipient = null
        privilegedServer = null
        serverDeathRecipient = null
        if (previous != null && recipient != null) runCatching { previous.unlinkToDeath(recipient, 0) }
        if (previousServer != null && previousServerRecipient != null) {
            runCatching { previousServer.unlinkToDeath(previousServerRecipient, 0) }
        }
    }

    private fun setState(nextPhase: String, nextCode: String, nextMessage: String) {
        phase = nextPhase
        code = nextCode
        message = nextMessage
    }

    private fun step(label: String, acquire: () -> SessionLease) = object : SessionStep {
        override val name = label
        override fun acquire() = acquire.invoke()
    }

    private fun describe(error: Throwable?): String = error?.let {
        "${it.javaClass.simpleName}: ${it.message.orEmpty().take(350)}"
    } ?: "Unknown failure"

    companion object {
        private const val APP_PACKAGE = "io.github.audiosplit"
        private const val SHELL_UID = 2000
        private const val ROUTING_PERMISSION = "android.permission.MODIFY_AUDIO_ROUTING"
        private const val POLL_INTERVAL_MS = 1_500L
        private const val MAX_PLAYBACK_HISTORY = 12
        private const val ROUTE_MISMATCH_MESSAGE = "O Android manteve áudio na saída errada enquanto os dois apps tocavam. A separação falhou e a política foi removida. Exporte o diagnóstico para investigar a rota compartilhada do sistema."
        private val MEDIA_PACKAGES = listOf("com.spotify.music", "com.instagram.android")
        private val TEST_PACKAGES = listOf("io.github.audiosplit.tone.bluetooth", "io.github.audiosplit.tone.phone")
    }
}
