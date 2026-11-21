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
import kotlin.math.roundToInt

/**
 * Mini Barra Flutuante compacta (Modo DeX e Celular):
 * - Pílula horizontal escura, discreta e arrastável para qualquer canto da tela.
 * - Redimensionamento proporcional real em dp/sp (sem cortar bordas no WindowManager)
 *   e opacidade configurável (25% a 100%, padrão 90%).
 * - Salva automaticamente a última posição arrastada no SharedPreferences.
 * - Exibe indicador colorido (vermelho gravando / amarelo pausado), cronômetro tabular em tempo real,
 *   botão dinâmico Pausar (||) ⇄ Continuar (▶), botão Parar (■) e botão Recolher/Ocultar.
 * - Suporta recolhimento em pequena aba lateral semitransparente proporcional (reabrível com 1 toque)
 *   ou ocultação completa até o usuário tocar na notificação.
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
    initialOpacityPercent: Int = SettingsPreferences.DEFAULT_MINI_BAR_OPACITY_PERCENT
) : FrameLayout(context) {

    var collapseBehavior: MiniBarCollapseBehavior = initialCollapseBehavior
        private set

    var currentSizePercent: Int = SettingsPreferences.sanitizeSizePercent(initialSizePercent)
        private set

    var currentOpacityPercent: Int = SettingsPreferences.sanitizeMiniBarOpacityPercent(initialOpacityPercent)
        private set

    private val pillScale: Float
        get() = currentSizePercent / 100f

    var isCollapsedToEdgeTab: Boolean = false
        private set

    var isPausedState: Boolean = false
        private set

    private var savedExpandedX: Int = 0
    private var savedExpandedY: Int = 0
    private var collapsedOnRightEdge: Boolean = false

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

    private fun computeInitialPosition(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(dpToPx(320))
        val screenH = metrics.heightPixels.coerceAtLeast(dpToPx(320))
        val estimatedBarWidth = scaledDp(220, pillScale)
        val estimatedBarHeight = scaledDp(46, pillScale)

        val saved = SettingsPreferences.loadDexMiniBarPosition(context)
        return if (saved != null) {
            val clampedX = saved.first.coerceIn(0, (screenW - estimatedBarWidth).coerceAtLeast(0))
            val clampedY = saved.second.coerceIn(0, (screenH - estimatedBarHeight).coerceAtLeast(0))
            Pair(clampedX, clampedY)
        } else {
            val centerX = ((screenW - estimatedBarWidth) / 2).coerceAtLeast(dpToPx(12))
            val topY = dpToPx(24)
            Pair(centerX, topY)
        }
    }

    private fun setupExpandedPill() {
        pillContainer.orientation = LinearLayout.HORIZONTAL
        pillContainer.gravity = Gravity.CENTER_VERTICAL
        pillContainer.elevation = dpToPx(8).toFloat()

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
     * TAREFA 3: Aplica escala proporcional real em dp/sp e opacidade na Pílula e na Aba Lateral Recolhida,
     * mantendo WRAP_CONTENT e fazendo clamp seguro de (x, y) dentro das bordas da tela.
     */
    fun applyStyle(sizePercent: Int, opacityPercent: Int) {
        currentSizePercent = SettingsPreferences.sanitizeSizePercent(sizePercent)
        currentOpacityPercent = SettingsPreferences.sanitizeMiniBarOpacityPercent(opacityPercent)

        val s = pillScale
        val userAlpha = currentOpacityPercent / 100f
        val edgeAlpha = SettingsPreferences.computeEdgeTabAlpha(currentOpacityPercent)

        // 1. Pílula expandida
        pillContainer.alpha = userAlpha
        pillContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = scaledDp(24, s).toFloat()
            setColor(0xEB141820.toInt())
            setStroke(scaledDp(1, s).coerceAtLeast(1), 0x44FFFFFF)
        }
        pillContainer.setPadding(
            scaledDp(12, s),
            scaledDp(6, s),
            scaledDp(8, s),
            scaledDp(6, s)
        )

        val dotSize = scaledDp(10, s)
        statusDot.layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginEnd = scaledDp(8, s)
        }

        timerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * s)
        timerText.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = scaledDp(10, s)
        }

        val actionSize = scaledDp(32, s)
        val actionPad = scaledDp(7, s)
        val actionGap = scaledDp(6, s)
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

        val collapseSize = scaledDp(28, s)
        val collapsePad = scaledDp(7, s)
        btnCollapseOrHide.background = makeCircleDrawable(0x24FFFFFF)
        btnCollapseOrHide.layoutParams = LinearLayout.LayoutParams(collapseSize, collapseSize)
        btnCollapseOrHide.setPadding(collapsePad, collapsePad, collapsePad, collapsePad)

        // 2. Aba Lateral Recolhida proporcional e discreta
        edgeTabContainer.alpha = edgeAlpha
        edgeTabContainer.setPadding(
            scaledDp(6, s),
            scaledDp(8, s),
            scaledDp(6, s),
            scaledDp(8, s)
        )
        val edgeDotSize = scaledDp(7, s)
        edgeStatusDot.layoutParams = LinearLayout.LayoutParams(edgeDotSize, edgeDotSize).apply {
            bottomMargin = scaledDp(4, s)
        }
        edgeChevronText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * s)
        applyEdgeTabBackground(onRightEdge = collapsedOnRightEdge)

        requestLayout()
        clampPositionToScreen()
        safeUpdateWindowLayout()
    }

    private fun applyEdgeTabBackground(onRightEdge: Boolean) {
        val r = scaledDp(14, pillScale).toFloat()
        val radii = if (onRightEdge) {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        } else {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        edgeTabContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(0xD9141820.toInt())
            setStroke(scaledDp(1, pillScale).coerceAtLeast(1), 0x44FFFFFF)
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
        val screenW = context.resources.displayMetrics.widthPixels.coerceAtLeast(dpToPx(320))
        savedExpandedX = layoutParams.x
        savedExpandedY = layoutParams.y

        val barWidth = if (pillContainer.width > 0) pillContainer.width else scaledDp(220, pillScale)
        val centerX = savedExpandedX + (barWidth / 2)
        collapsedOnRightEdge = centerX >= (screenW / 2)

        applyEdgeTabBackground(onRightEdge = collapsedOnRightEdge)
        pillContainer.visibility = View.GONE
        edgeTabContainer.visibility = View.VISIBLE
        isCollapsedToEdgeTab = true

        val tabWidth = scaledDp(26, pillScale)
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
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(dpToPx(320))
        val screenH = metrics.heightPixels.coerceAtLeast(dpToPx(320))

        if (isCollapsedToEdgeTab) {
            val tabWidth = scaledDp(26, pillScale)
            val tabHeight = scaledDp(48, pillScale)
            layoutParams.x = if (collapsedOnRightEdge) (screenW - tabWidth).coerceAtLeast(0) else 0
            layoutParams.y = layoutParams.y.coerceIn(0, (screenH - tabHeight).coerceAtLeast(0))
        } else {
            val estWidth = if (pillContainer.width > 0) pillContainer.width else scaledDp(220, pillScale)
            val estHeight = if (pillContainer.height > 0) pillContainer.height else scaledDp(46, pillScale)
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
        val touchSlop = dpToPx(6)
        targetView.setOnTouchListener { _, event ->
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
                        val metrics = context.resources.displayMetrics
                        val maxW = (metrics.widthPixels - width).coerceAtLeast(0)
                        val maxH = (metrics.heightPixels - height).coerceAtLeast(0)
                        layoutParams.x = (initialX + dx.toInt()).coerceIn(0, maxW)
                        layoutParams.y = (initialY + dy.toInt()).coerceIn(0, maxH)
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
        val touchSlop = dpToPx(6)
        edgeTabContainer.setOnTouchListener { _, event ->
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
                        val metrics = context.resources.displayMetrics
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

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun scaledDp(baseDp: Int, scale: Float): Int {
        return (baseDp * scale * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    }
}
