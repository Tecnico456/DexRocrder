package com.example.ui.screens

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.BitrateOption
import com.example.model.DexMiniBarMode
import com.example.model.MiniBarCollapseBehavior
import com.example.model.NotificationControlStyle
import com.example.model.ResolutionOption
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.RedDark
import com.example.ui.theme.RedPrimary
import com.example.viewmodel.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onRequestOverlayPermission: () -> Unit = {},
    modifier: Modifier = Modifier,
    androidSdkInt: Int = Build.VERSION.SDK_INT
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hasOverlayPermission = Settings.canDrawOverlays(context)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Configurações Avançadas",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Nova Seção: Controles de Gravação e Notificação (3 Opções Configuráveis + SharedPreferences)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("section_recording_controls_notification")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = CyanAccent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Controles de Gravação e Notificação",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ative, desative ou combine livremente a Bola Flutuante, a Mini Barra DeX e o estilo da notificação.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 1. Mini Barra Flutuante (Modo DeX) — 3 opções: Desativada, Apenas no Samsung DeX (padrão), Sempre
                Text(
                    text = "Mini Barra Flutuante (Modo DeX)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = settings.dexMiniBarMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DexMiniBarMode.values().forEach { mode ->
                        val selected = settings.dexMiniBarMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setDexMiniBarMode(context, mode) },
                            label = {
                                Text(
                                    text = mode.shortLabel,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = RedPrimary,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.testTag("dex_mini_bar_mode_${mode.name.lowercase()}")
                        )
                    }
                }

                // Sub-opção de recolhimento quando a Mini Barra estiver ativa
                AnimatedVisibility(visible = settings.dexMiniBarMode != DexMiniBarMode.OFF) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(
                            text = "Ao tocar em Recolher / Ocultar na Mini Barra",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        MiniBarCollapseBehavior.values().forEach { behavior ->
                            val isSelected = settings.miniBarCollapseBehavior == behavior
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) CyanAccent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { viewModel.setMiniBarCollapseBehavior(context, behavior) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("mini_bar_collapse_${behavior.name.lowercase()}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = behavior.title,
                                    tint = if (isSelected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = behavior.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        text = behavior.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Abre no topo central na 1ª vez, pode ser arrastada para qualquer canto e salva a última posição automaticamente.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        DexMiniBarStyleControls(
                            sizePercent = settings.dexMiniBarSizePercent,
                            opacityPercent = settings.dexMiniBarOpacityPercent,
                            collapseBehavior = settings.miniBarCollapseBehavior,
                            onSizeChange = { viewModel.updateDexMiniBarSize(context, it) },
                            onOpacityChange = { viewModel.updateDexMiniBarOpacity(context, it) },
                            onResetToDefault = { viewModel.resetDexMiniBarStyle(context) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(14.dp))

                // 2. Seletor: Estilo da Notificação (CUSTOM vs MEDIA_STYLE)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Estilo da Notificação",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Atualiza na hora",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                NotificationControlStyle.values().forEach { style ->
                    val isSelected = settings.notificationStyle == style
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) RedPrimary else Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { viewModel.setNotificationStyle(context, style) }
                            .padding(12.dp)
                            .testTag("notification_style_${style.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = style.title,
                            tint = if (isSelected) RedPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = style.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = style.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (androidSdkInt >= 35) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Android15NotificationInfoCard(androidSdkInt = androidSdkInt)
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(14.dp))

                // 3. Switch: Mini Controle ao Tocar na Notificação
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Mini Controle ao Tocar na Notificação",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (settings.notificationTapOpensMiniControl) {
                                "Durante a gravação, tocar na notificação abre o Mini Menu Flutuante (cronômetro, Pausar e Parar) sem abrir a janela principal."
                            } else {
                                "Tocar na notificação abre normalmente a janela principal do DexRecorder."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.notificationTapOpensMiniControl,
                        onCheckedChange = { enabled ->
                            viewModel.setNotificationTapOpensMiniControl(context, enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = RedPrimary,
                            checkedTrackColor = RedDark
                        ),
                        modifier = Modifier.testTag("switch_notification_tap_mini_control")
                    )
                }

                if (!hasOverlayPermission && (settings.enableDexMiniBar || settings.notificationTapOpensMiniControl)) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(RedDark.copy(alpha = 0.35f))
                            .border(1.dp, RedPrimary.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .clickable { onRequestOverlayPermission() }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("btn_grant_overlay_permission_controls"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Permissão de sobreposição",
                            tint = RedPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Toque aqui para conceder permissão de sobreposição para a Mini Barra e Mini Controle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Video Settings
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = RedPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Qualidade de Vídeo & Codificador",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(text = "Resolução de Gravação", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                ResolutionOption.values().forEach { res ->
                    val isSelected = res == settings.resolution
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .clickable { viewModel.setResolution(res) }
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = res.label, color = if (isSelected) RedPrimary else MaterialTheme.colorScheme.onSurface)
                        if (isSelected) {
                            Text("Selecionado", color = RedPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(text = "Taxa de Bits (Bitrate)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(BitrateOption.MBPS_8, BitrateOption.MBPS_12, BitrateOption.MBPS_16, BitrateOption.MBPS_24).forEach { br ->
                        FilterChip(
                            selected = br == settings.bitrate,
                            onClick = { viewModel.setBitrate(br) },
                            label = { Text(br.label.substringBefore(" ")) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RedPrimary, selectedLabelColor = Color.White)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Codificador H.265 / HEVC", fontWeight = FontWeight.Bold)
                        Text(
                            "Maior compressão mantendo alta qualidade (requer suporte de hardware).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.useHevcCodec,
                        onCheckedChange = { viewModel.updateSettings { s -> s.copy(useHevcCodec = it) } },
                        colors = SwitchDefaults.colors(checkedThumbColor = RedPrimary, checkedTrackColor = RedDark)
                    )
                }
            }
        }

        // Audio Balance Sliders
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = CyanAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Misturador de Áudio em Tempo Real (Mixer PCM)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(text = "Volume do Áudio Interno: ${(settings.internalAudioVolume * 100).toInt()}%")
                Slider(
                    value = settings.internalAudioVolume,
                    onValueChange = { viewModel.setVolumes(it, settings.micAudioVolume) },
                    valueRange = 0f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = CyanAccent)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(text = "Volume do Microfone: ${(settings.micAudioVolume * 100).toInt()}%")
                Slider(
                    value = settings.micAudioVolume,
                    onValueChange = { viewModel.setVolumes(settings.internalAudioVolume, it) },
                    valueRange = 0f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = RedPrimary, activeTrackColor = RedPrimary)
                )
            }
        }

        // Countdown & Floating Behavior
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Temporizador de Contagem Regressiva",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 5, 10).forEach { sec ->
                        val label = if (sec == 0) "Sem atraso" else "${sec}s"
                        FilterChip(
                            selected = settings.countdownSeconds == sec,
                            onClick = { viewModel.setCountdown(sec) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RedPrimary, selectedLabelColor = Color.White)
                        )
                    }
                }
            }
        }

        // Samsung DeX & Storage Info Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DesktopWindows, contentDescription = null, tint = CyanAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Samsung DeX & Armazenamento",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Compatível com redimensionamento de janela dinâmico no DeX (android:resizeableActivity=\"true\").\n" +
                            "• Os vídeos são gravados diretamente no armazenamento público em Filmes/DexRecorder usando a MediaStore API moderna (sem necessidade de permissões obsoletas).\n" +
                            "• Captura infalível de áudio interno com suporte a fones P2, USB-C e Bluetooth A2DP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }
    }
}
