package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

@SuppressLint("ViewConstructor")
class FacecamOverlayView(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val windowManager: WindowManager,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private val previewView = PreviewView(context)
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var currentSizeDp = 140

    val layoutParams = WindowManager.LayoutParams(
        dpToPx(currentSizeDp),
        dpToPx((currentSizeDp * 1.33f).toInt()),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 50
        y = 200
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    init {
        setBackgroundColor(0xFF1E1B2E.toInt())
        setupView()
        bindCamera()
        setupTouchListener()
    }

    private fun setupView() {
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x88000000.toInt())
            gravity = Gravity.END

            val btnFlip = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_rotate)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.WHITE)
                setPadding(12, 12, 12, 12)
                setOnClickListener { flipCamera() }
            }

            val btnResize = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_crop)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.WHITE)
                setPadding(12, 12, 12, 12)
                setOnClickListener { cycleSize() }
            }

            val btnClose = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.WHITE)
                setPadding(12, 12, 12, 12)
                setOnClickListener { onClose() }
            }

            addView(btnFlip)
            addView(btnResize)
            addView(btnClose)
        }

        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun bindCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
            } catch (e: Exception) {
                // Log and ignore
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun flipCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        bindCamera()
    }

    private fun cycleSize() {
        currentSizeDp = when (currentSizeDp) {
            120 -> 160
            160 -> 220
            else -> 120
        }
        layoutParams.width = dpToPx(currentSizeDp)
        layoutParams.height = dpToPx((currentSizeDp * 1.33f).toInt())
        windowManager.updateViewLayout(this, layoutParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(this, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val scale = context.resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }
}
