package com.example.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MiniBarCollapseBehavior
import com.example.model.SettingsPreferences
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.RedPrimary
import com.example.ui.theme.WarningAmber
import kotlin.math.roundToInt

/**
 * Controles independentes de Tamanho (80%..180%) e Opacidade (25%..100%) da Mini Barra Flutuante (Pílula),
 * acompanhados de uma pré-visualização interativa em escala real (1:1 em dp/sp — sem Modifier.scale)
 * e botão discreto para restaurar os padrões (120% e 90%).
 */
@Composable
fun DexMiniBarStyleControls(
    sizePercent: Int,
    opacityPercent: Int,
    collapseBehavior: MiniBarCollapseBehavior,
    onSizeChange: (Int) -> Unit,
    onOpacityChange: (Int) -> Unit,
    onResetToDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDefault = sizePercent == SettingsPreferences.DEFAULT_MINI_BAR_SIZE_PERCENT &&
        opacityPercent == SettingsPreferences.DEFAULT_MINI_BAR_OPACITY_PERCENT

    Column(modifier = modifier.fillMaxWidth()) {
        // Prévia interativa em escala real (1:1)
        DexMiniBarLivePreview(
            sizePercent = sizePercent,
            opacityPercent = opacityPercent,
            collapseBehavior = collapseBehavior
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tamanho da Mini Barra (Pílula): $sizePercent%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            if (!isDefault) {
                Text(
                    text = "Restaurar padrão",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onResetToDefault() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("btn_reset_mini_bar_style")
                )
            }
        }

        Slider(
            value = sizePercent.toFloat(),
            onValueChange = { raw ->
                val snapped = SettingsPreferences.sanitizeSizePercent(raw.roundToInt())
                if (snapped != sizePercent) {
                    onSizeChange(snapped)
                }
            },
            valueRange = SettingsPreferences.MIN_SIZE_PERCENT.toFloat()..SettingsPreferences.MAX_SIZE_PERCENT.toFloat(),
            steps = 19,
            colors = SliderDefaults.colors(
                thumbColor = RedPrimary,
                activeTrackColor = RedPrimary
            ),
            modifier = Modifier.testTag("slider_dex_mini_bar_size")
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Opacidade da Mini Barra (Pílula): $opacityPercent%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        Slider(
            value = opacityPercent.toFloat(),
            onValueChange = { raw ->
                val snapped = SettingsPreferences.sanitizeMiniBarOpacityPercent(raw.roundToInt())
                if (snapped != opacityPercent) {
                    onOpacityChange(snapped)
                }
            },
            valueRange = SettingsPreferences.MIN_MINI_BAR_OPACITY_PERCENT.toFloat()..SettingsPreferences.MAX_MINI_BAR_OPACITY_PERCENT.toFloat(),
            steps = 14,
            colors = SliderDefaults.colors(
                thumbColor = RedPrimary,
                activeTrackColor = RedPrimary
            ),
            modifier = Modifier.testTag("slider_dex_mini_bar_opacity")
        )
    }
}

/**
 * Palco interativo em escala real (1:1) que renderiza a Mini Barra (Pílula) e a Aba Lateral Recolhida
 * usando exatamente as mesmas dimensões reais em dp/sp do DexMiniBarView (nunca Modifier.scale).
 */
@Composable
fun DexMiniBarLivePreview(
    sizePercent: Int,
    opacityPercent: Int,
    collapseBehavior: MiniBarCollapseBehavior,
    modifier: Modifier = Modifier
) {
    var previewPaused by remember { mutableStateOf(false) }
    var previewCollapsed by remember { mutableStateOf(false) }

    val pillScale = SettingsPreferences.sanitizeSizePercent(sizePercent) / 100f
    val pillAlpha = SettingsPreferences.sanitizeMiniBarOpacityPercent(opacityPercent) / 100f
    val edgeTabAlpha = SettingsPreferences.computeEdgeTabAlpha(opacityPercent)

    val stageGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF0C1018),
            Color(0xFF161D2A),
            Color(0xFF101520)
        )
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(stageGradient)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .testTag("dex_mini_bar_live_preview"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Prévia em escala real (1:1)",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (previewCollapsed) "Aba lateral" else if (previewPaused) "Pausado" else "Gravando",
                style = MaterialTheme.typography.labelSmall,
                color = if (previewPaused) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 68.dp)
                .horizontalScroll(rememberScrollState()),
            contentAlignment = if (previewCollapsed) Alignment.CenterStart else Alignment.Center
        ) {
            if (!previewCollapsed) {
                // Dimensões 1:1 proporcionais ao DexMiniBarView
                val padH = (10f * pillScale).dp
                val padV = (6f * pillScale).dp
                val cornerRadius = (24f * pillScale).dp
                val dotSize = (10f * pillScale).dp
                val dotEndGap = (6f * pillScale).dp
                val timerEndGap = (10f * pillScale).dp
                val btnSize = (32f * pillScale).dp
                val closeBtnSize = (28f * pillScale).dp
                val btnGap = (6f * pillScale).dp
                val iconSize = (18f * pillScale).dp
                val closeIconSize = (15f * pillScale).dp

                Row(
                    modifier = Modifier
                        .alpha(pillAlpha)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(Color(0xEB141820))
                        .border(
                            width = (1f * pillScale).coerceAtLeast(1f).dp,
                            color = Color(0x44FFFFFF),
                            shape = RoundedCornerShape(cornerRadius)
                        )
                        .padding(horizontal = padH, vertical = padV)
                        .testTag("preview_mini_bar_pill"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .clip(CircleShape)
                            .background(if (previewPaused) Color(0xFFFFC400) else Color(0xFFE50914))
                    )
                    Spacer(modifier = Modifier.width(dotEndGap))
                    Text(
                        text = "00:08",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = (13f * pillScale).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum"
                        )
                    )
                    Spacer(modifier = Modifier.width(timerEndGap))

                    // Botão Pausar / Continuar interativo
                    Box(
                        modifier = Modifier
                            .size(btnSize)
                            .clip(CircleShape)
                            .background(Color(0xFF282F3D))
                            .border(1.dp, Color(0x33FFFFFF), CircleShape)
                            .clickable { previewPaused = !previewPaused }
                            .testTag("preview_mini_bar_pause"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (previewPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (previewPaused) "Continuar prévia" else "Pausar prévia",
                            tint = Color.White,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                    Spacer(modifier = Modifier.width(btnGap))

                    // Botão Parar (visual na prévia, restaura estado gravando ao tocar)
                    Box(
                        modifier = Modifier
                            .size(btnSize)
                            .clip(CircleShape)
                            .background(Color(0xFFE50914))
                            .clickable { previewPaused = false }
                            .testTag("preview_mini_bar_stop"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Parar prévia",
                            tint = Color.White,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                    Spacer(modifier = Modifier.width(btnGap))

                    // Botão Recolher para testar a Aba Lateral
                    Box(
                        modifier = Modifier
                            .size(closeBtnSize)
                            .clip(CircleShape)
                            .background(Color(0xFF1F242E))
                            .border(1.dp, Color(0x33FFFFFF), CircleShape)
                            .clickable { previewCollapsed = true }
                            .testTag("preview_mini_bar_collapse"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Testar aba lateral",
                            tint = Color.White,
                            modifier = Modifier.size(closeIconSize)
                        )
                    }
                }
            } else {
                // Aba Lateral Recolhida proporcional (escala real + opacidade proporcional)
                val tabPadH = (8f * pillScale).dp
                val tabPadV = (8f * pillScale).dp
                val tabCorner = (16f * pillScale).dp
                val tabDotSize = (8f * pillScale).dp
                val tabGap = (4f * pillScale).dp

                Row(
                    modifier = Modifier
                        .alpha(edgeTabAlpha)
                        .clip(RoundedCornerShape(tabCorner))
                        .background(Color(0xB8141820))
                        .border(
                            width = (1f * pillScale).coerceAtLeast(1f).dp,
                            color = Color(0x55FFFFFF),
                            shape = RoundedCornerShape(tabCorner)
                        )
                        .clickable { previewCollapsed = false }
                        .padding(horizontal = tabPadH, vertical = tabPadV)
                        .testTag("preview_mini_bar_edge_tab"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(tabDotSize)
                            .clip(CircleShape)
                            .background(if (previewPaused) Color(0xFFFFC400) else Color(0xFFE50914))
                    )
                    Spacer(modifier = Modifier.width(tabGap))
                    Text(
                        text = "00:08",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = (11f * pillScale).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum"
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val hintText = when {
            previewCollapsed -> "Aba lateral recolhida • Toque na aba para expandir a Pílula novamente"
            collapseBehavior == MiniBarCollapseBehavior.HIDE_UNTIL_NOTIFICATION_TAP ->
                "Toque em Pausar para testar • Toque em × para visualizar o estilo da aba lateral"
            else -> "Toque em Pausar ou × na prévia para testar o tamanho real dos botões"
        }
        Text(
            text = hintText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Card educativo recolhível exclusivo para Android 15+ (API 35+).
 * Conformidade com diretrizes de lojas de aplicativos:
 * - Visível somente nos Ajustes quando androidSdkInt >= 35 (sem popups ou toasts ao abrir o app).
 * - Recolhido por padrão com expansão suave.
 * - Texto neutro destacando a Mini Barra Flutuante e o Mini Controle ao Tocar na Notificação.
 * - Zero botões ou atalhos para abrir Opções do Desenvolvedor.
 */
@Composable
fun Android15NotificationInfoCard(
    modifier: Modifier = Modifier,
    androidSdkInt: Int = Build.VERSION.SDK_INT
) {
    if (androidSdkInt < 35) return

    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "android15InfoChevronRotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141B24))
            .border(
                width = 1.dp,
                color = CyanAccent.copy(alpha = 0.40f),
                shape = RoundedCornerShape(12.dp)
            )
            .testTag("card_android15_notif_info")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("card_android15_notif_info_header"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "Informação sobre notificações no Android 15",
                tint = CyanAccent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Por que os botões somem na notificação no Android 15?",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Recolher explicação" else "Expandir explicação",
                tint = CyanAccent,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(chevronRotation)
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                    .testTag("card_android15_notif_info_body"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "No Android 15 (One UI 7+), o sistema introduziu uma proteção automática de privacidade durante compartilhamento e gravação de tela. Quando essa proteção está ativa, o próprio Android oculta layouts customizados na barra de notificações.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
                Text(
                    text = "Para manter controles rápidos e confiáveis durante a gravação (especialmente no Samsung DeX), use a Mini Barra Flutuante (Pílula) acima ou ative o Mini Controle ao Tocar na Notificação — ambos funcionam de forma independente da notificação do sistema.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.90f),
                    lineHeight = 18.sp
                )
                Text(
                    text = "Nota para usuários avançados: a ocultação de notificações sensíveis durante captura de tela é controlada exclusivamente pelo sistema Android 15 e só pode ser ajustada manualmente nas configurações do próprio aparelho, se desejar.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}
