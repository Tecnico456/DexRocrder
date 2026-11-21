package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R
import com.example.model.RecordingStatus
import com.example.model.SettingsPreferences
import com.example.service.ScreenRecordingService
import kotlin.math.hypot

/**
 * Mini Menu Flutuante Compacto aberto ao tocar no corpo da notificação durante a gravação
 * (quando "Mini Controle ao Tocar na Notificação" está ativado nos Ajustes).
 *
 * Aplica redimensionamento proporcional real em dp/sp (80% a 300%) usando a densidade real
 * do display alvo, opacidade configurável e proteção de 94% de largura sem recortar bordas.
 */
@SuppressLint("ViewConstructor")
class NotificationMiniControlView(
    context: Context,
    private val windowManager: WindowManager,
    private val onTogglePauseResume: () -> Unit,
    private val onStopRecording: () -> Unit,
    private val onDismissMenu: () -> Unit,
    initialSizePercent: Int = SettingsPreferences.DEFAULT_MINI_BAR_SIZE_PERCENT,
    initialOpacityPercent: Int = SettingsPreferences.DEFAULT_MINI_BAR_OPACITY_PERCENT,
    private val metricsProvider: (() -> OverlayDisplaySnapshot)? = null
) : FrameLayout(context) {

    var currentSizePercent: Int = SettingsPreferences.sanitizeSizePercent(initialSizePercent)
        private set

    var currentOpacityPercent: Int = SettingsPreferences.sanitizeMiniBarOpacityPercent(initialOpacityPercent)
        private set

    private val requestedPillScale: Float
        get() = currentSizePercent / 100f

    /**
     * Escala efetiva aplicada após o limitador de segurança de 94% da largura do display.
     */
    var effectivePillScale: Float = requestedPillScale
        private set

    var isPausedState: Boolean = false
        private set

    private fun currentMetrics(): OverlayDisplaySnapshot {
        return metricsProvider?.invoke() ?: OverlayDisplayMetrics.readMetrics(context, windowManager)
    }

    val layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = 0
        y = currentMetrics().dpToPx(56)
    }

    val cardContainer = LinearLayout(context)
    private val headerRow = LinearLayout(context)
    private val actionsRow = LinearLayout(context)
    val statusDot = View(context)
    val statusLabel = TextView(context)
    val timerText = TextView(context)
    val btnPauseResume = LinearLayout(context)
    private val pauseResumeIcon = ImageView(context)
    val pauseResumeLabel = TextView(context)
    val btnStop = LinearLayout(context)
    private val stopIcon = ImageView(context)
    private val stopLabel = TextView(context)
    val btnCloseMenu = ImageButton(context)

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    init {
        setupCard()
        addView(cardContainer)
        applyStyle(currentSizePercent, currentOpacityPercent)
        setupDrag()
        updateRecordingState(ScreenRecordingService.recordingStatus.value)
    }

    private fun computeEffectiveScale(metrics: OverlayDisplaySnapshot, requested: Float): Float {
        // Largura base estimada do Mini Controle em 100% (~240dp com botões "Continuar" e "Parar")
        val naturalWidthPx = metrics.scaledDp(240, requested)
        val maxAllowedPx = metrics.maxAllowedWidthPx
        return if (naturalWidthPx > maxAllowedPx) {
            (requested * (maxAllowedPx.toFloat() / naturalWidthPx.toFloat())).coerceAtLeast(0.55f)
        } else {
            requested
        }
    }

    private fun setupCard() {
        cardContainer.orientation = LinearLayout.VERTICAL
        cardContainer.elevation = currentMetrics().dpToPx(10).toFloat()

        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL

        statusDot.background = makeCircleDrawable(0xFFFF3B30.toInt())
        headerRow.addView(statusDot)

        statusLabel.text = "REC"
        statusLabel.setTextColor(0xFFFF5252.toInt())
        statusLabel.typeface = Typeface.DEFAULT_BOLD
        headerRow.addView(statusLabel)

        timerText.text = "00:00"
        timerText.setTextColor(Color.WHITE)
        timerText.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        timerText.fontFeatureSettings = "tnum"
        headerRow.addView(timerText)

        btnCloseMenu.background = makeCircleDrawable(0x26FFFFFF)
        btnCloseMenu.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        btnCloseMenu.setColorFilter(Color.WHITE)
        btnCloseMenu.scaleType = ImageView.ScaleType.FIT_CENTER
        btnCloseMenu.contentDescription = "Fechar menu de controle"
        btnCloseMenu.setOnClickListener {
            onDismissMenu()
        }
        headerRow.addView(btnCloseMenu)

        cardContainer.addView(headerRow)

        actionsRow.orientation = LinearLayout.HORIZONTAL
        actionsRow.gravity = Gravity.CENTER_VERTICAL

        // Botão Pausar ⇄ Continuar
        btnPauseResume.orientation = LinearLayout.HORIZONTAL
        btnPauseResume.gravity = Gravity.CENTER
        btnPauseResume.isClickable = true
        btnPauseResume.contentDescription = "Pausar Gravação"

        pauseResumeIcon.setImageResource(R.drawable.ic_notif_pause)
        pauseResumeIcon.setColorFilter(Color.WHITE)
        pauseResumeIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        btnPauseResume.addView(pauseResumeIcon)

        pauseResumeLabel.text = "Pausar"
        pauseResumeLabel.setTextColor(Color.WHITE)
        pauseResumeLabel.typeface = Typeface.DEFAULT_BOLD
        btnPauseResume.addView(pauseResumeLabel)

        btnPauseResume.setOnClickListener {
            onTogglePauseResume()
        }
        actionsRow.addView(btnPauseResume)

        // Botão Parar (■)
        btnStop.orientation = LinearLayout.HORIZONTAL
        btnStop.gravity = Gravity.CENTER
        btnStop.isClickable = true
        btnStop.contentDescription = "Parar Gravação"

        stopIcon.setImageResource(R.drawable.ic_stop_square)
        stopIcon.setColorFilter(Color.WHITE)
        stopIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        btnStop.addView(stopIcon)

        stopLabel.text = "Parar"
        stopLabel.setTextColor(Color.WHITE)
        stopLabel.typeface = Typeface.DEFAULT_BOLD
        btnStop.addView(stopLabel)

        btnStop.setOnClickListener {
            onStopRecording()
        }
        actionsRow.addView(btnStop)

        cardContainer.addView(actionsRow)
    }

    /**
     * TAREFA 2 & 3: Redimensionamento proporcional real em dp/sp com densidade real do display alvo,
     * opacidade configurável e proteção contra estouro acima de 94% da largura da tela.
     */
    fun applyStyle(sizePercent: Int, opacityPercent: Int) {
        currentSizePercent = SettingsPreferences.sanitizeSizePercent(sizePercent)
        currentOpacityPercent = SettingsPreferences.sanitizeMiniBarOpacityPercent(opacityPercent)

        val metrics = currentMetrics()
        val s = computeEffectiveScale(metrics, requestedPillScale)
        effectivePillScale = s
        val userAlpha = currentOpacityPercent / 100f

        cardContainer.alpha = userAlpha
        cardContainer.elevation = metrics.dpToPx(10).toFloat()
        cardContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = metrics.scaledDp(20, s).toFloat()
            setColor(0xF2141820.toInt())
            setStroke(metrics.scaledDp(1, s).coerceAtLeast(1), 0x44FFFFFF)
        }
        cardContainer.setPadding(
            metrics.scaledDp(14, s),
            metrics.scaledDp(10, s),
            metrics.scaledDp(14, s),
            metrics.scaledDp(12, s)
        )

        headerRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = metrics.scaledDp(10, s)
        }

        val dotSize = metrics.scaledDp(10, s)
        statusDot.layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginEnd = metrics.scaledDp(8, s)
        }

        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.scaledSpPx(11f, s))
        statusLabel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = metrics.scaledDp(10, s)
        }

        timerText.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.scaledSpPx(15f, s))
        timerText.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginEnd = metrics.scaledDp(14, s)
        }

        val closeSize = metrics.scaledDp(28, s)
        val closePad = metrics.scaledDp(6, s)
        btnCloseMenu.layoutParams = LinearLayout.LayoutParams(closeSize, closeSize)
        btnCloseMenu.setPadding(closePad, closePad, closePad, closePad)

        val pillCorner = metrics.scaledDp(16, s).toFloat()
        val pillPadH = metrics.scaledDp(14, s)
        val pillPadV = metrics.scaledDp(8, s)
        val iconSize = metrics.scaledDp(16, s)
        val stopIconSize = metrics.scaledDp(15, s)
        val iconMargin = metrics.scaledDp(6, s)

        btnPauseResume.background = makePillDrawable(0xFF283040.toInt(), pillCorner)
        btnPauseResume.setPadding(pillPadH, pillPadV, pillPadH, pillPadV)
        btnPauseResume.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = metrics.scaledDp(8, s)
        }
        pauseResumeIcon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginEnd = iconMargin
        }
        pauseResumeLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.scaledSpPx(13f, s))

        btnStop.background = makePillDrawable(0xFFE50914.toInt(), pillCorner)
        btnStop.setPadding(pillPadH, pillPadV, pillPadH, pillPadV)
        btnStop.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        stopIcon.layoutParams = LinearLayout.LayoutParams(stopIconSize, stopIconSize).apply {
            marginEnd = iconMargin
        }
        stopLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.scaledSpPx(13f, s))

        requestLayout()
        clampPositionToScreen()
        safeUpdateWindowLayout()
    }

    fun clampPositionToScreen() {
        val metrics = currentMetrics()
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val estWidth = if (width > 0) {
            width.coerceAtMost(screenW)
        } else {
            metrics.scaledDp(240, effectivePillScale).coerceAtMost(metrics.maxAllowedWidthPx)
        }
        val estHeight = if (height > 0) {
            height.coerceAtMost(screenH)
        } else {
            metrics.scaledDp(96, effectivePillScale)
        }

        val maxHalfX = ((screenW - estWidth) / 2).coerceAtLeast(0)
        layoutParams.x = layoutParams.x.coerceIn(-maxHalfX, maxHalfX)
        layoutParams.y = layoutParams.y.coerceIn(0, (screenH - estHeight).coerceAtLeast(0))
    }

    fun updateRecordingState(status: RecordingStatus) {
        when (status) {
            is RecordingStatus.Recording -> {
                isPausedState = false
                statusDot.background = makeCircleDrawable(0xFFFF3B30.toInt())
                statusLabel.text = "REC"
                statusLabel.setTextColor(0xFFFF5252.toInt())
                timerText.text = ScreenRecordingService.formatDurationStatic(status.durationMs)
                pauseResumeIcon.setImageResource(R.drawable.ic_notif_pause)
                pauseResumeLabel.text = "Pausar"
                btnPauseResume.contentDescription = "Pausar Gravação"
            }
            is RecordingStatus.Paused -> {
                isPausedState = true
                statusDot.background = makeCircleDrawable(0xFFFFC400.toInt())
                statusLabel.text = "PAUSADO"
                statusLabel.setTextColor(0xFFFFC400.toInt())
                timerText.text = ScreenRecordingService.formatDurationStatic(status.durationMs)
                pauseResumeIcon.setImageResource(R.drawable.ic_notif_play)
                pauseResumeLabel.text = "Continuar"
                btnPauseResume.contentDescription = "Continuar Gravação"
            }
            else -> {
                isPausedState = false
                statusLabel.text = "PRONTO"
                timerText.text = "00:00"
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        cardContainer.setOnTouchListener { _, event ->
            val touchSlop = currentMetrics().dpToPx(6)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        clampPositionToScreen()
                        safeUpdateWindowLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun safeUpdateWindowLayout() {
        if (isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(this, layoutParams)
            } catch (_: Exception) {}
        }
    }

    private fun makeCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun makePillDrawable(color: Int, cornerRadiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(color)
        }
    }
}
