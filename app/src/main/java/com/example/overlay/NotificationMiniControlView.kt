package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.example.service.ScreenRecordingService
import kotlin.math.hypot

/**
 * Mini Menu Flutuante Compacto aberto ao tocar no corpo da notificação durante a gravação
 * (quando "Mini Controle ao Tocar na Notificação" está ativado nos Ajustes).
 *
 * Exibe o cronômetro atual em tempo real + botões Pausar ⇄ Continuar, Parar e Fechar menu,
 * evitando abrir a MainActivity e cobrir o jogo ou área de trabalho do Samsung DeX.
 */
@SuppressLint("ViewConstructor")
class NotificationMiniControlView(
    context: Context,
    private val windowManager: WindowManager,
    private val onTogglePauseResume: () -> Unit,
    private val onStopRecording: () -> Unit,
    private val onDismissMenu: () -> Unit
) : FrameLayout(context) {

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
    val statusDot = View(context)
    val statusLabel = TextView(context)
    val timerText = TextView(context)
    val btnPauseResume = LinearLayout(context)
    private val pauseResumeIcon = ImageView(context)
    val pauseResumeLabel = TextView(context)
    val btnStop = LinearLayout(context)
    val btnCloseMenu = ImageButton(context)

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    init {
        setupCard()
        addView(cardContainer)
        setupDrag()
        updateRecordingState(ScreenRecordingService.recordingStatus.value)
    }

    private fun setupCard() {
        cardContainer.orientation = LinearLayout.VERTICAL
        cardContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(20).toFloat()
            setColor(0xF2141820.toInt())
            setStroke(dpToPx(1), 0x44FFFFFF)
        }
        cardContainer.elevation = dpToPx(10).toFloat()
        cardContainer.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(12))

        // Cabeçalho: ponto de status + estado + cronômetro + botão Fechar menu
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusDot.background = makeCircleDrawable(0xFFFF3B30.toInt())
        headerRow.addView(
            statusDot,
            LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply {
                marginEnd = dpToPx(8)
            }
        )

        statusLabel.text = "REC"
        statusLabel.setTextColor(0xFFFF5252.toInt())
        statusLabel.textSize = 11f
        statusLabel.typeface = Typeface.DEFAULT_BOLD
        headerRow.addView(
            statusLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(10)
            }
        )

        timerText.text = "00:00"
        timerText.setTextColor(Color.WHITE)
        timerText.textSize = 15f
        timerText.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        headerRow.addView(
            timerText,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = dpToPx(14)
            }
        )

        btnCloseMenu.layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(28))
        btnCloseMenu.background = makeCircleDrawable(0x26FFFFFF)
        btnCloseMenu.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        btnCloseMenu.setColorFilter(Color.WHITE)
        btnCloseMenu.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val closePad = dpToPx(6)
        btnCloseMenu.setPadding(closePad, closePad, closePad, closePad)
        btnCloseMenu.contentDescription = "Fechar menu de controle"
        btnCloseMenu.setOnClickListener {
            onDismissMenu()
        }
        headerRow.addView(btnCloseMenu)

        cardContainer.addView(
            headerRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(10)
            }
        )

        // Barra de ações: Pausar ⇄ Continuar + Parar
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Botão Pausar ⇄ Continuar
        btnPauseResume.orientation = LinearLayout.HORIZONTAL
        btnPauseResume.gravity = Gravity.CENTER
        btnPauseResume.background = makePillDrawable(0xFF283040.toInt())
        btnPauseResume.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
        btnPauseResume.isClickable = true
        btnPauseResume.contentDescription = "Pausar Gravação"

        pauseResumeIcon.setImageResource(R.drawable.ic_notif_pause)
        pauseResumeIcon.setColorFilter(Color.WHITE)
        btnPauseResume.addView(
            pauseResumeIcon,
            LinearLayout.LayoutParams(dpToPx(16), dpToPx(16)).apply {
                marginEnd = dpToPx(6)
            }
        )

        pauseResumeLabel.text = "Pausar"
        pauseResumeLabel.setTextColor(Color.WHITE)
        pauseResumeLabel.textSize = 13f
        pauseResumeLabel.typeface = Typeface.DEFAULT_BOLD
        btnPauseResume.addView(pauseResumeLabel)

        btnPauseResume.setOnClickListener {
            onTogglePauseResume()
        }
        actionsRow.addView(
            btnPauseResume,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(8)
            }
        )

        // Botão Parar (■)
        btnStop.orientation = LinearLayout.HORIZONTAL
        btnStop.gravity = Gravity.CENTER
        btnStop.background = makePillDrawable(0xFFE50914.toInt())
        btnStop.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
        btnStop.isClickable = true
        btnStop.contentDescription = "Parar Gravação"

        val stopIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_stop_square)
            setColorFilter(Color.WHITE)
        }
        btnStop.addView(
            stopIcon,
            LinearLayout.LayoutParams(dpToPx(15), dpToPx(15)).apply {
                marginEnd = dpToPx(6)
            }
        )

        val stopLabel = TextView(context).apply {
            text = "Parar"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        btnStop.addView(stopLabel)

        btnStop.setOnClickListener {
            onStopRecording()
        }
        actionsRow.addView(
            btnStop,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        cardContainer.addView(actionsRow)
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
                        layoutParams.y = (initialY + dy.toInt()).coerceAtLeast(0)
                        if (isAttachedToWindow) {
                            try {
                                windowManager.updateViewLayout(this, layoutParams)
                            } catch (_: Exception) {}
                        }
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

    private fun makeCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun makePillDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16).toFloat()
            setColor(color)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
