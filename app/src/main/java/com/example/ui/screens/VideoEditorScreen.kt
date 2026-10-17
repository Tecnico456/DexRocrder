package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.RecordedVideo
import com.example.ui.components.VideoPlayerDialog
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.RedPrimary
import com.example.viewmodel.MainViewModel
import java.util.Locale

@Composable
fun VideoEditorScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeVideos by viewModel.activeVideos.collectAsStateWithLifecycle()
    val selectedVideo by viewModel.selectedVideoForEdit.collectAsStateWithLifecycle()
    val statusMessage by viewModel.editingStatusMessage.collectAsStateWithLifecycle()

    var showPlayerDialog by remember { mutableStateOf(false) }

    if (showPlayerDialog && selectedVideo != null) {
        VideoPlayerDialog(
            video = selectedVideo!!,
            onDismiss = { showPlayerDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Editor de Vídeo Integrado",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Apare (trim) pontos indesejados, altere a velocidade e salve novos clipes rapidamente.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (selectedVideo == null) {
            // Select video card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Selecione um vídeo para editar:",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (activeVideos.isEmpty()) {
                        Text(
                            text = "Nenhum vídeo salvo disponível para edição.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        activeVideos.forEach { vid ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { viewModel.selectVideoForEdit(vid) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.MovieCreation, contentDescription = null, tint = RedPrimary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = vid.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(
                                        text = "${formatTime(vid.durationMs)} • ${vid.width}x${vid.height}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val video = selectedVideo!!
            val totalSeconds = (video.durationMs / 1000f).coerceAtLeast(1f)
            var trimRange by remember(video.id) { mutableStateOf(0f..totalSeconds) }
            var selectedSpeed by remember(video.id) { mutableFloatStateOf(1.0f) }

            // Active video card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = video.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                text = "Duração original: ${formatTime(video.durationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyanAccent
                            )
                        }
                        OutlinedButton(onClick = { viewModel.selectVideoForEdit(null) }) {
                            Text("Trocar")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = { showPlayerDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pré-visualizar Vídeo Original")
                    }
                }
            }

            // Trimming range card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCut, contentDescription = null, tint = RedPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Aparar Início e Fim (Trim)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Início: ${formatSeconds(trimRange.start)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Fim: ${formatSeconds(trimRange.endInclusive)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    RangeSlider(
                        value = trimRange,
                        onValueChange = { trimRange = it },
                        valueRange = 0f..totalSeconds,
                        colors = SliderDefaults.colors(
                            thumbColor = RedPrimary,
                            activeTrackColor = RedPrimary
                        )
                    )

                    val newDurationSec = (trimRange.endInclusive - trimRange.start) / selectedSpeed
                    Text(
                        text = "Duração resultante: ${formatSeconds(newDurationSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyanAccent
                    )
                }
            }

            // Speed card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = CyanAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Velocidade de Reprodução",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            val isSelected = speed == selectedSpeed
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedSpeed = speed },
                                label = { Text("${speed}x") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RedPrimary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            if (statusMessage != null) {
                Text(
                    text = statusMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusMessage!!.startsWith("Erro")) MaterialTheme.colorScheme.error else CyanAccent,
                    fontWeight = FontWeight.Medium
                )
            }

            Button(
                onClick = {
                    val startMs = (trimRange.start * 1000).toLong()
                    val endMs = (trimRange.endInclusive * 1000).toLong()
                    viewModel.trimSelectedVideo(context, startMs, endMs, selectedSpeed)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RedPrimary)
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("APARAR E SALVAR NOVO VÍDEO", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / 1000) / 60
    return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
}

private fun formatSeconds(seconds: Float): String {
    val totalSec = seconds.toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    val frac = ((seconds - totalSec) * 10).toInt()
    return String.format(Locale.getDefault(), "%02d:%02d.%d", min, sec, frac)
}
