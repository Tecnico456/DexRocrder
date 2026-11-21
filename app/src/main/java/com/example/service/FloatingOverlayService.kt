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
import com.example.model.DexMiniBarMode
import com.example.model.RecordingStatus
import com.example.model.SettingsPreferences
import com.example.overlay.BrushOverlayView
import com.example.overlay.DexMiniBarView
import com.example.overlay.FacecamOverlayView
import com.example.overlay.FloatingBallView
import com.example.overlay.NotificationMiniControlView
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
        const val ACTION_SYNC_MINI_BAR = "com.example.action.SYNC_MINI_BAR"
        const val ACTION_HIDE_MINI_BAR = "com.example.action.HIDE_MINI_BAR"
        const val ACTION_SHOW_MINI_CONTROL = "com.example.action.SHOW_MINI_CONTROL"
        const val ACTION_HIDE_MINI_CONTROL = "com.example.action.HIDE_MINI_CONTROL"

        const val EXTRA_OPACITY = "extra_opacity"

        private val _floatingBallVisibleFlow = MutableStateFlow(false)
        val floatingBallVisibleFlow: StateFlow<Boolean> = _floatingBallVisibleFlow.asStateFlow()

        private val _brushActiveFlow = MutableStateFlow(false)
        val brushActiveFlow: StateFlow<Boolean> = _brushActiveFlow.asStateFlow()

        private val _facecamActiveFlow = MutableStateFlow(false)
        val facecamActiveFlow: StateFlow<Boolean> = _facecamActiveFlow.asStateFlow()

        private val _dexMiniBarVisibleFlow = MutableStateFlow(false)
        val dexMiniBarVisibleFlow: StateFlow<Boolean> = _dexMiniBarVisibleFlow.asStateFlow()

        private val _miniControlVisibleFlow = MutableStateFlow(false)
        val miniControlVisibleFlow: StateFlow<Boolean> = _miniControlVisibleFlow.asStateFlow()

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

        @Volatile
        var isDexMiniBarVisible = false
            private set

        @Volatile
        var isMiniControlVisible = false
            private set

        fun notifySwitchStateChanged(enabled: Boolean) {
            _switchSyncFlow.value = enabled
        }

        fun shouldShowDexMiniBar(context: Context): Boolean {
            return when (ScreenRecordingService.currentSettings.dexMiniBarMode) {
                DexMiniBarMode.OFF -> false
                DexMiniBarMode.DEX_ONLY -> ScreenRecordingService.isDeXMode(context)
                DexMiniBarMode.ALWAYS -> true
            }
        }
    }

    private val tag = "FloatingOverlayService"
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var floatingBallView: FloatingBallView? = null
    private var dexMiniBarView: DexMiniBarView? = null
    private var miniControlView: NotificationMiniControlView? = null
    private var brushOverlayView: BrushOverlayView? = null
    private var facecamOverlayView: FacecamOverlayView? = null
    private var dexMiniBarHiddenByUser: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // Observa em tempo real os estados isRecording, isPaused e o tempo gravado do serviço de gravação
        overlayScope.launch {
            ScreenRecordingService.recordingStatus.collect { status ->
                floatingBallView?.updateRecordingState(status)
                dexMiniBarView?.updateRecordingState(status)
                miniControlView?.updateRecordingState(status)

                val isRecordingActive = status is RecordingStatus.Recording ||
                    status is RecordingStatus.Paused
                if (isRecordingActive) {
                    if (shouldShowDexMiniBar(this@FloatingOverlayService) &&
                        !dexMiniBarHiddenByUser &&
                        Settings.canDrawOverlays(this@FloatingOverlayService)
                    ) {
                        showDexMiniBar()
                    }
                } else if (status is RecordingStatus.Idle || status is RecordingStatus.Error) {
                    dexMiniBarHiddenByUser = false
                    removeDexMiniBarOverlay()
                    removeMiniControlOverlay()
                    stopSelfIfIdle()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE_BALL -> {
                removeFloatingBallOverlay()
                stopSelfIfIdle()
                return START_NOT_STICKY
            }
            ACTION_HIDE_MINI_BAR -> {
                removeDexMiniBarOverlay()
                stopSelfIfIdle()
                return START_NOT_STICKY
            }
            ACTION_HIDE_MINI_CONTROL -> {
                removeMiniControlOverlay()
                stopSelfIfIdle()
                return START_NOT_STICKY
            }
            ACTION_CLOSE_ALL -> {
                closeAllOverlaysAndStop()
                return START_NOT_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(tag, "Overlay permission not granted, falling back to MainActivity if user-triggered")
            when (intent?.action) {
                ACTION_TOGGLE_BRUSH, ACTION_TOGGLE_FACECAM -> {
                    val actIntent = Intent(this, MainActivity::class.java).apply {
                        action = intent.action
                        this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(actIntent)
                }
                ACTION_SHOW_MINI_CONTROL -> {
                    val mainIntent = Intent(this, MainActivity::class.java).apply {
                        this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(mainIntent)
                }
            }
            removeFloatingBallOverlay()
            removeDexMiniBarOverlay()
            removeMiniControlOverlay()
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
            ACTION_SYNC_MINI_BAR -> syncDexMiniBar()
            ACTION_SHOW_MINI_CONTROL -> showNotificationMiniControl()
            else -> showFloatingBall()
        }

        return START_NOT_STICKY
    }

    private fun isRecordingActive(): Boolean {
        val status = ScreenRecordingService.recordingStatus.value
        return ScreenRecordingService.isStartingOrRecording ||
            status is RecordingStatus.Recording ||
            status is RecordingStatus.Paused
    }

    private fun syncDexMiniBar() {
        val behavior = ScreenRecordingService.currentSettings.miniBarCollapseBehavior
        if (isRecordingActive() && shouldShowDexMiniBar(this)) {
            dexMiniBarHiddenByUser = false
            showDexMiniBar()
            dexMiniBarView?.updateCollapseBehavior(behavior)
        } else {
            removeDexMiniBarOverlay()
            stopSelfIfIdle()
        }
    }

    private fun showDexMiniBar() {
        val wm = windowManager ?: return
        val existing = dexMiniBarView
        if (existing != null) {
            existing.updateCollapseBehavior(ScreenRecordingService.currentSettings.miniBarCollapseBehavior)
            existing.updateRecordingState(ScreenRecordingService.recordingStatus.value)
            isDexMiniBarVisible = true
            _dexMiniBarVisibleFlow.value = true
            return
        }

        val miniBar = DexMiniBarView(
            context = this,
            windowManager = wm,
            initialCollapseBehavior = ScreenRecordingService.currentSettings.miniBarCollapseBehavior,
            onTogglePauseResume = { sendPauseOrResumeToRecordingService() },
            onStopRecording = { sendStopToRecordingService() },
            onHideUntilNotificationTap = {
                dexMiniBarHiddenByUser = true
                removeDexMiniBarOverlay()
                stopSelfIfIdle()
            }
        )
        miniBar.updateRecordingState(ScreenRecordingService.recordingStatus.value)
        try {
            wm.addView(miniBar, miniBar.layoutParams)
            dexMiniBarView = miniBar
            isDexMiniBarVisible = true
            _dexMiniBarVisibleFlow.value = true
            Log.d(tag, "DeX Mini Bar attached to window")
        } catch (e: Exception) {
            Log.e(tag, "Error adding DeX Mini Bar view", e)
        }
    }

    private fun removeDexMiniBarOverlay() {
        dexMiniBarView?.let { bar ->
            try {
                windowManager?.removeView(bar)
            } catch (e: Exception) {
                Log.w(tag, "Error removing DeX Mini Bar", e)
            }
        }
        dexMiniBarView = null
        isDexMiniBarVisible = false
        _dexMiniBarVisibleFlow.value = false
    }

    private fun showNotificationMiniControl() {
        val wm = windowManager ?: return
        if (!isRecordingActive()) {
            removeMiniControlOverlay()
            stopSelfIfIdle()
            return
        }

        // Se a Mini Barra DeX estava oculta pelo usuário ("Ocultar até tocar na notificação")
        // ou recolhida em aba lateral, restaura-a de forma conveniente.
        if (dexMiniBarHiddenByUser && shouldShowDexMiniBar(this)) {
            dexMiniBarHiddenByUser = false
            showDexMiniBar()
        } else {
            dexMiniBarView?.expandFromEdgeTab()
        }

        // Remove e readiciona se já existir para garantir que venha para a frente (z-order superior)
        removeMiniControlOverlay()

        val menu = NotificationMiniControlView(
            context = this,
            windowManager = wm,
            onTogglePauseResume = { sendPauseOrResumeToRecordingService() },
            onStopRecording = {
                removeMiniControlOverlay()
                sendStopToRecordingService()
            },
            onDismissMenu = {
                removeMiniControlOverlay()
                stopSelfIfIdle()
            }
        )
        menu.updateRecordingState(ScreenRecordingService.recordingStatus.value)
        try {
            wm.addView(menu, menu.layoutParams)
            miniControlView = menu
            isMiniControlVisible = true
            _miniControlVisibleFlow.value = true
            Log.d(tag, "Notification Mini Control Menu attached to window")
        } catch (e: Exception) {
            Log.e(tag, "Error adding Notification Mini Control Menu", e)
        }
    }

    private fun removeMiniControlOverlay() {
        miniControlView?.let { menu ->
            try {
                windowManager?.removeView(menu)
            } catch (e: Exception) {
                Log.w(tag, "Error removing Notification Mini Control Menu", e)
            }
        }
        miniControlView = null
        isMiniControlVisible = false
        _miniControlVisibleFlow.value = false
    }

    private fun sendPauseOrResumeToRecordingService() {
        val status = ScreenRecordingService.recordingStatus.value
        val action = if (status is RecordingStatus.Paused) {
            ScreenRecordingService.ACTION_RESUME
        } else {
            ScreenRecordingService.ACTION_PAUSE
        }
        try {
            startService(Intent(this, ScreenRecordingService::class.java).apply {
                this.action = action
            })
        } catch (e: Exception) {
            Log.e(tag, "Error sending $action to ScreenRecordingService", e)
        }
    }

    private fun sendStopToRecordingService() {
        try {
            startService(Intent(this, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_STOP
            })
        } catch (e: Exception) {
            Log.e(tag, "Error sending ACTION_STOP to ScreenRecordingService", e)
        }
    }

    private fun stopSelfIfIdle() {
        if (floatingBallView == null &&
            dexMiniBarView == null &&
            miniControlView == null &&
            brushOverlayView == null &&
            facecamOverlayView == null
        ) {
            stopSelf()
        }
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
        stopSelfIfIdle()
    }

    private fun closeAllOverlaysAndStop() {
        removeFloatingBallOverlay()
        removeDexMiniBarOverlay()
        removeMiniControlOverlay()
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
            removeDexMiniBarOverlay()
            removeMiniControlOverlay()
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
        stopSelfIfIdle()
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
        stopSelfIfIdle()
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
        removeDexMiniBarOverlay()
        removeMiniControlOverlay()
        removeBrushOverlay()
        removeFacecamOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
