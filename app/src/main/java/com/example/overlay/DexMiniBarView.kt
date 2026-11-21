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
import com.example.model.MiniBarCollapseBehavior
import com.example.model.RecordingStatus
import com.example.model.SettingsPreferences
import com.example.service.ScreenRecordingService
import kotlin.math.hypot

/**
 * Mini Barra Flutuante compacta (Modo DeX e Celular):
 * - Pílula horizontal escura, discreta e arrastável para qualquer canto da tela.
 * - Redimensionamento proporcional real em dp/sp (80% a 300%) usando a densidade real do display alvo
 *   e limitador de segurança de 94% da largura da tela para evitar qualquer corte de botões.
 * - Opacidade configurável (25% a 100%, padrão 90%) e Aba Lateral Recolhida proporcional.
 * - Salva automaticamente a última posição arrastada no SharedPreferences.
 */
@SuppressLint("ViewConstructor")
class DexMiniBarView(
    context: Context,
    private val windowManager: WindowManager,
    initialCollapseBehavior: MiniBarCollapseBehavior,
    private val onTogglePauseResume: () -> Unit,
    private val onStopRecording: () -> Unit,
    private val onHideUntilNotificationTap: () -> Unit,
    initialSizePercent: Int = SettingsPreferences.DEFAULT_MINI_BAR_SIZE_PERCENT,
    initialOpacityPercent: Int = SettingsPreferences.DEFAULT_MINI_BAR_OPACITY_PERCENT,
    private val metricsProvider: (() -> OverlayDisplaySnapshot)? = null
) : FrameLayout(context) {

    var collapseBehavior: MiniBarCollapseBehavior = initialCollapseBehavior
        private set

    var currentSizePercent: Int = SettingsPreferences.sanitizeSizePercent(initialSizePercent)
        private set

    var currentOpacityPercent: Int = SettingsPreferences.sanitizeMiniBarOpacityPercent(initialOpacityPercent)
        private set

    private val requestedPillScale: Float
        get() = currentSizePercent / 100f

    /**
     * Escala efetiva aplicada à Pílula após respeitar o teto de segurança de 94% da largura do display.
     */
    var effectivePillScale: Float = requestedPillScale
        private set

    var isCollapsedToEdgeTab: Boolean = false
        private set

    var isPausedState: Boolean = false
        private set

    private var savedExpandedX: Int = 0
    private var savedExpandedY: Int = 0
    private var collapsedOnRightEdge: Boolean = false

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
        gravity = Gravity.TOP or Gravity.START
        val (initX, initY) = computeInitialPosition()
        x = initX
        y = initY
        savedExpandedX = initX
        savedExpandedY = initY
    }

    val pillContainer = LinearLayout(context)
    val edgeTabContainer = LinearLayout(context)

    val statusDot = View(context)
    val timerText = TextView(context)
    val btnPauseResume = ImageButton(context)
    val btnStop = ImageButton(context)
    val btnCollapseOrHide = ImageButton(context)

    private val edgeStatusDot = View(context)
    private val edgeChevronText = TextView(context)

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    init {
        setupExpandedPill()
        setupCollapsedEdgeTab()
        addView(pillContainer)
        addView(edgeTabContainer)
        applyStyle(currentSizePercent, currentOpacityPercent)
        setupDragTouch(pillContainer, savePositionOnUp = true)
        setupEdgeTabTouch()
        updateRecordingState(ScreenRecordingService.recordingStatus.value)
    }

    private fun computeEffectivePillScale(metrics: OverlayDisplaySnapshot, requested: Float): Float {
        // Largura base estimada da Pílula em 100% (~212dp cobrindo padding, ponto, cronômetro, 3 botões e borda)
        val estimatedNaturalWidthPx = metrics.scaledDp(212, requested)
        val maxAllowedPx = metrics.maxAllowedWidthPx
        return if (estimatedNaturalWidthPx > maxAllowedPx) {
            (requested * (maxAllowedPx.toFloat() / estimatedNaturalWidthPx.toFloat())).coerceAtLeast(0.55f)
        } else {
            requested
        }
    }

    private fun computeInitialPosition(): Pair<Int, Int> {
        val metrics = currentMetrics()
        val effScale = computeEffectivePillScale(metrics, requestedPillScale)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val estimatedBarWidth = metrics.scaledDp(212, effScale).coerceAtMost(metrics.maxAllowedWidthPx)
        val estimatedBarHeight = metrics.scaledDp(46, effScale)

        val saved = SettingsPreferences.loadDexMiniBarPosition(context)
        return if (saved != null) {
            val clampedX = saved.first.coerceIn(0, (screenW - estimatedBarWidth).coerceAtLeast(0))
            val clampedY = saved.second.coerceIn(0, (screenH - estimatedBarHeight).coerceAtLeast(0))
            Pair(clampedX, clampedY)
        } else {
            val centerX = ((screenW - estimatedBarWidth) / 2).coerceAtLeast(metrics.dpToPx(8))
            val topY = metrics.dpToPx(24)
            Pair(centerX, topY)
        }
    }

    private fun setupExpandedPill() {
        pillContainer.orientation = LinearLayout.HORIZONTAL
        pillContainer.gravity = Gravity.CENTER_VERTICAL
        pillContainer.elevation = currentMetrics().dpToPx(8).toFloat()

        statusDot.background = makeCircleDrawable(0xFFFF3B30.toInt())
        pillContainer.addView(statusDot)

        timerText.text = "00:00"
        timerText.setTextColor(Color.WHITE)
        timerText.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        timerText.fontFeatureSettings = "tnum"
        timerText.contentDescription = "Tempo de gravação"
        pillContainer.addView(timerText)

        btnPauseResume.setImageResource(R.drawable.ic_notif_pause)
        btnPauseResume.setColorFilter(Color.WHITE)
        btnPauseResume.scaleType = ImageView.ScaleType.FIT_CENTER
        btnPauseResume.contentDescription = "Pausar Gravação"
        btnPauseResume.setOnClickListener {
            onTogglePauseResume()
        }
        pillContainer.addView(btnPauseResume)

        btnStop.setImageResource(R.drawable.ic_stop_square)
        btnStop.setColorFilter(Color.WHITE)
        btnStop.scaleType = ImageView.ScaleType.FIT_CENTER
        btnStop.contentDescription = "Parar Gravação"
        btnStop.setOnClickListener {
            onStopRecording()
        }
        pillContainer.addView(btnStop)

        btnCollapseOrHide.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        btnCollapseOrHide.setColorFilter(0xCCCFD8DC.toInt())
        btnCollapseOrHide.scaleType = ImageView.ScaleType.FIT_CENTER
        btnCollapseOrHide.contentDescription = collapseButtonContentDescription()
        btnCollapseOrHide.setOnClickListener {
            handleCollapseOrHideClick()
        }
        pillContainer.addView(btnCollapseOrHide)
    }

    private fun setupCollapsedEdgeTab() {
        edgeTabContainer.orientation = LinearLayout.VERTICAL
        edgeTabContainer.gravity = Gravity.CENTER
        edgeTabContainer.visibility = View.GONE
        edgeTabContainer.contentDescription = "Expandir Mini Barra Flutuante"
        edgeTabContainer.isClickable = true

        edgeStatusDot.background = makeCircleDrawable(0xFFFF3B30.toInt())
        edgeTabContainer.addView(edgeStatusDot)

        edgeChevronText.text = "›"
        edgeChevronText.setTextColor(Color.WHITE)
        edgeChevronText.typeface = Typeface.DEFAULT_BOLD
        edgeChevronText.gravity = Gravity.CENTER
        edgeTabContainer.addView(
            edgeChevronText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /**
     * TAREFA 2 & 3: Aplica escala proporcional real em dp/sp com base nas DisplayMetrics reais
     * do display alvo e teto de segurança de 94% da largura da tela, mantendo WRAP_CONTENT
     * e fazendo clamp seguro de (x, y) dentro das bordas.
     */
    fun applyStyle(sizePercent: Int, opacityPercent: Int) {
        currentSizePercent = SettingsPreferences.sanitizeSizePercent(sizePercent)
        currentOpacityPercent = SettingsPreferences.sanitizeMiniBarOpacityPercent(opacityPercent)

        val metrics = currentMetrics()
        val s = computeEffectivePillScale(metrics, requestedPillScale)
        effectivePillScale = s

        val userAlpha = currentOpacityPercent / 100f
        val edgeAlpha = SettingsPreferences.computeEdgeTabAlpha(currentOpacityPercent)

        // 1. Pílula expandida
        pillContainer.alpha = userAlpha
        pillContainer.elevation = metrics.dpToPx(8).toFloat()
        pillContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = metrics.scaledDp(24, s).toFloat()
            setColor(0xEB141820.toInt())
            setStroke(metrics.scaledDp(1, s).coerceAtLeast(1), 0x44FFFFFF)
        }
        pillContainer.setPadding(
            metrics.scaledDp(12, s),
            metrics.scaledDp(6, s),
            metrics.scaledDp(8, s),
            metrics.scaledDp(6, s)
        )

        val dotSize = metrics.scaledDp(10, s)
        statusDot.layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginEnd = metrics.scaledDp(8, s)
        }

        timerText.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.scaledSpPx(13f, s))
        timerText.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = metrics.scaledDp(10, s)
        }

        val actionSize = metrics.scaledDp(32, s)
        val actionPad = metrics.scaledDp(7, s)
        val actionGap = metrics.scaledDp(6, s)
        btnPauseResume.background = makeCircleDrawable(0xFF283040.toInt())
        btnPauseResume.layoutParams = LinearLayout.LayoutParams(actionSize, actionSize).apply {
            marginEnd = actionGap
        }
        btnPauseResume.setPadding(actionPad, actionPad, actionPad, actionPad)

        btnStop.background = makeCircleDrawable(0xFFE50914.toInt())
        btnStop.layoutParams = LinearLayout.LayoutParams(actionSize, actionSize).apply {
            marginEnd = actionGap
        }
        btnStop.setPadding(actionPad, actionPad, actionPad, actionPad)

        val collapseSize = metrics.scaledDp(28, s)
        val collapsePad = metrics.scaledDp(7, s)
        btnCollapseOrHide.background = makeCircleDrawable(0x24FFFFFF)
        btnCollapseOrHide.layoutParams = LinearLayout.LayoutParams(collapseSize, collapseSize)
        btnCollapseOrHide.setPadding(collapsePad, collapsePad, collapsePad, collapsePad)

        // 2. Aba Lateral Recolhida proporcional e discreta (usa a escala desejada limitada a 35% da largura da tela)
        val rawTabWidthPx = metrics.scaledDp(28, requestedPillScale)
        val maxTabWidthPx = (metrics.widthPixels * 0.35f).toInt().coerceAtLeast(metrics.dpToPx(24))
        val tabScale = if (rawTabWidthPx > maxTabWidthPx) {
            (requestedPillScale * (maxTabWidthPx.toFloat() / rawTabWidthPx.toFloat())).coerceAtLeast(0.8f)
        } else {
            requestedPillScale
        }

        edgeTabContainer.alpha = edgeAlpha
        edgeTabContainer.setPadding(
            metrics.scaledDp(6, tabScale),
            metrics.scaledDp(8, tabScale),
            metrics.scaledDp(6, tabScale),
            metrics.scaledDp(8, tabScale)
        )
        val edgeDotSize = metrics.scaledDp(7, tabScale)
        edgeStatusDot.layoutParams = LinearLayout.LayoutParams(edgeDotSize, edgeDotSize).apply {
            bottomMargin = metrics.scaledDp(4, tabScale)
        }
        edgeChevronText.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.scaledSpPx(14f, tabScale))
        applyEdgeTabBackground(onRightEdge = collapsedOnRightEdge, tabScale = tabScale, metrics = metrics)

        requestLayout()
        clampPositionToScreen()
        safeUpdateWindowLayout()
    }

    private fun applyEdgeTabBackground(
        onRightEdge: Boolean,
        tabScale: Float = effectivePillScale,
        metrics: OverlayDisplaySnapshot = currentMetrics()
    ) {
        val r = metrics.scaledDp(14, tabScale).toFloat()
        val radii = if (onRightEdge) {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        } else {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        edgeTabContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(0xD9141820.toInt())
            setStroke(metrics.scaledDp(1, tabScale).coerceAtLeast(1), 0x44FFFFFF)
        }
        edgeChevronText.text = if (onRightEdge) "‹" else "›"
    }

    private fun makeCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun collapseButtonContentDescription(): String {
        return when (collapseBehavior) {
            MiniBarCollapseBehavior.COLLAPSE_TO_EDGE_TAB -> "Recolher Mini Barra em aba lateral"
            MiniBarCollapseBehavior.HIDE_UNTIL_NOTIFICATION_TAP -> "Ocultar Mini Barra até tocar na notificação"
        }
    }

    fun updateCollapseBehavior(behavior: MiniBarCollapseBehavior) {
        collapseBehavior = behavior
        btnCollapseOrHide.contentDescription = collapseButtonContentDescription()
    }

    fun handleCollapseOrHideClick() {
        when (collapseBehavior) {
            MiniBarCollapseBehavior.COLLAPSE_TO_EDGE_TAB -> collapseToEdgeTab()
            MiniBarCollapseBehavior.HIDE_UNTIL_NOTIFICATION_TAP -> onHideUntilNotificationTap()
        }
    }

    fun collapseToEdgeTab() {
        if (isCollapsedToEdgeTab) return
        val metrics = currentMetrics()
        val screenW = metrics.widthPixels
        savedExpandedX = layoutParams.x
        savedExpandedY = layoutParams.y

        val barWidth = if (pillContainer.width > 0) {
            pillContainer.width
        } else {
            metrics.scaledDp(212, effectivePillScale).coerceAtMost(metrics.maxAllowedWidthPx)
        }
        val centerX = savedExpandedX + (barWidth / 2)
        collapsedOnRightEdge = centerX >= (screenW / 2)

        applyEdgeTabBackground(onRightEdge = collapsedOnRightEdge, tabScale = effectivePillScale, metrics = metrics)
        pillContainer.visibility = View.GONE
        edgeTabContainer.visibility = View.VISIBLE
        isCollapsedToEdgeTab = true

        val tabWidth = metrics.scaledDp(26, effectivePillScale)
        layoutParams.x = if (collapsedOnRightEdge) (screenW - tabWidth).coerceAtLeast(0) else 0
        layoutParams.y = savedExpandedY
        clampPositionToScreen()
        safeUpdateWindowLayout()
    }

    fun expandFromEdgeTab() {
        if (!isCollapsedToEdgeTab) return
        isCollapsedToEdgeTab = false
        edgeTabContainer.visibility = View.GONE
        pillContainer.visibility = View.VISIBLE

        layoutParams.x = savedExpandedX
        layoutParams.y = savedExpandedY
        clampPositionToScreen()
        safeUpdateWindowLayout()
    }

    fun clampPositionToScreen() {
        val metrics = currentMetrics()
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        if (isCollapsedToEdgeTab) {
            val tabWidth = if (edgeTabContainer.width > 0) edgeTabContainer.width else metrics.scaledDp(26, effectivePillScale)
            val tabHeight = if (edgeTabContainer.height > 0) edgeTabContainer.height else metrics.scaledDp(48, effectivePillScale)
            layoutParams.x = if (collapsedOnRightEdge) (screenW - tabWidth).coerceAtLeast(0) else 0
            layoutParams.y = layoutParams.y.coerceIn(0, (screenH - tabHeight).coerceAtLeast(0))
        } else {
            val estWidth = if (pillContainer.width > 0) {
                pillContainer.width.coerceAtMost(screenW)
            } else {
                metrics.scaledDp(212, effectivePillScale).coerceAtMost(metrics.maxAllowedWidthPx)
            }
            val estHeight = if (pillContainer.height > 0) {
                pillContainer.height.coerceAtMost(screenH)
            } else {
                metrics.scaledDp(46, effectivePillScale)
            }
            layoutParams.x = layoutParams.x.coerceIn(0, (screenW - estWidth).coerceAtLeast(0))
            layoutParams.y = layoutParams.y.coerceIn(0, (screenH - estHeight).coerceAtLeast(0))
            savedExpandedX = layoutParams.x
            savedExpandedY = layoutParams.y
        }
    }

    fun updateRecordingState(status: RecordingStatus) {
        when (status) {
            is RecordingStatus.Recording -> {
                isPausedState = false
                statusDot.background = makeCircleDrawable(0xFFFF3B30.toInt())
                edgeStatusDot.background = makeCircleDrawable(0xFFFF3B30.toInt())
                timerText.text = ScreenRecordingService.formatDurationStatic(status.durationMs)
                btnPauseResume.setImageResource(R.drawable.ic_notif_pause)
                btnPauseResume.contentDescription = "Pausar Gravação"
            }
            is RecordingStatus.Paused -> {
                isPausedState = true
                statusDot.background = makeCircleDrawable(0xFFFFC400.toInt())
                edgeStatusDot.background = makeCircleDrawable(0xFFFFC400.toInt())
                timerText.text = ScreenRecordingService.formatDurationStatic(status.durationMs)
                btnPauseResume.setImageResource(R.drawable.ic_notif_play)
                btnPauseResume.contentDescription = "Continuar Gravação"
            }
            else -> {
                isPausedState = false
                timerText.text = "00:00"
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragTouch(targetView: View, savePositionOnUp: Boolean) {
        targetView.setOnTouchListener { _, event ->
            val metrics = currentMetrics()
            val touchSlop = metrics.dpToPx(6)
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
                        val maxW = (metrics.widthPixels - width).coerceAtLeast(0)
                        val maxH = (metrics.heightPixels - height).coerceAtLeast(0)
                        layoutParams.x = (initialX + dx.toInt()).coerceIn(0, maxW)
                        layoutParams.y = (initialY + dy.toInt()).coerceAtLeast(0).coerceAtMost(maxH)
                        savedExpandedX = layoutParams.x
                        savedExpandedY = layoutParams.y
                        safeUpdateWindowLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging && savePositionOnUp && !isCollapsedToEdgeTab) {
                        SettingsPreferences.saveDexMiniBarPosition(context, layoutParams.x, layoutParams.y)
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupEdgeTabTouch() {
        edgeTabContainer.setOnTouchListener { _, event ->
            val metrics = currentMetrics()
            val touchSlop = metrics.dpToPx(6)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                        val maxH = (metrics.heightPixels - height).coerceAtLeast(0)
                        layoutParams.y = (initialY + dy.toInt()).coerceIn(0, maxH)
                        savedExpandedY = layoutParams.y
                        safeUpdateWindowLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        expandFromEdgeTab()
                    }
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
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
}
