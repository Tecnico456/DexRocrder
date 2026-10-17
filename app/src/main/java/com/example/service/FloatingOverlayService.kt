package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.overlay.BrushOverlayView
import com.example.overlay.FacecamOverlayView
import com.example.overlay.FloatingBallView

class FloatingOverlayService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_SHOW_BALL = "com.example.action.SHOW_BALL"
        const val ACTION_HIDE_BALL = "com.example.action.HIDE_BALL"
        const val ACTION_TOGGLE_BRUSH = "com.example.action.TOGGLE_BRUSH"
        const val ACTION_TOGGLE_FACECAM = "com.example.action.TOGGLE_FACECAM"
        const val ACTION_UPDATE_OPACITY = "com.example.action.UPDATE_OPACITY"

        const val EXTRA_OPACITY = "extra_opacity"

        @Volatile
        var isFloatingBallVisible = false
            private set
    }

    private val tag = "FloatingOverlayService"
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var windowManager: WindowManager? = null
    private var floatingBallView: FloatingBallView? = null
    private var brushOverlayView: BrushOverlayView? = null
    private var facecamOverlayView: FacecamOverlayView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(tag, "Overlay permission not granted, cannot display floating windows")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SHOW_BALL -> showFloatingBall()
            ACTION_HIDE_BALL -> hideFloatingBall()
            ACTION_TOGGLE_BRUSH -> toggleBrush()
            ACTION_TOGGLE_FACECAM -> toggleFacecam()
            ACTION_UPDATE_OPACITY -> {
                val opacity = intent.getFloatExtra(EXTRA_OPACITY, 0.85f)
                floatingBallView?.setBallAlpha(opacity)
            }
            else -> showFloatingBall()
        }

        return START_STICKY
    }

    private fun showFloatingBall() {
        if (floatingBallView == null && windowManager != null) {
            val ball = FloatingBallView(
                context = this,
                windowManager = windowManager!!,
                onToggleBrush = { toggleBrush() },
                onToggleFacecam = { toggleFacecam() }
            )
            ball.setBallAlpha(ScreenRecordingService.currentSettings.floatingBallOpacity)
            try {
                windowManager?.addView(ball, ball.layoutParams)
                floatingBallView = ball
                isFloatingBallVisible = true
                Log.d(tag, "Floating Ball attached to window")
            } catch (e: Exception) {
                Log.e(tag, "Error adding floating ball view", e)
            }
        }
    }

    private fun hideFloatingBall() {
        floatingBallView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(tag, "Error removing floating ball", e)
            }
            floatingBallView = null
            isFloatingBallVisible = false
        }
    }

    private fun toggleBrush() {
        if (brushOverlayView == null) {
            val wm = windowManager ?: return
            val brush = BrushOverlayView(this) {
                toggleBrush()
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                0,
                PixelFormat.TRANSLUCENT
            )
            try {
                wm.addView(brush, lp)
                brushOverlayView = brush
                Log.d(tag, "Brush overlay displayed")
            } catch (e: Exception) {
                Log.e(tag, "Error showing brush overlay", e)
            }
        } else {
            brushOverlayView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (e: Exception) {
                    Log.w(tag, "Error removing brush overlay", e)
                }
                brushOverlayView = null
            }
        }
    }

    private fun toggleFacecam() {
        if (facecamOverlayView == null) {
            val wm = windowManager ?: return
            val facecam = FacecamOverlayView(this, this, wm) {
                toggleFacecam()
            }
            try {
                wm.addView(facecam, facecam.layoutParams)
                facecamOverlayView = facecam
                Log.d(tag, "Facecam overlay displayed")
            } catch (e: Exception) {
                Log.e(tag, "Error adding facecam overlay", e)
            }
        } else {
            facecamOverlayView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (e: Exception) {
                    Log.w(tag, "Error removing facecam overlay", e)
                }
                facecamOverlayView = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingBall()
        brushOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            brushOverlayView = null
        }
        facecamOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            facecamOverlayView = null
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
