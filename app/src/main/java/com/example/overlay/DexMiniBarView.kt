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
import com.example.model.MiniBarCollapseBehavior
import com.example.model.RecordingStatus
import com.example.model.SettingsPreferences
import com.example.service.ScreenRecordingService
import kotlin.math.hypot

/**
 * Mini Barra Flutuante compacta (Modo DeX e Celular):
 * - Pílula horizontal escura, discreta e arrastável para qualquer canto da tela.
 * - Salva automaticamente a última posição arrastada no SharedPreferences.
 * - Exibe indicador colorido (vermelho gravando / amarelo pausado), cronômetro em tempo real,
 *   botão dinâmico Pausar (||) ⇄ Continuar (▶), botão Parar (■) e botão Recolher/Ocultar.
 * - Suporta recolhimento em pequena aba lateral semitransparente (reabrível com 1 toque)
 *   ou ocultação completa até o usuário tocar na notificação.
 */
@SuppressLint("ViewConstructor")
class DexMiniBarView(
    context: Context,
    private val windowManager: WindowManager,
    initialCollapseBehavior: MiniBarCollapseBehavior,
    private val onTogglePauseResume: () -> Unit,
    private val onStopRecording: () -> Unit,
    private val onHideUntilNotificationTap: () -> Unit
) : FrameLayout(context) {

    var collapseBehavior: MiniBarCollapseBehavior = initialCollapseBehavior
        private set

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
        setupDragTouch(pillContainer, savePositionOnUp = true)
        setupEdgeTabTouch()
        updateRecordingState(ScreenRecordingService.recordingStatus.value)
    }

    private fun computeInitialPosition(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(dpToPx(320))
        val screenH = metrics.heightPixels.coerceAtLeast(dpToPx(320))
        val estimatedBarWidth = dpToPx(220)
        val estimatedBarHeight = dpToPx(46)

        val saved = SettingsPreferences.loadDexMiniBarPosition(context)
        return if (saved != null) {
            val clampedX = saved.first.coerceIn(0, (screenW - estimatedBarWidth).coerceAtLeast(0))
            val clampedY = saved.second.coerceIn(0, (screenH - estimatedBarHeight).coerceAtLeast(0))
            Pair(clampedX, clampedY)
        } else {
            // Primeira execução: centralizado no topo da tela
            val centerX = ((screenW - estimatedBarWidth) / 2).coerceAtLeast(dpToPx(12))
            val topY = dpToPx(24)
            Pair(centerX, topY)
        }
    }

    private fun setupExpandedPill() {
        pillContainer.orientation = LinearLayout.HORIZONTAL
        pillContainer.gravity = Gravity.CENTER_VERTICAL
        pillContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(24).toFloat()
            setColor(0xEB141820.toInt())
            setStroke(dpToPx(1), 0x44FFFFFF)
        }
        pillContainer.elevation = dpToPx(8).toFloat()
        pillContainer.setPadding(dpToPx(12), dpToPx(6), dpToPx(8), dpToPx(6))

        // Ponto indicador de status (vermelho gravando / amarelo pausado)
        statusDot.background = makeCircleDrawable(0xFFFF3B30.toInt())
        pillContainer.addView(
            statusDot,
            LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply {
                marginEnd = dpToPx(8)
            }
        )

        // Cronômetro em tempo real (00:00) com dígitos monoespaçados para evitar trepidação
        timerText.text = "00:00"
        timerText.setTextColor(Color.WHITE)
        timerText.textSize = 13f
        timerText.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        timerText.contentDescription = "Tempo de gravação"
        pillContainer.addView(
            timerText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(10)
            }
        )

        // Botão dinâmico Pausar (||) ⇄ Continuar (▶)
        configureCircleButton(
            button = btnPauseResume,
            sizeDp = 32,
            bgColor = 0xFF283040.toInt(),
            iconRes = R.drawable.ic_notif_pause,
            contentDesc = "Pausar Gravação",
            marginEndDp = 6
        )
        btnPauseResume.setOnClickListener {
            onTogglePauseResume()
        }
        pillContainer.addView(btnPauseResume)

        // Botão Parar (■)
        configureCircleButton(
            button = btnStop,
            sizeDp = 32,
            bgColor = 0xFFE50914.toInt(),
            iconRes = R.drawable.ic_stop_square,
            contentDesc = "Parar Gravação",
            marginEndDp = 6
        )
        btnStop.setOnClickListener {
            onStopRecording()
        }
        pillContainer.addView(btnStop)

        // Botão discreto para Recolher em aba lateral / Ocultar
        configureCircleButton(
            button = btnCollapseOrHide,
            sizeDp = 28,
            bgColor = 0x24FFFFFF,
            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
            contentDesc = collapseButtonContentDescription(),
            marginEndDp = 0
        )
        btnCollapseOrHide.setColorFilter(0xCCCFD8DC.toInt())
        btnCollapseOrHide.setOnClickListener {
            handleCollapseOrHideClick()
        }
        pillContainer.addView(btnCollapseOrHide)
    }

    private fun setupCollapsedEdgeTab() {
        edgeTabContainer.orientation = LinearLayout.VERTICAL
        edgeTabContainer.gravity = Gravity.CENTER
        edgeTabContainer.alpha = 0.52f
        edgeTabContainer.setPadding(dpToPx(6), dpToPx(8), dpToPx(6), dpToPx(8))
        edgeTabContainer.visibility = View.GONE
        edgeTabContainer.contentDescription = "Expandir Mini Barra Flutuante"
        edgeTabContainer.isClickable = true

        edgeStatusDot.background = makeCircleDrawable(0xFFFF3B30.toInt())
        edgeTabContainer.addView(
            edgeStatusDot,
            LinearLayout.LayoutParams(dpToPx(7), dpToPx(7)).apply {
                bottomMargin = dpToPx(4)
            }
        )

        edgeChevronText.text = "›"
        edgeChevronText.setTextColor(Color.WHITE)
        edgeChevronText.textSize = 14f
        edgeChevronText.typeface = Typeface.DEFAULT_BOLD
        edgeChevronText.gravity = Gravity.CENTER
        edgeTabContainer.addView(
            edgeChevronText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        applyEdgeTabBackground(onRightEdge = false)
    }

    private fun applyEdgeTabBackground(onRightEdge: Boolean) {
        val r = dpToPx(14).toFloat()
        val radii = if (onRightEdge) {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        } else {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        edgeTabContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(0xD9141820.toInt())
            setStroke(dpToPx(1), 0x44FFFFFF)
        }
        edgeChevronText.text = if (onRightEdge) "‹" else "›"
    }

    private fun configureCircleButton(
        button: ImageButton,
        sizeDp: Int,
        bgColor: Int,
        iconRes: Int,
        contentDesc: String,
        marginEndDp: Int
    ) {
        val sizePx = dpToPx(sizeDp)
        button.layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
            marginEnd = dpToPx(marginEndDp)
        }
        button.background = makeCircleDrawable(bgColor)
        button.setImageResource(iconRes)
        button.setColorFilter(Color.WHITE)
        button.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dpToPx(7)
        button.setPadding(pad, pad, pad, pad)
        button.contentDescription = contentDesc
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

        val barWidth = if (pillContainer.width > 0) pillContainer.width else dpToPx(220)
        val centerX = savedExpandedX + (barWidth / 2)
        collapsedOnRightEdge = centerX >= (screenW / 2)

        applyEdgeTabBackground(onRightEdge = collapsedOnRightEdge)
        pillContainer.visibility = View.GONE
        edgeTabContainer.visibility = View.VISIBLE
        isCollapsedToEdgeTab = true

        val tabWidth = dpToPx(26)
        layoutParams.x = if (collapsedOnRightEdge) (screenW - tabWidth).coerceAtLeast(0) else 0
        layoutParams.y = savedExpandedY
        safeUpdateWindowLayout()
    }

    fun expandFromEdgeTab() {
        if (!isCollapsedToEdgeTab) return
        isCollapsedToEdgeTab = false
        edgeTabContainer.visibility = View.GONE
        pillContainer.visibility = View.VISIBLE

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(dpToPx(320))
        val screenH = metrics.heightPixels.coerceAtLeast(dpToPx(320))
        val estWidth = dpToPx(220)
        val estHeight = dpToPx(46)

        layoutParams.x = savedExpandedX.coerceIn(0, (screenW - estWidth).coerceAtLeast(0))
        layoutParams.y = savedExpandedY.coerceIn(0, (screenH - estHeight).coerceAtLeast(0))
        safeUpdateWindowLayout()
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
}
