package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar

class BrushOverlayView(
    context: Context,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private val drawingView = DrawingCanvas(context)
    private val controlBar = LinearLayout(context)

    init {
        addView(drawingView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setupControls()
    }

    private fun setupControls() {
        controlBar.orientation = LinearLayout.HORIZONTAL
        controlBar.setBackgroundColor(0xDD1E1B2E.toInt())
        controlBar.setPadding(24, 16, 24, 16)

        // Undo button
        val btnUndo = createIconButton(android.R.drawable.ic_menu_revert) {
            drawingView.undo()
        }
        // Clear button
        val btnClear = createIconButton(android.R.drawable.ic_menu_delete) {
            drawingView.clear()
        }
        // Red color
        val btnRed = createColorButton(0xFFFF3B30.toInt()) {
            drawingView.currentColor = 0xFFFF3B30.toInt()
            drawingView.isHighlighter = false
        }
        // Yellow color
        val btnYellow = createColorButton(0xFFFFCC00.toInt()) {
            drawingView.currentColor = 0xFFFFCC00.toInt()
            drawingView.isHighlighter = false
        }
        // Cyan color
        val btnCyan = createColorButton(0xFF00C7BE.toInt()) {
            drawingView.currentColor = 0xFF00C7BE.toInt()
            drawingView.isHighlighter = false
        }
        // Highlighter mode (Yellow alpha)
        val btnHighlighter = createIconButton(android.R.drawable.ic_menu_edit) {
            drawingView.currentColor = 0x66FFEB3B.toInt()
            drawingView.isHighlighter = true
            drawingView.strokeWidth = 36f
        }
        // Close brush
        val btnClose = createIconButton(android.R.drawable.ic_menu_close_clear_cancel) {
            onClose()
        }

        controlBar.addView(btnUndo)
        controlBar.addView(btnClear)
        controlBar.addView(btnRed)
        controlBar.addView(btnYellow)
        controlBar.addView(btnCyan)
        controlBar.addView(btnHighlighter)
        controlBar.addView(btnClose)

        val barParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 48
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        }
        addView(controlBar, barParams)
    }

    private fun createIconButton(drawableRes: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(drawableRes)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(16, 16, 16, 16)
            setOnClickListener { onClick() }
        }
    }

    private fun createColorButton(color: Int, onClick: () -> Unit): View {
        return View(context).apply {
            val size = 56
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = 12
                rightMargin = 12
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            setBackgroundColor(color)
            setOnClickListener { onClick() }
        }
    }

    private class DrawingCanvas(context: Context) : View(context) {
        data class DrawPath(val path: Path, val paint: Paint)

        private val paths = mutableListOf<DrawPath>()
        private var currentPath = Path()
        var currentColor: Int = 0xFFFF3B30.toInt()
        var strokeWidth: Float = 14f
        var isHighlighter: Boolean = false

        private fun createPaint(): Paint {
            return Paint().apply {
                isAntiAlias = true
                isDither = true
                color = currentColor
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                strokeWidth = if (isHighlighter) 36f else this@DrawingCanvas.strokeWidth
                if (isHighlighter) {
                    alpha = 110
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentPath = Path()
                    currentPath.moveTo(x, y)
                    paths.add(DrawPath(currentPath, createPaint()))
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    currentPath.lineTo(x, y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (dp in paths) {
                canvas.drawPath(dp.path, dp.paint)
            }
        }

        fun undo() {
            if (paths.isNotEmpty()) {
                paths.removeAt(paths.size - 1)
                invalidate()
            }
        }

        fun clear() {
            paths.clear()
            invalidate()
        }
    }
}
