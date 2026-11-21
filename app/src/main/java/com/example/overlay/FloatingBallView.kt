package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class FloatingBallView(
    context: Context,
    private val windowManager: WindowManager,
    private val onStartRecording: () -> Unit,
    private val onToggleBrush: () -> Unit,
    private val onToggleFacecam: () -> Unit,
    private val onCloseBall: () -> Unit,
    initialSizePercent: Int = SettingsPreferences.DEFAULT_BUBBLE_SIZE_PERCENT
) : FrameLayout(context) {

    var currentSizePercent: Int = SettingsPreferences.sanitizeSizePercent(initialSizePercent)
        private set

    private val bubbleScale: Float
        get() = currentSizePercent / 100f

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
    val bubbleContainer = FrameLayout(context)
    private val bubbleIcon = ImageView(context)
    val quickCloseBadge = TextView(context)
    val menuContainer = LinearLayout(context)

    // Action buttons in the expanded bar
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
        applySizePercent(currentSizePercent)
        setupCloseTargetView()
        setupTouch()
        updateRecordingState(ScreenRecordingService.recordingStatus.value)
    }

    private fun setupViews() {
        bubbleIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
        bubbleIcon.setColorFilter(Color.WHITE)
        bubbleIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        bubbleContainer.addView(
            bubbleIcon,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        quickCloseBadge.text = "✕"
        quickCloseBadge.setTextColor(Color.WHITE)
        quickCloseBadge.typeface = Typeface.DEFAULT_BOLD
        quickCloseBadge.gravity = Gravity.CENTER
        quickCloseBadge.contentDescription = "Fechar Bola Flutuante"
        quickCloseBadge.setOnClickListener {
            onCloseBall()
        }

        bubbleWrapper.addView(bubbleContainer)
        bubbleWrapper.addView(quickCloseBadge)

        menuContainer.orientation = LinearLayout.HORIZONTAL
        menuContainer.gravity = Gravity.CENTER_VERTICAL
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
     * TAREFA 3: Redimensionamento proporcional real em dp/sp (sem scaleX/scaleY que corta bordas no WindowManager).
     * Atualiza bolinha recolhida, ícone, badge "✕", container do menu expandido e todos os botões de ação,
     * recalculando WRAP_CONTENT + clamping seguro dentro dos limites da tela.
     */
    fun applySizePercent(sizePercent: Int) {
        currentSizePercent = SettingsPreferences.sanitizeSizePercent(sizePercent)
        val s = bubbleScale

        val ballSizePx = scaledDp(56, s)
        val bubbleMargin = scaledDp(4, s)
        val bubbleStroke = scaledDp(2, s).coerceAtLeast(1)
        val bubbleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE50914.toInt())
            setStroke(bubbleStroke, Color.WHITE)
        }
        bubbleContainer.background = bubbleBg
        bubbleContainer.layoutParams = LayoutParams(ballSizePx, ballSizePx).apply {
            topMargin = bubbleMargin
            rightMargin = bubbleMargin
        }
        val iconPad = scaledDp(12, s)
        bubbleIcon.setPadding(iconPad, iconPad, iconPad, iconPad)

        // Badge "✕" na borda superior direita da bola
        val badgeSizePx = scaledDp(22, s)
        val badgeStroke = scaledDp(1, s).coerceAtLeast(1)
        quickCloseBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1E1B2E.toInt())
            setStroke(badgeStroke, Color.WHITE)
        }
        quickCloseBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * s)
        quickCloseBadge.layoutParams = LayoutParams(badgeSizePx, badgeSizePx).apply {
            gravity = Gravity.TOP or Gravity.END
        }

        // Menu expandido
        val menuCorner = scaledDp(28, s).toFloat()
        val menuPadH = scaledDp(8, s)
        val menuPadV = scaledDp(4, s)
        menuContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = menuCorner
            setColor(0xEE1E1B2E.toInt())
            setStroke(scaledDp(1, s).coerceAtLeast(1), 0x44FFFFFF)
        }
        menuContainer.setPadding(menuPadH, menuPadV, menuPadH, menuPadV)
        menuContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = scaledDp(4, s)
        }

        // Botões de ação proporcionais
        val actionBtnSize = scaledDp(38, s)
        val actionMarginH = scaledDp(4, s)
        val actionPad = scaledDp(8, s)
        val actionStroke = scaledDp(1, s).coerceAtLeast(1)

        btnRecord.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33FF1744)
            setStroke(actionStroke, 0xFFFF5252.toInt())
        }
        applyButtonDimensions(btnRecord, actionBtnSize, actionMarginH, actionMarginH, actionPad)

        btnStop.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33FF5252)
            setStroke(actionStroke, 0xFFFF5252.toInt())
        }
        applyButtonDimensions(btnStop, actionBtnSize, actionMarginH, actionMarginH, actionPad)

        applyButtonDimensions(btnPauseResume, actionBtnSize, actionMarginH, actionMarginH, actionPad)
        applyButtonDimensions(btnBrush, actionBtnSize, actionMarginH, actionMarginH, actionPad)
        applyButtonDimensions(btnFacecam, actionBtnSize, actionMarginH, actionMarginH, actionPad)

        val closeBtnSize = scaledDp(36, s)
        btnCloseBall.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE50914.toInt())
            setStroke(actionStroke, Color.WHITE)
        }
        applyButtonDimensions(btnCloseBall, closeBtnSize, scaledDp(6, s), scaledDp(2, s), actionPad)

        requestLayout()
        clampPositionToScreen()
        safeUpdateWindowLayout()
    }

    private fun applyButtonDimensions(
        button: ImageButton,
        sizePx: Int,
        marginLeftPx: Int,
        marginRightPx: Int,
        paddingPx: Int
    ) {
        button.layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
            leftMargin = marginLeftPx
            rightMargin = marginRightPx
        }
        button.scaleType = ImageView.ScaleType.FIT_CENTER
        button.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
    }

    /**
     * Atualiza dinamicamente os botões de Gravar, Pausar/Continuar e Parar
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
            btnRecord.visibility = View.VISIBLE
            btnPauseResume.visibility = View.GONE
            btnStop.visibility = View.GONE
        } else {
            btnRecord.visibility = View.GONE
            btnPauseResume.visibility = View.VISIBLE
            btnStop.visibility = View.VISIBLE

            if (paused) {
                btnPauseResume.setImageResource(android.R.drawable.ic_media_play)
                btnPauseResume.setColorFilter(0xFF00E676.toInt())
                btnPauseResume.contentDescription = "Continuar Gravação"
            } else {
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

    private fun setupCloseTargetView() {
        closeTargetOverlay.orientation = LinearLayout.VERTICAL
        closeTargetOverlay.gravity = Gravity.CENTER_HORIZONTAL
        closeTargetOverlay.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))

        val targetCircleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE50914.toInt())
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
        return ImageButton(context).apply {
            setImageResource(R.drawable.ic_notification_rec)
            setColorFilter(0xFFFF1744.toInt())
            contentDescription = "Iniciar Gravação"
            setOnClickListener { onClick() }
        }
    }

    private fun createStopButton(onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(R.drawable.ic_stop_square)
            setColorFilter(0xFFFF5252.toInt())
            contentDescription = "Parar Gravação"
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
            setOnClickListener { onClick() }
        }
    }

    private fun createCloseMenuButton(onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            contentDescription = "Fechar Bola Flutuante"
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
                        clampPositionToScreen()
                        safeUpdateWindowLayout()

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

    fun clampPositionToScreen() {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(dpToPx(320))
        val screenH = metrics.heightPixels.coerceAtLeast(dpToPx(320))

        val s = bubbleScale
        val estBubbleTotal = scaledDp(60, s)
        val estWidth = if (width > 0) {
            width
        } else if (isMenuExpanded) {
            estBubbleTotal + scaledDp(240, s)
        } else {
            estBubbleTotal
        }
        val estHeight = if (height > 0) height else estBubbleTotal

        layoutParams.x = layoutParams.x.coerceIn(0, (screenW - estWidth).coerceAtLeast(0))
        layoutParams.y = layoutParams.y.coerceIn(0, (screenH - estHeight).coerceAtLeast(0))
    }

    private fun safeUpdateWindowLayout() {
        if (isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(this, layoutParams)
            } catch (_: Exception) {}
        }
    }

    /**
     * Verifica se as coordenadas (rawX, rawY) estão sobre a área de fechamento inferior central.
     */
    fun isInsideCloseTarget(rawX: Float, rawY: Float): Boolean {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

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
        clampPositionToScreen()
        safeUpdateWindowLayout()
    }

    fun collapseMenu() {
        isMenuExpanded = false
        menuContainer.visibility = View.GONE
        safeUpdateWindowLayout()
    }

    fun setBallAlpha(alphaValue: Float) {
        alpha = alphaValue.coerceIn(0.2f, 1.0f)
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    private fun scaledDp(baseDp: Int, scale: Float): Int {
        val density = context.resources.displayMetrics.density
        return (baseDp * scale * density).roundToInt().coerceAtLeast(1)
    }
}
