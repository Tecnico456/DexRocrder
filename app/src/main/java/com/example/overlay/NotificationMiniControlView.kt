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
import kotlin.math.roundToInt

/**
 * Mini Menu Flutuante Compacto aberto ao tocar no corpo da notificação durante a gravação
 * (quando "Mini Controle ao Tocar na Notificação" está ativado nos Ajustes).
 *
 * Aplica redimensionamento proporcional real em dp/sp (pillScale) e opacidade (dexMiniBarOpacityPercent)
 * sem recortar bordas no WindowManager.
 */
@SuppressLint("ViewConstructor")
class NotificationMiniControlView(
    context: Context,
    private val windowManager: WindowManager,
    private val onTogglePauseResume: () -> Unit,
    private val onStopRecording: () -> Unit,
    private val onDismissMenu: () -> Unit,
    initialSizePercent: Int = SettingsPreferences.DEFAULT_MINI_BAR_SIZE_PERCENT,
    initialOpacityPercent: Int = SettingsPreferences.DEFAULT_MINI_BAR_OPACITY_PERCENT
) : FrameLayout(context) {

    var currentSizePercent: Int = SettingsPreferences.sanitizeSizePercent(initialSizePercent)
        private set

    var currentOpacityPercent: Int = SettingsPreferences.sanitizeMiniBarOpacityPercent(initialOpacityPercent)
        private set

    private val pillScale: Float
        get() = currentSizePercent / 100f

    var isPausedState: Boolean = false
        private set

    val layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = 0
        y = dpToPx(56)
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

    private fun setupCard() {
        cardContainer.orientation = LinearLayout.VERTICAL
        cardContainer.elevation = dpToPx(10).toFloat()

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
     * TAREFA 3: Redimensionamento proporcional real em dp/sp e opacidade segura no WindowManager.
     */
    fun applyStyle(sizePercent: Int, opacityPercent: Int) {
        currentSizePercent = SettingsPreferences.sanitizeSizePercent(sizePercent)
        currentOpacityPercent = SettingsPreferences.sanitizeMiniBarOpacityPercent(opacityPercent)

        val s = pillScale
        val userAlpha = currentOpacityPercent / 100f

        cardContainer.alpha = userAlpha
        cardContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = scaledDp(20, s).toFloat()
            setColor(0xF2141820.toInt())
            setStroke(scaledDp(1, s).coerceAtLeast(1), 0x44FFFFFF)
        }
        cardContainer.setPadding(
            scaledDp(14, s),
            scaledDp(10, s),
            scaledDp(14, s),
            scaledDp(12, s)
        )

        headerRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = scaledDp(10, s)
        }

        val dotSize = scaledDp(10, s)
        statusDot.layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginEnd = scaledDp(8, s)
        }

        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * s)
        statusLabel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = scaledDp(10, s)
        }

        timerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * s)
        timerText.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginEnd = scaledDp(14, s)
        }

        val closeSize = scaledDp(28, s)
        val closePad = scaledDp(6, s)
        btnCloseMenu.layoutParams = LinearLayout.LayoutParams(closeSize, closeSize)
        btnCloseMenu.setPadding(closePad, closePad, closePad, closePad)

        val pillCorner = scaledDp(16, s).toFloat()
        val pillPadH = scaledDp(14, s)
        val pillPadV = scaledDp(8, s)
        val iconSize = scaledDp(16, s)
        val stopIconSize = scaledDp(15, s)
        val iconMargin = scaledDp(6, s)

        btnPauseResume.background = makePillDrawable(0xFF283040.toInt(), pillCorner)
        btnPauseResume.setPadding(pillPadH, pillPadV, pillPadH, pillPadV)
        btnPauseResume.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = scaledDp(8, s)
        }
        pauseResumeIcon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginEnd = iconMargin
        }
        pauseResumeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * s)

        btnStop.background = makePillDrawable(0xFFE50914.toInt(), pillCorner)
        btnStop.setPadding(pillPadH, pillPadV, pillPadH, pillPadV)
        btnStop.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        stopIcon.layoutParams = LinearLayout.LayoutParams(stopIconSize, stopIconSize).apply {
            marginEnd = iconMargin
        }
        stopLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * s)

        requestLayout()
        clampPositionToScreen()
        safeUpdateWindowLayout()
    }

    fun clampPositionToScreen() {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(dpToPx(320))
        val screenH = metrics.heightPixels.coerceAtLeast(dpToPx(320))
        val estWidth = if (width > 0) width else scaledDp(240, pillScale)
        val estHeight = if (height > 0) height else scaledDp(96, pillScale)

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
        val touchSlop = dpToPx(6)
        cardContainer.setOnTouchListener { _, event ->
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

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun scaledDp(baseDp: Int, scale: Float): Int {
        return (baseDp * scale * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    }
}
