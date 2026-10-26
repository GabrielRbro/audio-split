package io.github.audiosplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.audiosplit.BuildConfig
import io.github.audiosplit.client.UpdatePhase
import io.github.audiosplit.client.UpdateUiState

@Composable
internal fun UpdateCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Atualizações", style = MaterialTheme.typography.titleMedium)
            Text("Versão instalada: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(state.message, style = MaterialTheme.typography.bodyMedium,
                color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (state.busy) {
                val percent = state.percent
                if (state.phase == UpdatePhase.DOWNLOADING && percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("$percent%", style = MaterialTheme.typography.labelMedium)
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.available != null) {
                Button(onClick = onInstall, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.downloaded) "Instalar atualização" else "Baixar e instalar ${state.available.versionName}")
                }
            }
            OutlinedButton(onClick = onCheck, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Verificar atualização")
            }
            if (state.cancellable) TextButton(onClick = onCancel) { Text("Cancelar") }
            Text("O APK vem dos releases do GitHub. O Android pede sua confirmação para instalar. A sessão de áudio será encerrada antes da instalação.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
