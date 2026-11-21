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
import android.view.ViewGroup
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

@SuppressLint("ViewConstructor")
class FloatingBallView(
    context: Context,
    private val windowManager: WindowManager,
    private val onStartRecording: () -> Unit,
    private val onToggleBrush: () -> Unit,
    private val onToggleFacecam: () -> Unit,
    private val onCloseBall: () -> Unit,
    initialSizePercent: Int = SettingsPreferences.DEFAULT_BUBBLE_SIZE_PERCENT,
    private val metricsProvider: (() -> OverlayDisplaySnapshot)? = null
) : FrameLayout(context) {

    var currentSizePercent: Int = SettingsPreferences.sanitizeSizePercent(initialSizePercent)
        private set

    private val bubbleScale: Float
        get() = currentSizePercent / 100f

    /**
     * Escala efetiva aplicada ao menu expandido após a proteção contra estouro de 94% da largura.
     */
    var effectiveMenuScale: Float = bubbleScale
        private set

    /**
     * Indica se o menu expandido foi automaticamente organizado em 2 linhas para evitar recortes
     * em telas estreitas com escalas altas (ex.: 220%–300%).
     */
    var isMenuTwoRows: Boolean = false
        private set

    private fun currentMetrics(): OverlayDisplaySnapshot {
        return metricsProvider?.invoke() ?: OverlayDisplayMetrics.readMetrics(context, windowManager)
    }

    private val closeTargetSize: Int
        get() = currentMetrics().dpToPx(68)

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
        y = currentMetrics().dpToPx(48)
    }

    private val rootContainer = LinearLayout(context)
    private val bubbleWrapper = FrameLayout(context)
    val bubbleContainer = FrameLayout(context)
    private val bubbleIcon = ImageView(context)
    val quickCloseBadge = TextView(context)
    val menuContainer = LinearLayout(context)
    val menuRowTop = LinearLayout(context)
    val menuRowBottom = LinearLayout(context)

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

    private val allMenuButtons: List<ImageButton>
        get() = listOf(btnRecord, btnPauseResume, btnStop, btnBrush, btnFacecam, btnCloseBall)

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
        updateRecordingState(ScreenRecordingService.recordingStatus.value)
        applySizePercent(currentSizePercent)
        setupCloseTargetView()
        setupTouch()
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
        quickCloseBadge.includeFontPadding = false
        quickCloseBadge.contentDescription = "Fechar Bola Flutuante"
        quickCloseBadge.setOnClickListener {
            onCloseBall()
        }

        bubbleWrapper.addView(bubbleContainer)
        bubbleWrapper.addView(quickCloseBadge)

        menuContainer.orientation = LinearLayout.HORIZONTAL
        menuContainer.gravity = Gravity.CENTER_VERTICAL
        menuContainer.visibility = View.GONE

        menuRowTop.orientation = LinearLayout.HORIZONTAL
        menuRowTop.gravity = Gravity.CENTER_VERTICAL

        menuRowBottom.orientation = LinearLayout.HORIZONTAL
        menuRowBottom.gravity = Gravity.CENTER_VERTICAL

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

        arrangeMenuButtons(twoRows = false)

        rootContainer.orientation = LinearLayout.HORIZONTAL
        rootContainer.gravity = Gravity.CENTER_VERTICAL
        rootContainer.addView(bubbleWrapper)
        rootContainer.addView(menuContainer)

        addView(rootContainer)
    }

    /**
     * Reorganiza os 6 botões de ação entre:
     * - 1 linha horizontal direta em menuContainer (quando cabe dentro de 94% da largura da tela)
     * - 2 linhas equilibradas (menuRowTop + menuRowBottom) em telas estreitas com escalas altas (ex.: 220%–300%)
     */
    private fun arrangeMenuButtons(twoRows: Boolean) {
        allMenuButtons.forEach { btn ->
            (btn.parent as? ViewGroup)?.removeView(btn)
        }
        (menuRowTop.parent as? ViewGroup)?.removeView(menuRowTop)
        (menuRowBottom.parent as? ViewGroup)?.removeView(menuRowBottom)
        menuContainer.removeAllViews()
        menuRowTop.removeAllViews()
        menuRowBottom.removeAllViews()

        if (!twoRows) {
            menuContainer.orientation = LinearLayout.HORIZONTAL
            menuContainer.gravity = Gravity.CENTER_VERTICAL
            allMenuButtons.forEach { menuContainer.addView(it) }
            isMenuTwoRows = false
        } else {
            menuContainer.orientation = LinearLayout.VERTICAL
            menuContainer.gravity = Gravity.CENTER_HORIZONTAL

            val visibleButtons = allMenuButtons.filter { it.visibility == View.VISIBLE }
            val hiddenButtons = allMenuButtons.filter { it.visibility != View.VISIBLE }
            val topCount = (visibleButtons.size + 1) / 2

            visibleButtons.forEachIndexed { idx, btn ->
                if (idx < topCount) {
                    menuRowTop.addView(btn)
                } else {
                    menuRowBottom.addView(btn)
                }
            }
            // Mantém botões GONE anexados na árvore sem ocupar espaço
            hiddenButtons.forEach { menuRowBottom.addView(it) }

            menuContainer.addView(menuRowTop)
            menuContainer.addView(menuRowBottom)
            isMenuTwoRows = true
        }
    }

    /**
     * TAREFA 2 & 3: Redimensionamento proporcional real usando as DisplayMetrics reais do display alvo
     * (sem scaleX/scaleY). Escala ergonomicamente o badge "✕" de fechar (150%–300%) e aplica
     * quebra automática em 2 linhas + limite de segurança de 94% da largura para o menu expandido.
     */
    fun applySizePercent(sizePercent: Int) {
        currentSizePercent = SettingsPreferences.sanitizeSizePercent(sizePercent)
        val metrics = currentMetrics()
        val s = bubbleScale
        val maxAllowedWidth = metrics.maxAllowedWidthPx

        // 1. Bola principal (mantém escala cheia escolhida pelo usuário, limitada com segurança a 90% da tela)
        val rawBallTotalPx = metrics.scaledDp(62, s)
        val effectiveBallScale = if (rawBallTotalPx > (maxAllowedWidth * 0.90f)) {
            (s * ((maxAllowedWidth * 0.90f) / rawBallTotalPx)).coerceAtLeast(0.8f)
        } else {
            s
        }

        val ballSizePx = metrics.scaledDp(56, effectiveBallScale)
        val bubbleMargin = metrics.scaledDp(6, effectiveBallScale)
        val bubbleStroke = metrics.scaledDp(2, effectiveBallScale).coerceAtLeast(1)
        bubbleContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE50914.toInt())
            setStroke(bubbleStroke, Color.WHITE)
        }
        bubbleContainer.layoutParams = LayoutParams(ballSizePx, ballSizePx).apply {
            topMargin = bubbleMargin
            rightMargin = bubbleMargin
        }
        val iconPad = metrics.scaledDp(12, effectiveBallScale)
        bubbleIcon.setPadding(iconPad, iconPad, iconPad, iconPad)

        // 2. Badge "✕" na borda superior direita com ergonomia ampliada em escalas altas (150%–300%)
        val badgeBaseDp = if (currentSizePercent >= 150) 26 else 24
        val badgeSizePx = metrics.scaledDp(badgeBaseDp, effectiveBallScale)
        val badgeStroke = metrics.scaledDp(1, effectiveBallScale).coerceAtLeast(1)
        quickCloseBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1E1B2E.toInt())
            setStroke(badgeStroke, Color.WHITE)
        }
        quickCloseBadge.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            metrics.scaledSpPx(13f, effectiveBallScale)
        )
        quickCloseBadge.layoutParams = LayoutParams(badgeSizePx, badgeSizePx).apply {
            gravity = Gravity.TOP or Gravity.END
        }

        // 3. Cálculo do menu expandido com quebra em 2 linhas e proteção de 94% de largura
        val visibleCount = allMenuButtons.count { it.visibility == View.VISIBLE }.coerceAtLeast(4)
        val ballTotalWidthPx = ballSizePx + bubbleMargin
        val gapBetweenBallAndMenuPx = metrics.scaledDp(4, effectiveBallScale)

        // Largura estimada de 1 botão = 38dp + 4dp margens horizontais = 42dp; padding horizontal do menu = 12dp
        val singleRowMenuBaseDp = (visibleCount * 42) + 12
        val singleRowCombinedPx = ballTotalWidthPx + gapBetweenBallAndMenuPx + metrics.scaledDp(singleRowMenuBaseDp, s)

        val shouldWrapTwoRows = singleRowCombinedPx > maxAllowedWidth
        arrangeMenuButtons(twoRows = shouldWrapTwoRows)

        val maxButtonsPerRow = if (shouldWrapTwoRows) (visibleCount + 1) / 2 else visibleCount
        val rowMenuBaseDp = (maxButtonsPerRow * 42) + 12
        val unscaledMenuWidthPx = metrics.scaledDp(rowMenuBaseDp, s)

        // Decide se o menu fica ao lado da bola (HORIZONTAL) ou logo abaixo (VERTICAL) quando em 2 linhas
        val availableBesideBallPx = maxAllowedWidth - ballTotalWidthPx - gapBetweenBallAndMenuPx
        val stackVertically = shouldWrapTwoRows && availableBesideBallPx < (unscaledMenuWidthPx * 0.65f)

        val menuMaxWidthBudgetPx = if (stackVertically) {
            maxAllowedWidth
        } else {
            availableBesideBallPx.coerceAtLeast(metrics.dpToPx(120))
        }

        val menuScale = if (unscaledMenuWidthPx > menuMaxWidthBudgetPx) {
            (s * (menuMaxWidthBudgetPx.toFloat() / unscaledMenuWidthPx.toFloat())).coerceAtLeast(0.65f)
        } else {
            s
        }
        effectiveMenuScale = menuScale

        rootContainer.orientation = if (stackVertically) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        rootContainer.gravity = if (stackVertically) Gravity.START else Gravity.CENTER_VERTICAL

        val menuCorner = metrics.scaledDp(if (shouldWrapTwoRows) 20 else 28, menuScale).toFloat()
        val menuPadH = metrics.scaledDp(6, menuScale)
        val menuPadV = metrics.scaledDp(if (shouldWrapTwoRows) 6 else 4, menuScale)
        menuContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = menuCorner
            setColor(0xEE1E1B2E.toInt())
            setStroke(metrics.scaledDp(1, menuScale).coerceAtLeast(1), 0x44FFFFFF)
        }
        menuContainer.setPadding(menuPadH, menuPadV, menuPadH, menuPadV)
        menuContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (stackVertically) {
                topMargin = metrics.scaledDp(6, menuScale)
                leftMargin = 0
            } else {
                topMargin = 0
                leftMargin = metrics.scaledDp(4, menuScale)
            }
        }

        if (shouldWrapTwoRows) {
            val rowGap = metrics.scaledDp(6, menuScale)
            menuRowTop.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = rowGap
            }
            menuRowBottom.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 4. Dimensões reais dos botões de ação usando menuScale
        val actionBtnSize = metrics.scaledDp(38, menuScale)
        val actionMarginH = metrics.scaledDp(2, menuScale)
        val actionPad = metrics.scaledDp(8, menuScale)
        val actionStroke = metrics.scaledDp(1, menuScale).coerceAtLeast(1)

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

        val closeBtnSize = metrics.scaledDp(36, menuScale)
        btnCloseBall.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE50914.toInt())
            setStroke(actionStroke, Color.WHITE)
        }
        applyButtonDimensions(
            btnCloseBall,
            closeBtnSize,
            metrics.scaledDp(2, menuScale),
            metrics.scaledDp(2, menuScale),
            actionPad
        )

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

        val recordingChanged = isRecordingState != activeRecording
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

        if (recordingChanged) {
            applySizePercent(currentSizePercent)
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
        val metrics = currentMetrics()
        val targetSize = closeTargetSize
        closeTargetOverlay.orientation = LinearLayout.VERTICAL
        closeTargetOverlay.gravity = Gravity.CENTER_HORIZONTAL
        closeTargetOverlay.setPadding(metrics.dpToPx(16), metrics.dpToPx(16), metrics.dpToPx(16), metrics.dpToPx(16))

        val targetCircleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE50914.toInt())
            setStroke(metrics.dpToPx(2), Color.WHITE)
        }
        closeTargetCircle.background = targetCircleBg
        closeTargetCircle.layoutParams = LinearLayout.LayoutParams(targetSize, targetSize)

        val closeIcon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            val pad = metrics.dpToPx(16)
            setPadding(pad, pad, pad, pad)
        }
        closeTargetCircle.addView(
            closeIcon,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )

        val label = TextView(context).apply {
            text = "Arraste aqui para fechar"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.scaledSpPx(12f, 1f))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(metrics.dpToPx(8), metrics.dpToPx(6), metrics.dpToPx(8), metrics.dpToPx(4))
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
        val metrics = currentMetrics()
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val estBubbleTotal = metrics.scaledDp(62, bubbleScale).coerceAtMost(metrics.maxAllowedWidthPx)
        val estWidth = if (width > 0) {
            width.coerceAtMost(screenW)
        } else if (isMenuExpanded) {
            metrics.maxAllowedWidthPx
        } else {
            estBubbleTotal
        }
        val estHeight = if (height > 0) {
            height.coerceAtMost(screenH)
        } else if (isMenuExpanded && isMenuTwoRows) {
            estBubbleTotal + metrics.scaledDp(110, effectiveMenuScale)
        } else {
            estBubbleTotal
        }

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
        val metrics = currentMetrics()
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()
        val targetSize = closeTargetSize

        var targetCenterX = screenWidth / 2f
        var targetCenterY = screenHeight - metrics.dpToPx(48) - (targetSize / 2f)

        val loc = IntArray(2)
        closeTargetCircle.getLocationOnScreen(loc)
        if (loc[0] > 0 || loc[1] > 0) {
            targetCenterX = loc[0] + (closeTargetCircle.width.takeIf { it > 0 } ?: targetSize) / 2f
            targetCenterY = loc[1] + (closeTargetCircle.height.takeIf { it > 0 } ?: targetSize) / 2f
        }

        val hitRadius = metrics.dpToPx(96).toFloat()
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
            applySizePercent(currentSizePercent)
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
}
