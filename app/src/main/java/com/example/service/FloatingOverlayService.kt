package com.example.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.model.RecordingStatus
import com.example.model.SettingsPreferences
import com.example.overlay.BrushOverlayView
import com.example.overlay.FacecamOverlayView
import com.example.overlay.FloatingBallView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FloatingOverlayService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_SHOW_BALL = "com.example.action.SHOW_BALL"
        const val ACTION_HIDE_BALL = "com.example.action.HIDE_BALL"
        const val ACTION_TOGGLE_BRUSH = "com.example.action.TOGGLE_BRUSH"
        const val ACTION_TOGGLE_FACECAM = "com.example.action.TOGGLE_FACECAM"
        const val ACTION_UPDATE_OPACITY = "com.example.action.UPDATE_OPACITY"
        const val ACTION_CLOSE_ALL = "com.example.action.CLOSE_ALL_OVERLAYS"

        const val EXTRA_OPACITY = "extra_opacity"

        private val _floatingBallVisibleFlow = MutableStateFlow(false)
        val floatingBallVisibleFlow: StateFlow<Boolean> = _floatingBallVisibleFlow.asStateFlow()

        private val _brushActiveFlow = MutableStateFlow(false)
        val brushActiveFlow: StateFlow<Boolean> = _brushActiveFlow.asStateFlow()

        private val _facecamActiveFlow = MutableStateFlow(false)
        val facecamActiveFlow: StateFlow<Boolean> = _facecamActiveFlow.asStateFlow()

        // Fluxo reativo para sincronizar o Switch da tela principal quando a bola é fechada pelo "X" ou arrastada
        private val _switchSyncFlow = MutableStateFlow<Boolean?>(null)
        val switchSyncFlow: StateFlow<Boolean?> = _switchSyncFlow.asStateFlow()

        @Volatile
        var isFloatingBallVisible = false
            private set

        @Volatile
        var isBrushActive = false
            private set

        @Volatile
        var isFacecamActive = false
            private set

        fun notifySwitchStateChanged(enabled: Boolean) {
            _switchSyncFlow.value = enabled
        }
    }

    private val tag = "FloatingOverlayService"
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

        // Observa em tempo real os estados isRecording e isPaused do serviço de gravação
        overlayScope.launch {
            ScreenRecordingService.recordingStatus.collect { status ->
                floatingBallView?.updateRecordingState(status)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE_BALL -> {
                removeFloatingBallOverlay()
                if (brushOverlayView == null && facecamOverlayView == null) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_CLOSE_ALL -> {
                closeAllOverlaysAndStop()
                return START_NOT_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(tag, "Overlay permission not granted, requesting via MainActivity if triggered for tool")
            if (intent?.action == ACTION_TOGGLE_BRUSH || intent?.action == ACTION_TOGGLE_FACECAM) {
                val actIntent = Intent(this, MainActivity::class.java).apply {
                    action = intent.action
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(actIntent)
            }
            removeFloatingBallOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SHOW_BALL -> showFloatingBall()
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
                onStartRecording = { startRecordingFromOverlay() },
                onToggleBrush = { toggleBrush() },
                onToggleFacecam = { toggleFacecam() },
                onCloseBall = { closeFloatingBallFromOverlay() }
            )
            ball.setBallAlpha(ScreenRecordingService.currentSettings.floatingBallOpacity)
            ball.updateRecordingState(ScreenRecordingService.recordingStatus.value)
            ball.updateToolStates(isBrushActive, isFacecamActive)
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
            floatingBallView?.updateRecordingState(ScreenRecordingService.recordingStatus.value)
            floatingBallView?.updateToolStates(isBrushActive, isFacecamActive)
            isFloatingBallVisible = floatingBallView != null
            _floatingBallVisibleFlow.value = isFloatingBallVisible
        }
    }

    /**
     * Inicia a gravação a partir do botão Gravar (Record) do Balão Flutuante.
     * Abre a MainActivity / fluxo de captura solicitando o início imediato da gravação.
     */
    private fun startRecordingFromOverlay() {
        val status = ScreenRecordingService.recordingStatus.value
        val alreadyRecording = status is RecordingStatus.Recording ||
            status is RecordingStatus.Paused ||
            status is RecordingStatus.Countdown
        if (alreadyRecording) return

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_START_RECORDING_FROM_OVERLAY
            putExtra(MainActivity.EXTRA_AUTO_START_RECORDING, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    /**
     * Chamado ao clicar no botão "X" da bola flutuante
     * ou ao arrastar e soltar a bola sobre o alvo vermelho inferior.
     */
    private fun closeFloatingBallFromOverlay() {
        removeFloatingBallOverlay()
        val updated = ScreenRecordingService.currentSettings.copy(showFloatingBall = false)
        ScreenRecordingService.currentSettings = updated
        SettingsPreferences.save(this, updated)
        _switchSyncFlow.value = false
        if (brushOverlayView == null && facecamOverlayView == null) {
            stopSelf()
        }
    }

    private fun closeAllOverlaysAndStop() {
        removeFloatingBallOverlay()
        removeBrushOverlay()
        removeFacecamOverlay()
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

    /**
     * Fechar Automaticamente ao Encerrar o App nos Recentes (onTaskRemoved)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val status = ScreenRecordingService.recordingStatus.value
        val isRecording = status is RecordingStatus.Recording ||
            status is RecordingStatus.Paused ||
            status is RecordingStatus.Countdown
        if (!isRecording) {
            removeFloatingBallOverlay()
            removeBrushOverlay()
            removeFacecamOverlay()
            ScreenRecordingService.cancelControlNotification(this)
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
                isBrushActive = true
                _brushActiveFlow.value = true
                Log.d(tag, "Brush overlay displayed")
            } catch (e: Exception) {
                Log.e(tag, "Error showing brush overlay", e)
            }
        } else {
            removeBrushOverlay()
        }
        floatingBallView?.updateToolStates(isBrushActive, isFacecamActive)
        ScreenRecordingService.showOrUpdateControlNotification(this)
    }

    private fun removeBrushOverlay() {
        brushOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(tag, "Error removing brush overlay", e)
            }
            brushOverlayView = null
        }
        isBrushActive = false
        _brushActiveFlow.value = false
    }

    private fun toggleFacecam() {
        if (facecamOverlayView == null) {
            // Verifica se a permissão de câmera já foi concedida antes de abrir a Facecam
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                val actIntent = Intent(this, MainActivity::class.java).apply {
                    action = ACTION_TOGGLE_FACECAM
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(actIntent)
                return
            }

            val wm = windowManager ?: return
            val facecam = FacecamOverlayView(this, this, wm) {
                toggleFacecam()
            }
            try {
                wm.addView(facecam, facecam.layoutParams)
                facecamOverlayView = facecam
                isFacecamActive = true
                _facecamActiveFlow.value = true
                Log.d(tag, "Facecam overlay displayed")
            } catch (e: Exception) {
                Log.e(tag, "Error adding facecam overlay", e)
            }
        } else {
            removeFacecamOverlay()
        }
        floatingBallView?.updateToolStates(isBrushActive, isFacecamActive)
        ScreenRecordingService.showOrUpdateControlNotification(this)
    }

    private fun removeFacecamOverlay() {
        facecamOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(tag, "Error removing facecam overlay", e)
            }
            facecamOverlayView = null
        }
        isFacecamActive = false
        _facecamActiveFlow.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayScope.cancel()
        removeFloatingBallOverlay()
        removeBrushOverlay()
        removeFacecamOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}



