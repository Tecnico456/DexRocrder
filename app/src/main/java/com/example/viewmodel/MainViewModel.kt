package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DexRecorderApplication
import com.example.editor.VideoEditorManager
import com.example.model.AudioCaptureMode
import com.example.model.BitrateOption
import com.example.model.DexMiniBarMode
import com.example.model.FpsOption
import com.example.model.MiniBarCollapseBehavior
import com.example.model.NotificationControlStyle
import com.example.model.RecordedVideo
import com.example.model.RecordingSettings
import com.example.model.RecordingStatus
import com.example.model.ResolutionOption
import com.example.model.ScreenCaptureMode
import com.example.model.SettingsPreferences
import com.example.service.FloatingOverlayService
import com.example.service.ScreenRecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as DexRecorderApplication).videoRepository

    val activeVideos: StateFlow<List<RecordedVideo>> = repository.activeVideos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trashVideos: StateFlow<List<RecordedVideo>> = repository.trashVideos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recordingStatus: StateFlow<RecordingStatus> = ScreenRecordingService.recordingStatus

    private val _settings = MutableStateFlow(
        SettingsPreferences.load(application).also {
            ScreenRecordingService.currentSettings = it
        }
    )
    val settings: StateFlow<RecordingSettings> = _settings.asStateFlow()

    private val _selectedVideoForEdit = MutableStateFlow<RecordedVideo?>(null)
    val selectedVideoForEdit: StateFlow<RecordedVideo?> = _selectedVideoForEdit.asStateFlow()

    private val _editingStatusMessage = MutableStateFlow<String?>(null)
    val editingStatusMessage: StateFlow<String?> = _editingStatusMessage.asStateFlow()

    init {
        // Sincroniza em tempo real o Switch da tela principal quando a bola é fechada pelo botão "X" ou arrastada para a lixeira
        viewModelScope.launch {
            FloatingOverlayService.switchSyncFlow.collect { enabled ->
                if (enabled != null && _settings.value.showFloatingBall != enabled) {
                    val synced = _settings.value.copy(showFloatingBall = enabled)
                    _settings.value = synced
                    ScreenRecordingService.currentSettings = synced
                    SettingsPreferences.save(getApplication(), synced)
                }
            }
        }
    }

    fun updateSettings(transform: (RecordingSettings) -> RecordingSettings) {
        val newSettings = transform(_settings.value)
        _settings.value = newSettings
        ScreenRecordingService.currentSettings = newSettings
        // TAREFA 4: Salva automaticamente (apply()) qualquer alteração feita pelo usuário no SharedPreferences
        SettingsPreferences.save(getApplication(), newSettings)
    }

    fun setCaptureMode(mode: ScreenCaptureMode) {
        updateSettings { it.copy(captureMode = mode) }
    }

    fun setAudioMode(mode: AudioCaptureMode) {
        updateSettings { it.copy(audioMode = mode) }
    }

    fun setResolution(res: ResolutionOption) {
        updateSettings { it.copy(resolution = res) }
    }

    fun setFps(fps: FpsOption) {
        updateSettings { it.copy(fps = fps) }
    }

    fun setBitrate(bitrate: BitrateOption) {
        updateSettings { it.copy(bitrate = bitrate) }
    }

    fun setVolumes(internalVol: Float, micVol: Float) {
        updateSettings { it.copy(internalAudioVolume = internalVol, micAudioVolume = micVol) }
    }

    fun setCountdown(seconds: Int) {
        updateSettings { it.copy(countdownSeconds = seconds) }
    }

    /**
     * TAREFA 4: Sincronização Imediata com o Switch do Aplicativo
     * Envia ACTION_HIDE_BALL para remover apenas a Bola Flutuante sem derrubar
     * a Mini Barra DeX, Mini Menu da Notificação, Pincel ou Facecam se estiverem ativos.
     */
    fun toggleFloatingBall(context: Context, enabled: Boolean) {
        updateSettings { it.copy(showFloatingBall = enabled) }
        FloatingOverlayService.notifySwitchStateChanged(enabled)
        if (enabled) {
            if (Settings.canDrawOverlays(context)) {
                val intent = Intent(context, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_SHOW_BALL
                }
                context.startService(intent)
            }
        } else {
            val hideIntent = Intent(context, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_HIDE_BALL
            }
            context.startService(hideIntent)
        }
    }

    fun setDexMiniBarMode(context: Context, mode: DexMiniBarMode) {
        updateSettings { it.copy(dexMiniBarMode = mode) }
        syncMiniBarIfRecording(context)
    }

    fun setMiniBarCollapseBehavior(context: Context, behavior: MiniBarCollapseBehavior) {
        updateSettings { it.copy(miniBarCollapseBehavior = behavior) }
        syncMiniBarIfRecording(context)
    }

    fun setNotificationStyle(context: Context, style: NotificationControlStyle) {
        updateSettings { it.copy(notificationStyle = style) }
        // Atualiza imediatamente a notificação ativa para o usuário ver a mudança na hora
        ScreenRecordingService.showOrUpdateControlNotification(context, forceUpdate = true)
    }

    fun setNotificationTapOpensMiniControl(context: Context, enabled: Boolean) {
        updateSettings { it.copy(notificationTapOpensMiniControl = enabled) }
        // Reemite a notificação ativa para atualizar o PendingIntent do toque imediatamente
        ScreenRecordingService.showOrUpdateControlNotification(context, forceUpdate = true)
    }

    private fun syncMiniBarIfRecording(context: Context) {
        val status = ScreenRecordingService.recordingStatus.value
        val activeRecording = ScreenRecordingService.isStartingOrRecording ||
            status is RecordingStatus.Recording ||
            status is RecordingStatus.Paused
        if (activeRecording && Settings.canDrawOverlays(context)) {
            val syncIntent = Intent(context, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_SYNC_MINI_BAR
            }
            context.startService(syncIntent)
        } else if (!activeRecording && FloatingOverlayService.isDexMiniBarVisible) {
            val hideIntent = Intent(context, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_HIDE_MINI_BAR
            }
            context.startService(hideIntent)
        }
    }

    fun updateFloatingOpacity(context: Context, opacity: Float) {
        updateSettings { it.copy(floatingBallOpacity = opacity) }
        if (Settings.canDrawOverlays(context) && FloatingOverlayService.isFloatingBallVisible) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_UPDATE_OPACITY
                putExtra(FloatingOverlayService.EXTRA_OPACITY, opacity)
            }
            context.startService(intent)
        }
    }

    fun updateFloatingBubbleSize(context: Context, sizePercent: Int) {
        val sanitized = SettingsPreferences.sanitizeSizePercent(sizePercent)
        updateSettings { it.copy(floatingBubbleSizePercent = sanitized) }
        if (Settings.canDrawOverlays(context) && FloatingOverlayService.isFloatingBallVisible) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_UPDATE_BUBBLE_SIZE
                putExtra(FloatingOverlayService.EXTRA_BUBBLE_SIZE_PERCENT, sanitized)
            }
            context.startService(intent)
        }
    }

    fun resetFloatingBubbleSize(context: Context) {
        updateFloatingBubbleSize(context, SettingsPreferences.DEFAULT_BUBBLE_SIZE_PERCENT)
    }

    fun updateDexMiniBarSize(context: Context, sizePercent: Int) {
        val sanitizedSize = SettingsPreferences.sanitizeSizePercent(sizePercent)
        val currentOpacity = _settings.value.dexMiniBarOpacityPercent
        updateSettings { it.copy(dexMiniBarSizePercent = sanitizedSize) }
        syncMiniBarStyleIfVisible(context, sanitizedSize, currentOpacity)
    }

    fun updateDexMiniBarOpacity(context: Context, opacityPercent: Int) {
        val sanitizedOpacity = SettingsPreferences.sanitizeMiniBarOpacityPercent(opacityPercent)
        val currentSize = _settings.value.dexMiniBarSizePercent
        updateSettings { it.copy(dexMiniBarOpacityPercent = sanitizedOpacity) }
        syncMiniBarStyleIfVisible(context, currentSize, sanitizedOpacity)
    }

    fun resetDexMiniBarStyle(context: Context) {
        val defaultSize = SettingsPreferences.DEFAULT_MINI_BAR_SIZE_PERCENT
        val defaultOpacity = SettingsPreferences.DEFAULT_MINI_BAR_OPACITY_PERCENT
        updateSettings {
            it.copy(
                dexMiniBarSizePercent = defaultSize,
                dexMiniBarOpacityPercent = defaultOpacity
            )
        }
        syncMiniBarStyleIfVisible(context, defaultSize, defaultOpacity)
    }

    private fun syncMiniBarStyleIfVisible(context: Context, sizePercent: Int, opacityPercent: Int) {
        if (Settings.canDrawOverlays(context) &&
            (FloatingOverlayService.isDexMiniBarVisible || FloatingOverlayService.isMiniControlVisible)
        ) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_UPDATE_MINI_BAR_STYLE
                putExtra(FloatingOverlayService.EXTRA_MINI_BAR_SIZE_PERCENT, sizePercent)
                putExtra(FloatingOverlayService.EXTRA_MINI_BAR_OPACITY_PERCENT, opacityPercent)
            }
            context.startService(intent)
        }
    }

    fun moveToTrash(id: Long) {
        viewModelScope.launch {
            repository.moveToTrash(id)
        }
    }

    fun restoreFromTrash(id: Long) {
        viewModelScope.launch {
            repository.restoreFromTrash(id)
        }
    }

    fun deletePermanently(video: RecordedVideo) {
        viewModelScope.launch {
            repository.deletePermanently(video)
        }
    }

    fun clearAllTrash() {
        viewModelScope.launch {
            val trashList = trashVideos.value
            for (video in trashList) {
                repository.deletePermanently(video)
            }
        }
    }

    fun selectVideoForEdit(video: RecordedVideo?) {
        _selectedVideoForEdit.value = video
        _editingStatusMessage.value = null
    }

    fun trimSelectedVideo(context: Context, startMs: Long, endMs: Long, speed: Float) {
        val video = _selectedVideoForEdit.value ?: return
        viewModelScope.launch {
            _editingStatusMessage.value = "Processando vídeo com aceleração por hardware..."
            when (val result = VideoEditorManager.trimVideo(context, video, startMs, endMs, speed)) {
                is VideoEditorManager.EditResult.Success -> {
                    _editingStatusMessage.value = "Vídeo salvo com sucesso! Novo clipe disponível."
                    _selectedVideoForEdit.value = result.newVideo
                }
                is VideoEditorManager.EditResult.Error -> {
                    _editingStatusMessage.value = "Erro: ${result.message}"
                }
            }
        }
    }

    fun pauseRecording(context: Context) {
        val intent = Intent(context, ScreenRecordingService::class.java).apply {
            action = ScreenRecordingService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resumeRecording(context: Context) {
        val intent = Intent(context, ScreenRecordingService::class.java).apply {
            action = ScreenRecordingService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun stopRecording(context: Context) {
        val intent = Intent(context, ScreenRecordingService::class.java).apply {
            action = ScreenRecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun refreshVideos() {
        repository.refreshVideos()
    }
}
