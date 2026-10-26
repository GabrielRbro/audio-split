package io.github.audiosplit.client

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.audiosplit.BuildConfig
import io.github.audiosplit.update.InstallResult
import io.github.audiosplit.update.UpdateCheckResult
import io.github.audiosplit.update.UpdateException
import io.github.audiosplit.update.UpdateManifest
import io.github.audiosplit.update.UpdateRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, INSTALLING }

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val available: UpdateManifest? = null,
    val downloaded: Boolean = false,
    val percent: Int? = null,
    val message: String = "Consulte as versões publicadas no GitHub e atualize por aqui.",
    val error: Boolean = false,
) {
    val busy: Boolean get() = phase != UpdatePhase.IDLE
    val cancellable: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)
}

/** Keeps a download alive across rotations; leaving the activity permanently cancels it. */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UpdateRepository(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()
    private var operation: Job? = null
    private var downloadedFile: File? = null

    fun check() {
        if (operation?.isActive == true) return
        operation = scope.launch {
            _state.update { it.copy(phase = UpdatePhase.CHECKING, error = false, message = "Consultando o GitHub…") }
            try {
                val result = repository.check()
                downloadedFile = null
                _state.value = when (result) {
                    is UpdateCheckResult.Available -> UpdateUiState(
                        available = result.update,
                        message = "A versão ${result.update.versionName} está disponível.",
                    )
                    is UpdateCheckResult.UpToDate -> UpdateUiState(message = "A versão ${BuildConfig.VERSION_NAME} instalada está atualizada.")
                    UpdateCheckResult.NoRelease -> UpdateUiState(message = "Ainda não há uma atualização publicada no GitHub.")
                }
            } catch (error: CancellationException) {
                _state.update { it.copy(message = "Consulta cancelada.") }
                throw error
            } catch (error: Exception) {
                showError(error)
            } finally {
                _state.update { it.copy(phase = UpdatePhase.IDLE) }
            }
        }
    }

    fun downloadAndInstall(audio: AudioSplitViewModel) {
        if (operation?.isActive == true) return
        val update = _state.value.available ?: return
        operation = scope.launch {
            try {
                var file = downloadedFile?.takeIf { it.isFile }
                if (file == null) {
                    _state.update { it.copy(phase = UpdatePhase.DOWNLOADING, percent = null, error = false,
                        downloaded = false, message = "Baixando e verificando a atualização…") }
                    file = repository.download(update) { progress ->
                        _state.update { it.copy(percent = progress.percent) }
                    }
                    downloadedFile = file
                }
                _state.update { it.copy(phase = UpdatePhase.INSTALLING, downloaded = true,
                    error = false, message = "Restaurando o áudio antes de abrir o instalador…") }
                var result: InstallResult? = null
                val ready = audio.withRestoredAudioForUpdate {
                    result = withContext(Dispatchers.IO) {
                        repository.installOrOpenSettings(requireNotNull(file))
                    }
                }
                _state.update { it.copy(
                    error = !ready,
                    message = when {
                        !ready -> "Restaure o áudio ou aguarde a operação atual e toque em Instalar atualização novamente."
                        result == InstallResult.PermissionRequired -> "Autorize o Audio Split a instalar apps na tela do Android. Depois volte e toque em Instalar atualização."
                        else -> "O instalador do Android foi aberto. Confirme a instalação para concluir a atualização."
                    },
                ) }
            } catch (error: CancellationException) {
                _state.update { it.copy(message = "Download cancelado.", percent = null) }
                throw error
            } catch (error: Exception) {
                if (error is UpdateException && error.code == "MISSING_DOWNLOAD") {
                    downloadedFile = null
                    _state.update { it.copy(downloaded = false) }
                }
                showError(error)
            } finally {
                _state.update { it.copy(phase = UpdatePhase.IDLE) }
            }
        }
    }

    fun cancel() {
        if (_state.value.cancellable) operation?.cancel()
    }

    private fun showError(error: Exception) {
        val message = if (error is UpdateException) error.message
            else "Não foi possível atualizar agora. Confira a conexão e tente novamente."
        _state.update { it.copy(error = true, message = message ?: "Não foi possível atualizar agora.") }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
