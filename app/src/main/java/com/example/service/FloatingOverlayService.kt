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
import com.example.model.RecordingStatus
import com.example.model.SettingsPreferences
import com.example.overlay.BrushOverlayView
import com.example.overlay.FacecamOverlayView
import com.example.overlay.FloatingBallView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FloatingOverlayService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_SHOW_BALL = "com.example.action.SHOW_BALL"
        const val ACTION_HIDE_BALL = "com.example.action.HIDE_BALL"
        const val ACTION_TOGGLE_BRUSH = "com.example.action.TOGGLE_BRUSH"
        const val ACTION_TOGGLE_FACECAM = "com.example.action.TOGGLE_FACECAM"
        const val ACTION_UPDATE_OPACITY = "com.example.action.UPDATE_OPACITY"

        const val EXTRA_OPACITY = "extra_opacity"

        private val _floatingBallVisibleFlow = MutableStateFlow(false)
        val floatingBallVisibleFlow: StateFlow<Boolean> = _floatingBallVisibleFlow.asStateFlow()

        // Fluxo reativo para sincronizar o Switch da tela principal quando a bola é fechada pelo "X" ou arrastada
        private val _switchSyncFlow = MutableStateFlow<Boolean?>(null)
        val switchSyncFlow: StateFlow<Boolean?> = _switchSyncFlow.asStateFlow()

        @Volatile
        var isFloatingBallVisible = false
            private set

        fun notifySwitchStateChanged(enabled: Boolean) {
            _switchSyncFlow.value = enabled
        }
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
        if (intent?.action == ACTION_HIDE_BALL) {
            removeFloatingBallOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(tag, "Overlay permission not granted, cannot display floating windows")
            removeFloatingBallOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SHOW_BALL -> showFloatingBall()
            ACTION_HIDE_BALL -> {
                removeFloatingBallOverlay()
                stopSelf()
            }
            ACTION_TOGGLE_BRUSH -> toggleBrush()
            ACTION_TOGGLE_FACECAM -> toggleFacecam()
            ACTION_UPDATE_OPACITY -> {
                val opacity = intent.getFloatExtra(EXTRA_OPACITY, 0.85f)
                floatingBallView?.setBallAlpha(opacity)
            }
            else -> showFloatingBall()
        }

        return START_NOT_STICKY
    }

    private fun showFloatingBall() {
        if (floatingBallView == null && windowManager != null) {
            val ball = FloatingBallView(
                context = this,
                windowManager = windowManager!!,
                onToggleBrush = { toggleBrush() },
                onToggleFacecam = { toggleFacecam() },
                onCloseBall = { closeFloatingBallFromOverlay() }
            )
            ball.setBallAlpha(ScreenRecordingService.currentSettings.floatingBallOpacity)
            try {
                windowManager?.addView(ball, ball.layoutParams)
                floatingBallView = ball
                isFloatingBallVisible = true
                _floatingBallVisibleFlow.value = true
                _switchSyncFlow.value = true
                Log.d(tag, "Floating Ball attached to window")
            } catch (e: Exception) {
                Log.e(tag, "Error adding floating ball view", e)
            }
        } else {
            isFloatingBallVisible = floatingBallView != null
            _floatingBallVisibleFlow.value = isFloatingBallVisible
        }
    }

    /**
     * TAREFA 1 & TAREFA 2: Chamado ao clicar no botão "X" da bola flutuante
     * ou ao arrastar e soltar a bola sobre o alvo vermelho inferior.
     * Remove imediatamente a view do WindowManager, atualiza o estado de visibilidade
     * (incluindo o Switch da tela principal) para false e encerra o serviço (stopSelf()).
     */
    private fun closeFloatingBallFromOverlay() {
        removeFloatingBallOverlay()
        val updated = ScreenRecordingService.currentSettings.copy(showFloatingBall = false)
        ScreenRecordingService.currentSettings = updated
        SettingsPreferences.save(this, updated)
        _switchSyncFlow.value = false
        stopSelf()
    }

    /**
     * Remove imediatamente a view da bola flutuante (e o alvo de arraste) do WindowManager
     * e atualiza o estado de visibilidade para false.
     */
    fun removeFloatingBallOverlay() {
        floatingBallView?.let { ball ->
            ball.hideCloseTargetOverlay()
            try {
                windowManager?.removeView(ball)
            } catch (e: Exception) {
                Log.w(tag, "Error removing floating ball", e)
            }
        }
        floatingBallView = null
        isFloatingBallVisible = false
        _floatingBallVisibleFlow.value = false
    }

    private fun hideFloatingBall() {
        removeFloatingBallOverlay()
    }

    /**
     * TAREFA 3: Fechar Automaticamente ao Encerrar o App nos Recentes (onTaskRemoved)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val status = ScreenRecordingService.recordingStatus.value
        val isRecording = status is RecordingStatus.Recording ||
            status is RecordingStatus.Paused ||
            status is RecordingStatus.Countdown
        // Se não houver uma gravação ativa no momento, fecha a bola flutuante ao fechar o app
        if (!isRecording) {
            removeFloatingBallOverlay()
            stopSelf()
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
        removeFloatingBallOverlay()
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

