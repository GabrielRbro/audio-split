package io.github.audiosplit.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.audiosplit.R
import io.github.audiosplit.BuildConfig
import io.github.audiosplit.client.AudioSplitUiState
import io.github.audiosplit.client.AudioSplitViewModel
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun AudioSplitScreen(state: AudioSplitUiState, model: AudioSplitViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.export(uri)
    }
    LaunchedEffect(lifecycle, model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.connect()
            while (true) {
                model.refresh()
                delay(2_000)
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Header()
            Text(
                "Uma saída para cada aplicativo.",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Teste a caixa Bluetooth e o alto-falante do celular ao mesmo tempo. Compatibilidade ainda em avaliação no POCO F7.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            ConnectionCard(state, model)
            ConfigurationCard(state, model)
            StatusCard(state)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = model::diagnose, enabled = state.controlsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Diagnosticar")
                }
                Button(
                    onClick = model::apply,
                    enabled = state.canApply,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Aplicar separação", modifier = Modifier.padding(vertical = 6.dp))
                }
                FilledTonalButton(onClick = model::restore, enabled = state.controlsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Restaurar áudio padrão")
                }
                OutlinedButton(
                    onClick = { export.launch("audio-split-diagnostico-${System.currentTimeMillis()}.json") },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Exportar diagnóstico")
                }
            }
            if (state.exportMessage != null) Text(state.exportMessage, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            ObservationsCard(state, model)
            TechnicalDetails(state)
            HelpCard()
            Text(
                "O botão Restaurar encerra o teste. Alternar para outro app mantém a sessão. Após reconectar a caixa ou reiniciar o Shizuku, execute Diagnosticar e aplique novamente.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_audio_split), contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Column {
            Text("Audio Split", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("PROTÓTIPO EXPERIMENTAL · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ConnectionCard(state: AudioSplitUiState, model: AudioSplitViewModel) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Conexão com Shizuku", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else StatusLabel(if (state.backendConnected) "Conectado" else "Pendente", state.backendConnected)
        }
        Text(state.shizukuMessage, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (!state.backendConnected && !state.connecting) {
            if (state.shizukuAvailable && !state.permissionGranted) {
                FilledTonalButton(onClick = model::authorize) { Text("Autorizar no Shizuku") }
            } else {
                OutlinedButton(onClick = model::connect) { Text("Conectar") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConfigurationCard(state: AudioSplitUiState, model: AudioSplitViewModel) {
    val editable = !state.active && !state.busy
    var expanded by remember { mutableStateOf(false) }
    val selected = state.snapshot?.bluetoothDevices?.firstOrNull { it.key == state.selectedBluetoothKey }
    SectionCard {
        Text("1. Escolha o teste", style = MaterialTheme.typography.titleMedium)
        ModeRow("Spotify + Instagram", "Spotify na caixa · Instagram no celular", !state.testMode, editable) { model.setTestMode(false) }
        ModeRow("Sons auxiliares (opcional)", "Requer os APKs Tom A e Tom B", state.testMode, editable) { model.setTestMode(true) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("2. Selecione a caixa Bluetooth", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = editable && state.snapshot?.bluetoothDevices?.isNotEmpty() == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selected?.label ?: "Conecte a caixa e execute Diagnosticar", modifier = Modifier.weight(1f))
                Text("⌄", style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.snapshot?.bluetoothDevices.orEmpty().forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.label) },
                        onClick = { model.selectBluetooth(device.key); expanded = false },
                    )
                }
            }
        }
        RouteRow(if (state.testMode) "Tom A · 440 Hz" else "Spotify", selected?.label ?: "Caixa Bluetooth", "BLUETOOTH")
        RouteRow(if (state.testMode) "Tom B · 880 Hz" else "Instagram", "Alto-falante do celular", "CELULAR")
        Text("Saídas desejadas. Confirme ouvindo cada fonte após aplicar.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.snapshot != null && !state.targetsInstalled) {
            Text(if (state.testMode) "Instale os dois APKs auxiliares para habilitar este teste."
                else "Instale Spotify e Instagram oficiais no perfil principal para habilitar este teste.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.active) Text("Restaure o áudio padrão antes de alterar as escolhas.",
            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ModeRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RouteRow(app: String, destination: String, category: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(app, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                Text(destination, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Text(category, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatusCard(state: AudioSplitUiState) {
    val snapshot = state.snapshot
    val applied = state.backendConnected && snapshot?.code == "APPLIED_UNVERIFIED"
    val color = if (applied) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    SectionCard(container = color) {
        Text(when {
            !state.backendConnected && state.active -> "Confira a restauração no aparelho"
            applied -> "Configuração aplicada · confirme ouvindo"
            snapshot?.code == "RESTORED" -> "Áudio padrão restaurado"
            snapshot?.code == "DISCONNECTED" -> "Sessão encerrada"
            snapshot?.code == "READY" -> "Pronto para experimentar"
            snapshot == null -> "Comece pelo diagnóstico"
            else -> "O teste precisa de atenção"
        }, style = MaterialTheme.typography.titleMedium)
        Text(
            if (!state.backendConnected && state.active) "A última informação indicava uma política ativa. A conexão foi perdida; confirme se o som voltou ao padrão antes de continuar."
            else snapshot?.message ?: "O diagnóstico verifica permissões, aplicativos e saídas disponíveis. A separação precisa ser comprovada no seu celular.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.operationError != null) Text(state.operationError, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun ObservationsCard(state: AudioSplitUiState, model: AudioSplitViewModel) {
    val observations = state.observations
    SectionCard {
        Text("O que você ouviu?", style = MaterialTheme.typography.titleMedium)
        Text("Marque somente o que confirmou no teste. Essas observações serão incluídas no relatório exportado.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ObservationRow("A fonte Bluetooth tocou somente na caixa", observations.bluetoothExclusive) {
            model.updateObservations(observations.copy(bluetoothExclusive = it))
        }
        ObservationRow("A fonte do celular tocou somente no alto-falante", observations.phoneExclusive) {
            model.updateObservations(observations.copy(phoneExclusive = it))
        }
        ObservationRow("As duas tocaram juntas por 5 minutos, sem pausa nem redução de volume", observations.simultaneousFiveMinutes) {
            model.updateObservations(observations.copy(simultaneousFiveMinutes = it))
        }
        OutlinedTextField(
            value = observations.note,
            onValueChange = { model.updateObservations(observations.copy(note = it.take(4_000))) },
            label = { Text("Observações (opcional)") },
            placeholder = { Text("Ex.: o Spotify pausou ao iniciar um vídeo.") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 6,
        )
    }
}

@Composable
private fun ObservationRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).weight(1f))
    }
}

@Composable
private fun TechnicalDetails(state: AudioSplitUiState) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        TextButton(onClick = { open = !open }) { Text(if (open) "Ocultar detalhes técnicos" else "Ver detalhes técnicos") }
        if (open) {
            Text("Registro de política e observações do sistema são evidências parciais. A conferência auditiva continua necessária.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val details = remember(state.snapshot?.raw) {
                state.snapshot?.let { runCatching { JSONObject(it.raw).toString(2) }.getOrDefault(it.raw) }
                    ?: "Nenhum diagnóstico disponível."
            }
            Text(details, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HelpCard() {
    val context = LocalContext.current
    var launchError by remember { mutableStateOf(false) }
    SectionCard {
        Text("Preparar o HyperOS", style = MaterialTheme.typography.titleMedium)
        Text("Em Som e vibração, procure Assistente de som ou Múltiplas fontes de áudio. Ative a reprodução simultânea se essa opção existir. O nome varia entre versões.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            launchError = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))) }.isFailure
        }) { Text("Abrir instruções oficiais do Shizuku") }
        if (launchError) Text("Acesse shizuku.rikka.app/guide/setup no navegador.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusLabel(label: String, connected: Boolean) {
    Surface(color = if (connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small) {
        Text(label, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(container: Color = MaterialTheme.colorScheme.surface, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
