package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import kotlin.math.abs
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class FloatingBallView(
    context: Context,
    private val windowManager: WindowManager,
    private val onStartRecording: () -> Unit,
    private val onToggleBrush: () -> Unit,
    private val onToggleFacecam: () -> Unit,
    private val onCloseBall: () -> Unit
) : FrameLayout(context) {

    private val ballSize = dpToPx(56)
    private val closeTargetSize = dpToPx(68)

    val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 20
        y = 350
    }

    val closeTargetLayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dpToPx(48)
    }

    private val bubbleWrapper = FrameLayout(context)
    private val bubbleContainer = FrameLayout(context)
    private val bubbleIcon = ImageView(context)
    private val quickCloseBadge = TextView(context)
    val menuContainer = LinearLayout(context)

    // Action buttons in the expanded bar (TAREFA 1, 2 & 3)
    lateinit var btnRecord: ImageButton
        private set
    lateinit var btnPauseResume: ImageButton
        private set
    lateinit var btnStop: ImageButton
        private set
    lateinit var btnBrush: ImageButton
        private set
    lateinit var btnFacecam: ImageButton
        private set
    lateinit var btnCloseBall: ImageButton
        private set

    // Alvo de fechamento ("X" dentro de um círculo vermelho) na parte inferior central da tela
    val closeTargetOverlay = LinearLayout(context)
    private val closeTargetCircle = FrameLayout(context)
    private var isCloseTargetAttached = false

    var isMenuExpanded = false
        private set
    var isRecordingState = false
        private set
    var isPausedState = false
        private set

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    init {
        setupViews()
        setupCloseTargetView()
        setupTouch()
        updateRecordingState(ScreenRecordingService.recordingStatus.value)
    }

    private fun setupViews() {
        // Main circular bubble
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE50914.toInt()) // Sleek crimson red
            setStroke(dpToPx(2), Color.WHITE)
        }
        bubbleContainer.background = bgDrawable
        val bubbleLp = LayoutParams(ballSize, ballSize).apply {
            topMargin = dpToPx(4)
            rightMargin = dpToPx(4)
        }
        bubbleContainer.layoutParams = bubbleLp

        bubbleIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
        bubbleIcon.setColorFilter(Color.WHITE)
        bubbleIcon.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        bubbleContainer.addView(
            bubbleIcon,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        // Botão "X" direto ao lado/topo da bola flutuante para fechar com 1 toque
        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1E1B2E.toInt())
            setStroke(dpToPx(1), Color.WHITE)
        }
        quickCloseBadge.background = badgeBg
        quickCloseBadge.text = "✕"
        quickCloseBadge.setTextColor(Color.WHITE)
        quickCloseBadge.textSize = 11f
        quickCloseBadge.typeface = Typeface.DEFAULT_BOLD
        quickCloseBadge.gravity = Gravity.CENTER
        quickCloseBadge.contentDescription = "Fechar Bola Flutuante"
        val badgeSize = dpToPx(22)
        val badgeLp = LayoutParams(badgeSize, badgeSize).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        quickCloseBadge.setOnClickListener {
            onCloseBall()
        }

        bubbleWrapper.addView(bubbleContainer)
        bubbleWrapper.addView(quickCloseBadge, badgeLp)

        // Menu panel
        menuContainer.orientation = LinearLayout.HORIZONTAL
        menuContainer.gravity = Gravity.CENTER_VERTICAL
        val menuBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(28).toFloat()
            setColor(0xEE1E1B2E.toInt())
            setStroke(dpToPx(1), 0x44FFFFFF)
        }
        menuContainer.background = menuBg
        menuContainer.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        menuContainer.visibility = View.GONE

        // 1. Botão de Gravar (Record) - visível quando !isRecording
        btnRecord = createRecordButton {
            onStartRecording()
            collapseMenu()
        }

        // 2. Botão Dinâmico de Pausar / Continuar (Pause ⇄ Resume) - ativo quando isRecording == true
        btnPauseResume = createActionButton(
            iconRes = android.R.drawable.ic_media_pause,
            contentDesc = "Pausar Gravação"
        ) {
            val status = ScreenRecordingService.recordingStatus.value
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = if (status is RecordingStatus.Paused) {
                    ScreenRecordingService.ACTION_RESUME
                } else {
                    ScreenRecordingService.ACTION_PAUSE
                }
            }
            context.startService(intent)
        }

        // 3. Botão de Parar (Stop) - quadrado de Stop, ativo quando isRecording == true
        btnStop = createStopButton {
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_STOP
            }
            context.startService(intent)
            collapseMenu()
        }

        // 4. Pincel / Desenho na tela (Edit / Brush)
        btnBrush = createActionButton(
            iconRes = android.R.drawable.ic_menu_edit,
            contentDesc = "Pincel na Tela"
        ) {
            onToggleBrush()
            collapseMenu()
        }

        // 5. Câmera / Facecam (Camera)
        btnFacecam = createActionButton(
            iconRes = android.R.drawable.ic_menu_camera,
            contentDesc = "Facecam"
        ) {
            onToggleFacecam()
            collapseMenu()
        }

        // 6. Fechar Balão Flutuante (Close / X vermelho)
        btnCloseBall = createCloseMenuButton {
            collapseMenu()
            onCloseBall()
        }

        // TAREFA 1 & 3: Apenas os botões necessários (sem FastRewind/<< e sem Crosshair/Compass)
        menuContainer.addView(btnRecord)
        menuContainer.addView(btnPauseResume)
        menuContainer.addView(btnStop)
        menuContainer.addView(btnBrush)
        menuContainer.addView(btnFacecam)
        menuContainer.addView(btnCloseBall)

        val rootRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bubbleWrapper)
            addView(menuContainer)
        }

        addView(rootRow)
    }

    /**
     * TAREFA 2: Atualiza dinamicamente os botões de Gravar, Pausar/Continuar e Parar
     * em tempo real de acordo com o estado do ScreenRecordingService.
     */
    fun updateRecordingState(status: RecordingStatus) {
        val activeRecording = status is RecordingStatus.Recording ||
            status is RecordingStatus.Paused ||
            status is RecordingStatus.Countdown
        val paused = status is RecordingStatus.Paused

        isRecordingState = activeRecording
        isPausedState = paused

        if (!activeRecording) {
            // Não está gravando: exibe botão Gravar (Record) e oculta Pausar/Continuar e Parar
            btnRecord.visibility = View.VISIBLE
            btnPauseResume.visibility = View.GONE
            btnStop.visibility = View.GONE
        } else {
            // Está gravando (ou pausado): oculta botão Gravar e exibe Pausar/Continuar + Parar
            btnRecord.visibility = View.GONE
            btnPauseResume.visibility = View.VISIBLE
            btnStop.visibility = View.VISIBLE

            if (paused) {
                // Enquanto estiver pausado (isPaused): exibe ícone Play ("Continuar")
                btnPauseResume.setImageResource(android.R.drawable.ic_media_play)
                btnPauseResume.setColorFilter(0xFF00E676.toInt()) // Verde destaque para retomar
                btnPauseResume.contentDescription = "Continuar Gravação"
            } else {
                // Enquanto estiver gravando (!isPaused): exibe ícone Pause ("Pausar")
                btnPauseResume.setImageResource(android.R.drawable.ic_media_pause)
                btnPauseResume.setColorFilter(Color.WHITE)
                btnPauseResume.contentDescription = "Pausar Gravação"
            }
        }
    }

    /**
     * Sincroniza o destaque visual dos botões Pincel e Facecam entre o Balão Flutuante e a Notificação.
     */
    fun updateToolStates(brushActive: Boolean, facecamActive: Boolean) {
        btnBrush.setColorFilter(if (brushActive) 0xFF00E5FF.toInt() else Color.WHITE)
        btnFacecam.setColorFilter(if (facecamActive) 0xFF00E5FF.toInt() else Color.WHITE)
    }

    /**
     * Configura o alvo de fechamento (ícone "X" dentro de um círculo vermelho)
     * exibido na parte inferior central da tela durante o arraste.
     */
    private fun setupCloseTargetView() {
        closeTargetOverlay.orientation = LinearLayout.VERTICAL
        closeTargetOverlay.gravity = Gravity.CENTER_HORIZONTAL
        closeTargetOverlay.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))

        val targetCircleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE50914.toInt()) // Círculo vermelho
            setStroke(dpToPx(2), Color.WHITE)
        }
        closeTargetCircle.background = targetCircleBg
        closeTargetCircle.layoutParams = LinearLayout.LayoutParams(closeTargetSize, closeTargetSize)

        val closeIcon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            val pad = dpToPx(16)
            setPadding(pad, pad, pad, pad)
        }
        closeTargetCircle.addView(
            closeIcon,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )

        val label = TextView(context).apply {
            text = "Arraste aqui para fechar"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(4))
        }

        closeTargetOverlay.addView(closeTargetCircle)
        closeTargetOverlay.addView(label)
    }

    private fun createRecordButton(onClick: () -> Unit): ImageButton {
        val recRingBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33FF1744)
            setStroke(dpToPx(1), 0xFFFF5252.toInt())
        }
        return ImageButton(context).apply {
            setImageResource(R.drawable.ic_notification_rec)
            background = recRingBg
            setColorFilter(0xFFFF1744.toInt()) // Círculo vermelho de gravação
            contentDescription = "Iniciar Gravação"
            val size = dpToPx(38)
            val lp = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = dpToPx(4)
                rightMargin = dpToPx(4)
            }
            layoutParams = lp
            val pad = dpToPx(8)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
    }

    private fun createStopButton(onClick: () -> Unit): ImageButton {
        val stopBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33FF5252)
            setStroke(dpToPx(1), 0xFFFF5252.toInt())
        }
        return ImageButton(context).apply {
            setImageResource(R.drawable.ic_stop_square)
            background = stopBg
            setColorFilter(0xFFFF5252.toInt())
            contentDescription = "Parar Gravação"
            val size = dpToPx(38)
            val lp = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = dpToPx(4)
                rightMargin = dpToPx(4)
            }
            layoutParams = lp
            val pad = dpToPx(8)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
    }

    private fun createActionButton(
        iconRes: Int,
        contentDesc: String,
        onClick: () -> Unit
    ): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            contentDescription = contentDesc
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
    }

    private fun createCloseMenuButton(onClick: () -> Unit): ImageButton {
        val closeCircleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE50914.toInt())
            setStroke(dpToPx(1), Color.WHITE)
        }
        return ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = closeCircleBg
            setColorFilter(Color.WHITE)
            contentDescription = "Fechar Bola Flutuante"
            val size = dpToPx(36)
            val lp = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = dpToPx(6)
                rightMargin = dpToPx(2)
            }
            layoutParams = lp
            val pad = dpToPx(8)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        bubbleContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        if (!isDragging) {
                            isDragging = true
                            collapseMenu()
                            showCloseTargetOverlay()
                        }
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(this@FloatingBallView, layoutParams)
                        } catch (_: Exception) {}

                        // Destaca visualmente o círculo vermelho quando a bola está sobre a área de fechamento
                        val hoveringClose = isInsideCloseTarget(event.rawX, event.rawY)
                        highlightCloseTarget(hoveringClose)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        val droppedInCloseZone = isInsideCloseTarget(event.rawX, event.rawY)
                        hideCloseTargetOverlay()
                        isDragging = false
                        if (droppedInCloseZone) {
                            onCloseBall()
                        }
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Verifica se as coordenadas (rawX, rawY) estão sobre a área de fechamento inferior central.
     */
    fun isInsideCloseTarget(rawX: Float, rawY: Float): Boolean {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        // Centro estimado do alvo inferior central
        var targetCenterX = screenWidth / 2f
        var targetCenterY = screenHeight - dpToPx(48) - (closeTargetSize / 2f)

        val loc = IntArray(2)
        closeTargetCircle.getLocationOnScreen(loc)
        if (loc[0] > 0 || loc[1] > 0) {
            targetCenterX = loc[0] + (closeTargetCircle.width.takeIf { it > 0 } ?: closeTargetSize) / 2f
            targetCenterY = loc[1] + (closeTargetCircle.height.takeIf { it > 0 } ?: closeTargetSize) / 2f
        }

        val hitRadius = dpToPx(96).toFloat()
        val distance = hypot(rawX - targetCenterX, rawY - targetCenterY)
        return distance <= hitRadius
    }

    private fun highlightCloseTarget(active: Boolean) {
        val scale = if (active) 1.22f else 1.0f
        closeTargetCircle.scaleX = scale
        closeTargetCircle.scaleY = scale
    }

    private fun showCloseTargetOverlay() {
        if (!isCloseTargetAttached) {
            try {
                windowManager.addView(closeTargetOverlay, closeTargetLayoutParams)
                isCloseTargetAttached = true
            } catch (_: Exception) {}
        }
    }

    fun hideCloseTargetOverlay() {
        if (isCloseTargetAttached) {
            try {
                windowManager.removeView(closeTargetOverlay)
            } catch (_: Exception) {}
            isCloseTargetAttached = false
        }
    }

    fun toggleMenu() {
        isMenuExpanded = !isMenuExpanded
        if (isMenuExpanded) {
            updateRecordingState(ScreenRecordingService.recordingStatus.value)
        }
        menuContainer.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE
    }

    fun collapseMenu() {
        isMenuExpanded = false
        menuContainer.visibility = View.GONE
    }

    fun setBallAlpha(alphaValue: Float) {
        alpha = alphaValue
    }

    private fun dpToPx(dp: Int): Int {
        val scale = context.resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }
}


