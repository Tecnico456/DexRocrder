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
import com.example.model.FpsOption
import com.example.model.RecordedVideo
import com.example.model.RecordingSettings
import com.example.model.RecordingStatus
import com.example.model.ResolutionOption
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

    private val _settings = MutableStateFlow(ScreenRecordingService.currentSettings)
    val settings: StateFlow<RecordingSettings> = _settings.asStateFlow()

    private val _selectedVideoForEdit = MutableStateFlow<RecordedVideo?>(null)
    val selectedVideoForEdit: StateFlow<RecordedVideo?> = _selectedVideoForEdit.asStateFlow()

    private val _editingStatusMessage = MutableStateFlow<String?>(null)
    val editingStatusMessage: StateFlow<String?> = _editingStatusMessage.asStateFlow()

    fun updateSettings(transform: (RecordingSettings) -> RecordingSettings) {
        val newSettings = transform(_settings.value)
        _settings.value = newSettings
        ScreenRecordingService.currentSettings = newSettings
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

    fun toggleFloatingBall(context: Context, enabled: Boolean) {
        updateSettings { it.copy(showFloatingBall = enabled) }
        if (Settings.canDrawOverlays(context)) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = if (enabled) FloatingOverlayService.ACTION_SHOW_BALL else FloatingOverlayService.ACTION_HIDE_BALL
            }
            context.startService(intent)
        }
    }

    fun updateFloatingOpacity(context: Context, opacity: Float) {
        updateSettings { it.copy(floatingBallOpacity = opacity) }
        if (Settings.canDrawOverlays(context)) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_UPDATE_OPACITY
                putExtra(FloatingOverlayService.EXTRA_OPACITY, opacity)
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
