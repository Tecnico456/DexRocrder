package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AudioCaptureMode
import com.example.model.FpsOption
import com.example.model.RecordingSettings
import com.example.model.RecordingStatus
import com.example.model.ResolutionOption
import com.example.service.FloatingOverlayService
import com.example.service.ScreenRecordingService
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.RedDark
import com.example.ui.theme.RedPrimary
import com.example.ui.theme.RedSecondary
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber
import com.example.viewmodel.MainViewModel

@Composable
fun RecordDashboardScreen(
    viewModel: MainViewModel,
    onStartRecordingClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val status by viewModel.recordingStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Samsung DeX Badge
        DeXStatusHeader()

        // Hero Recording Status Card
        HeroRecordingStatusCard(
            status = status,
            settings = settings,
            onStartClick = onStartRecordingClick,
            onPauseClick = { viewModel.pauseRecording(context) },
            onResumeClick = { viewModel.resumeRecording(context) },
            onStopClick = { viewModel.stopRecording(context) }
        )

        // Audio Source Selection (Critical Req 1)
        AudioCaptureModeCard(
            selectedMode = settings.audioMode,
            onSelectMode = { viewModel.setAudioMode(it) }
        )

        // Video Quality Presets
        VideoQualitySelectorCard(
            settings = settings,
            onResolutionChange = { viewModel.setResolution(it) },
            onFpsChange = { viewModel.setFps(it) }
        )

        // Floating Ball & Screen Tools (SYSTEM_ALERT_WINDOW)
        FloatingToolsCard(
            context = context,
            settings = settings,
            onToggleBall = { viewModel.toggleFloatingBall(context, it) },
            onOpacityChange = { viewModel.updateFloatingOpacity(context, it) }
        )
    }
}

@Composable
private fun DeXStatusHeader() {
    val context = LocalContext.current
    val isDeX = ScreenRecordingService.isDeXMode(context)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isDeX) CyanAccent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.DesktopWindows,
                contentDescription = "Samsung DeX",
                tint = if (isDeX) SuccessGreen else CyanAccent,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = if (isDeX) "Modo Samsung DeX Ativo (Monitor Externo)" else "Samsung DeX & Multi-Window Ready",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isDeX) SuccessGreen else CyanAccent,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isDeX)
                        "Resolução 16:9 e densidade nativas do monitor externo detectadas (sem windowboxing)."
                    else
                        "Detecção automática de monitores externos e orientação para jogos em tela cheia.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HeroRecordingStatusCard(
    status: RecordingStatus,
    settings: RecordingSettings,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surface
        )
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(modifier = Modifier.background(gradient).padding(20.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (status) {
                    is RecordingStatus.Idle -> {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(RedDark.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = null,
                                tint = RedPrimary,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Pronto para Gravar",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Sem marca d'água • Sem limite de tempo • Sem root",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onStartClick,
                            colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("start_recording_button")
                        ) {
                            Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("INICIAR GRAVAÇÃO", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }

                    is RecordingStatus.Countdown -> {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(RedPrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${status.secondsLeft}",
                                style = MaterialTheme.typography.displayMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Iniciando captura da tela...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    is RecordingStatus.Recording -> {
                        val seconds = (status.durationMs / 1000) % 60
                        val minutes = (status.durationMs / (1000 * 60)) % 60
                        val hours = status.durationMs / (1000 * 60 * 60)
                        val timeStr = if (hours > 0) {
                            String.format("%02d:%02d:%02d", hours, minutes, seconds)
                        } else {
                            String.format("%02d:%02d", minutes, seconds)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(RedPrimary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GRAVANDO EM ANDAMENTO",
                                color = RedPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onPauseClick,
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pausar")
                            }

                            Button(
                                onClick = onStopClick,
                                modifier = Modifier.weight(1f).height(48.dp).testTag("stop_recording_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Finalizar")
                            }
                        }
                    }

                    is RecordingStatus.Paused -> {
                        Text(
                            text = "PAUSADO",
                            color = WarningAmber,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onResumeClick,
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Continuar")
                            }

                            Button(
                                onClick = onStopClick,
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Finalizar")
                            }
                        }
                    }

                    is RecordingStatus.Stopping -> {
                        Text("Salvando gravação no MediaStore...", style = MaterialTheme.typography.titleMedium)
                    }

                    is RecordingStatus.Error -> {
                        Text(
                            text = status.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioCaptureModeCard(
    selectedMode: AudioCaptureMode,
    onSelectMode: (AudioCaptureMode) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Headphones, contentDescription = null, tint = CyanAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Fonte de Áudio (Android 10+ Infalível)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Captura de áudio interno com proteção para fones de ouvido e mixagem de microfone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            AudioCaptureMode.values().forEach { mode ->
                val isSelected = mode == selectedMode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else Color.Transparent
                        )
                        .border(
                            1.dp,
                            if (isSelected) RedPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectMode(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (mode) {
                        AudioCaptureMode.INTERNAL_ONLY -> Icons.Default.Headphones
                        AudioCaptureMode.MIXED_INTERNAL_MIC -> Icons.Default.Radio
                        AudioCaptureMode.MIC_ONLY -> Icons.Default.Mic
                        AudioCaptureMode.MUTE -> Icons.Default.VolumeOff
                    }

                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (isSelected) RedPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isSelected) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = RedPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoQualitySelectorCard(
    settings: RecordingSettings,
    onResolutionChange: (ResolutionOption) -> Unit,
    onFpsChange: (FpsOption) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Resolução & Taxa de Quadros (FPS)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Resolutions chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    ResolutionOption.RES_1080P,
                    ResolutionOption.RES_4K,
                    ResolutionOption.RES_720P,
                    ResolutionOption.NATIVE
                ).forEach { res ->
                    val isSelected = res == settings.resolution
                    FilterChip(
                        selected = isSelected,
                        onClick = { onResolutionChange(res) },
                        label = { Text(res.label.substringBefore(" ")) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RedPrimary,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // FPS chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FpsOption.values().forEach { fps ->
                    val isSelected = fps == settings.fps
                    FilterChip(
                        selected = isSelected,
                        onClick = { onFpsChange(fps) },
                        label = { Text("${fps.fps} FPS") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RedPrimary,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingToolsCard(
    context: Context,
    settings: RecordingSettings,
    onToggleBall: (Boolean) -> Unit,
    onOpacityChange: (Float) -> Unit
) {
    val hasOverlayPermission = Settings.canDrawOverlays(context)

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
                    Text(
                        text = "Bola Flutuante (Floating Bubble)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Controle a gravação de qualquer aplicativo ou jogo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.showFloatingBall && hasOverlayPermission,
                    onCheckedChange = { enable ->
                        if (enable && !hasOverlayPermission) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            onToggleBall(enable)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = RedPrimary, checkedTrackColor = RedDark)
                )
            }

            AnimatedVisibility(visible = settings.showFloatingBall && hasOverlayPermission) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = "Opacidade da Bola: ${(settings.floatingBallOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = settings.floatingBallOpacity,
                        onValueChange = onOpacityChange,
                        valueRange = 0.2f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = RedPrimary, activeTrackColor = RedPrimary)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, FloatingOverlayService::class.java).apply {
                                    action = FloatingOverlayService.ACTION_TOGGLE_FACECAM
                                }
                                context.startService(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = CyanAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Facecam")
                        }

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, FloatingOverlayService::class.java).apply {
                                    action = FloatingOverlayService.ACTION_TOGGLE_BRUSH
                                }
                                context.startService(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = CyanAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pincel")
                        }
                    }
                }
            }
        }
    }
}
