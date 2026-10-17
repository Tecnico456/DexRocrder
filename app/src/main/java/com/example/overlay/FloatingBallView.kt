package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.MainActivity
import com.example.model.RecordingStatus
import com.example.service.ScreenRecordingService

@SuppressLint("ViewConstructor")
class FloatingBallView(
    context: Context,
    private val windowManager: WindowManager,
    private val onToggleBrush: () -> Unit,
    private val onToggleFacecam: () -> Unit
) : FrameLayout(context) {

    private val ballSize = dpToPx(56)
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

    private val bubbleContainer = FrameLayout(context)
    private val bubbleIcon = ImageView(context)
    private val menuContainer = LinearLayout(context)

    private var isMenuExpanded = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    init {
        setupViews()
        setupTouch()
    }

    private fun setupViews() {
        // Main circular bubble
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE50914.toInt()) // Sleek crimson red
            setStroke(dpToPx(2), Color.WHITE)
        }
        bubbleContainer.background = bgDrawable
        val bubbleLp = LayoutParams(ballSize, ballSize)
        bubbleContainer.layoutParams = bubbleLp

        bubbleIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
        bubbleIcon.setColorFilter(Color.WHITE)
        bubbleIcon.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        bubbleContainer.addView(bubbleIcon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Menu panel
        menuContainer.orientation = LinearLayout.HORIZONTAL
        val menuBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(28).toFloat()
            setColor(0xEE1E1B2E.toInt())
            setStroke(dpToPx(1), 0x44FFFFFF)
        }
        menuContainer.background = menuBg
        menuContainer.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        menuContainer.visibility = View.GONE

        // Actions: Pause/Resume, Stop, Brush, Facecam, Open App
        val btnPauseResume = createActionButton(android.R.drawable.ic_media_pause) {
            val status = ScreenRecordingService.recordingStatus.value
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = if (status is RecordingStatus.Paused) {
                    ScreenRecordingService.ACTION_RESUME
                } else {
                    ScreenRecordingService.ACTION_PAUSE
                }
            }
            context.startService(intent)
            collapseMenu()
        }

        val btnStop = createActionButton(android.R.drawable.ic_delete) {
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_STOP
            }
            context.startService(intent)
            collapseMenu()
        }

        val btnBrush = createActionButton(android.R.drawable.ic_menu_edit) {
            onToggleBrush()
            collapseMenu()
        }

        val btnFacecam = createActionButton(android.R.drawable.ic_menu_camera) {
            onToggleFacecam()
            collapseMenu()
        }

        val btnHome = createActionButton(android.R.drawable.ic_menu_compass) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
            collapseMenu()
        }

        menuContainer.addView(btnPauseResume)
        menuContainer.addView(btnStop)
        menuContainer.addView(btnBrush)
        menuContainer.addView(btnFacecam)
        menuContainer.addView(btnHome)

        val rootRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bubbleContainer)
            addView(menuContainer)
        }

        addView(rootRow)
    }

    private fun createActionButton(iconRes: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            val pad = dpToPx(10)
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
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isDragging = true
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager.updateViewLayout(this@FloatingBallView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun toggleMenu() {
        isMenuExpanded = !isMenuExpanded
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
