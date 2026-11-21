package com.example.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Snapshot imutável das métricas físicas reais do display onde o overlay está sendo anexado.
 */
data class OverlayDisplaySnapshot(
    val displayId: Int,
    val density: Float,
    val scaledDensity: Float,
    val widthPixels: Int,
    val heightPixels: Int
) {
    /**
     * TAREFA 3: Teto de segurança de 94% da largura útil do display atual para evitar recorte de botões em 300%.
     */
    val maxAllowedWidthPx: Int
        get() = (widthPixels * 0.94f).roundToInt().coerceAtLeast(dpToPx(180))

    fun dpToPx(dp: Int): Int {
        return (dp * density + 0.5f).toInt().coerceAtLeast(1)
    }

    fun scaledDp(baseDp: Int, scale: Float): Int {
        return (baseDp * scale * density).roundToInt().coerceAtLeast(1)
    }

    fun scaledSpPx(baseSp: Float, scale: Float): Float {
        return (baseSp * scale * scaledDensity).coerceAtLeast(1f)
    }
}

/**
 * TAREFA 2: Resolve o display real onde o WindowManager desenha o overlay (via DisplayManager /
 * createDisplayContext), evitando que o serviço herde a densidade reduzida do monitor Samsung DeX
 * quando o overlay estiver sendo exibido na tela de alta densidade do smartphone (ou vice-versa).
 */
object OverlayDisplayMetrics {

    fun resolveTargetDisplay(baseContext: Context, windowManager: WindowManager? = null): Display? {
        val displayManager = baseContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

        @Suppress("DEPRECATION")
        val wmDisplay = runCatching { windowManager?.defaultDisplay }.getOrNull()
        val ctxDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { baseContext.display }.getOrNull()
        } else {
            null
        }

        val candidateId = wmDisplay?.displayId
            ?: ctxDisplay?.displayId
            ?: Display.DEFAULT_DISPLAY

        return displayManager?.getDisplay(candidateId)
            ?: wmDisplay
            ?: ctxDisplay
            ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    }

    /**
     * Cria um Context vinculado ao display real do WindowManager para que recursos, densidades
     * e WindowManager.addView usem as métricas nativas daquela tela.
     */
    fun resolveOverlayDisplayContext(baseContext: Context, windowManager: WindowManager? = null): Context {
        val targetDisplay = resolveTargetDisplay(baseContext, windowManager) ?: return baseContext
        return runCatching {
            baseContext.createDisplayContext(targetDisplay)
        }.getOrDefault(baseContext)
    }

    /**
     * Lê as DisplayMetrics físicas reais do display alvo (density, scaledDensity, largura e altura).
     */
    fun readMetrics(context: Context, windowManager: WindowManager? = null): OverlayDisplaySnapshot {
        val fallbackMetrics = context.resources.displayMetrics
        val targetDisplay = resolveTargetDisplay(context, windowManager)

        val realMetrics = DisplayMetrics()
        var hasRealMetrics = false
        if (targetDisplay != null) {
            hasRealMetrics = runCatching {
                @Suppress("DEPRECATION")
                targetDisplay.getRealMetrics(realMetrics)
                realMetrics.density > 0f && realMetrics.widthPixels > 0 && realMetrics.heightPixels > 0
            }.getOrDefault(false)
        }

        val displayContextMetrics = if (targetDisplay != null) {
            runCatching {
                context.createDisplayContext(targetDisplay).resources.displayMetrics
            }.getOrNull()
        } else {
            null
        }

        val chosenDensity = when {
            hasRealMetrics && realMetrics.density > 0f -> realMetrics.density
            displayContextMetrics != null && displayContextMetrics.density > 0f -> displayContextMetrics.density
            else -> fallbackMetrics.density.coerceAtLeast(1f)
        }

        val fontScale = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val chosenScaledDensity = (chosenDensity * fontScale).coerceAtLeast(1f)

        var chosenWidth = when {
            hasRealMetrics && realMetrics.widthPixels > 0 -> realMetrics.widthPixels
            displayContextMetrics != null && displayContextMetrics.widthPixels > 0 -> displayContextMetrics.widthPixels
            else -> fallbackMetrics.widthPixels
        }
        var chosenHeight = when {
            hasRealMetrics && realMetrics.heightPixels > 0 -> realMetrics.heightPixels
            displayContextMetrics != null && displayContextMetrics.heightPixels > 0 -> displayContextMetrics.heightPixels
            else -> fallbackMetrics.heightPixels
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            runCatching {
                val bounds = windowManager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    chosenWidth = bounds.width()
                    chosenHeight = bounds.height()
                }
            }
        }

        val minSafePx = (320 * chosenDensity).roundToInt().coerceAtLeast(320)
        return OverlayDisplaySnapshot(
            displayId = targetDisplay?.displayId ?: Display.DEFAULT_DISPLAY,
            density = chosenDensity,
            scaledDensity = chosenScaledDensity,
            widthPixels = chosenWidth.coerceAtLeast(minSafePx),
            heightPixels = chosenHeight.coerceAtLeast(minSafePx)
        )
    }
}
