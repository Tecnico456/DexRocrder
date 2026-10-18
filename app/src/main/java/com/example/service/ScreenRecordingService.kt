package com.example.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.DexRecorderApplication
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioCaptureAndEncoder
import com.example.model.AudioCaptureMode
import com.example.model.RecordedVideo
import com.example.model.RecordingSettings
import com.example.model.RecordingStatus
import com.example.model.ResolutionOption
import com.example.model.SettingsPreferences
import com.example.storage.MediaStoreHelper
import com.example.video.MediaMuxerManager
import com.example.video.ScreenVideoEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground Service for Screen Recording with MediaProjection.
 * Adheres strictly to Android 14 (API 34+) requirements:
 * 1. startForeground() is invoked with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
 *    immediately upon start with valid MediaProjection authorization.
 * 2. MediaProjection callback is registered.
 * 3. Notification channel is configured with IMPORTANCE_LOW.
 * 4. Ongoing notification includes action buttons for Pause, Resume, and Stop & Close.
 */
class ScreenRecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "dex_recorder_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START"
        const val ACTION_PAUSE = "com.example.action.PAUSE"
        const val ACTION_RESUME = "com.example.action.RESUME"
        const val ACTION_STOP = "com.example.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SETTINGS = "extra_settings"

        private val _recordingStatus = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
        val recordingStatus: StateFlow<RecordingStatus> = _recordingStatus.asStateFlow()

        var currentSettings = RecordingSettings()

        /**
         * TAREFA 1: Identificar se o DeX está ativo.
         * Verifique se o dispositivo está em modo Desktop:
         */
        fun isDeXMode(context: Context): Boolean {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val isDeskMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_DESK
            val config = context.resources.configuration
            val isSemDesk = try {
                val field = config.javaClass.getField("semDesktopModeEnabled")
                field.getInt(config) == 1
            } catch (e: Exception) {
                false
            }
            return isDeskMode || isSemDesk
        }
    }

    private val tag = "ScreenRecordingService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaProjection: MediaProjection? = null
    private var muxerManager: MediaMuxerManager? = null
    private var videoEncoder: ScreenVideoEncoder? = null
    private var audioPipeline: AudioCaptureAndEncoder? = null
    private var mediaStoreOutput: MediaStoreHelper.MediaStoreOutput? = null

    private var timerJob: Job? = null
    private var recordedDurationMs: Long = 0L
    private var isPaused = false

    private var videoWidth = 1920
    private var videoHeight = 1080
    private var screenDpi = 320
    private val isStopping = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * BroadcastReceiver to immediately listen for user action clicks
     * (Pausar Gravação, Continuar Gravação, Parar e Fechar) directly from the Ongoing Notification.
     */
    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(tag, "Broadcast action received: ${intent?.action}")
            when (intent?.action) {
                ACTION_PAUSE -> pauseRecording()
                ACTION_RESUME -> resumeRecording()
                ACTION_STOP -> stopRecordingSession()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        currentSettings = SettingsPreferences.load(this)
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationActionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notificationActionReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(tag, "onStartCommand received action: $action")

        when (action) {
            ACTION_START -> {
                currentSettings = SettingsPreferences.load(this)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                // Security check for Android 14+: startForeground MUST only be called if authorized
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    Log.e(tag, "Intent de início inválida: resultCode não é RESULT_OK ou dados nulos")
                    // If started as foreground service, satisfy system requirement before stopping
                    val initialNotification = buildNotification(isPaused = false, elapsedText = "00:00")
                    startForeground(NOTIFICATION_ID, initialNotification)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // INÍCIO SEGURO DO SERVIÇO: startForeground DEPOIS do clique em Permitir
                startForegroundSafely(isPaused = false, elapsedText = "00:00")
                startRecordingSession(resultCode, resultData)
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecordingSession()
        }
        return START_NOT_STICKY
    }

    /**
     * Safely initiates Foreground Service with appropriate ServiceInfo flags.
     * Guaranteed to be called before getMediaProjection() on Android 14+.
     */
    private fun startForegroundSafely(isPaused: Boolean, elapsedText: String) {
        val notification = buildNotification(isPaused, elapsedText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (API 34/35) requires FOREGROUND_SERVICE_TYPE_MICROPHONE for AudioPlaybackCapture & AudioRecord
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Builds the Ongoing Notification (Notificação Permanente) with:
     * - Importance LOW channel (no audio chiming while keeping persistent visibility)
     * - Dynamic title and elapsed recording time
     * - Action 1: "Pausar Gravação" or "Continuar Gravação"
     * - Action 2: "Parar e Fechar"
     */
    private fun buildNotification(isPaused: Boolean, elapsedText: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PendingIntent for Pause
        val pauseIntent = Intent(ACTION_PAUSE).setPackage(packageName)
        val pausePendingIntent = PendingIntent.getBroadcast(
            this,
            101,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PendingIntent for Resume
        val resumeIntent = Intent(ACTION_RESUME).setPackage(packageName)
        val resumePendingIntent = PendingIntent.getBroadcast(
            this,
            102,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PendingIntent for Stop & Close
        val stopIntent = Intent(ACTION_STOP).setPackage(packageName)
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            103,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isPaused) "Gravação Pausada" else "DexRecorder: Gravando..."
        val content = if (isPaused) {
            "Pausado em $elapsedText • Toque para continuar"
        } else {
            "Tempo decorrido: $elapsedText • Gravação ativa"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification_rec)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        // Add Pause or Resume action dynamically
        if (isPaused) {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Continuar Gravação",
                resumePendingIntent
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pausar Gravação",
                pausePendingIntent
            )
        }

        // Add Stop & Close action
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Parar e Fechar",
            stopPendingIntent
        )

        return builder.build()
    }

    private fun startRecordingSession(resultCode: Int, resultData: Intent) {
        isStopping.set(false)
        serviceScope.launch(Dispatchers.Default) {
            val countdown = currentSettings.countdownSeconds
            if (countdown > 0) {
                for (i in countdown downTo 1) {
                    _recordingStatus.value = RecordingStatus.Countdown(i)
                    delay(1000)
                }
            }

            try {
                calculateScreenDimensions()

                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                if (projection == null) {
                    _recordingStatus.value = RecordingStatus.Error("Falha ao obter MediaProjection do sistema.")
                    stopSelf()
                    return@launch
                }

                // Android 14 requires a registered callback on MediaProjection
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(tag, "MediaProjection finalizado pelo sistema ou usuário")
                        stopRecordingSession()
                    }
                }, Handler(Looper.getMainLooper()))
                mediaProjection = projection

                // Prepare MediaStore output file
                val output = MediaStoreHelper.createVideoOutput(this@ScreenRecordingService)
                if (output == null) {
                    _recordingStatus.value = RecordingStatus.Error("Falha ao criar arquivo de mídia via MediaStore.")
                    stopSelf()
                    return@launch
                }
                mediaStoreOutput = output

                val expectedTracks = if (currentSettings.audioMode == AudioCaptureMode.MUTE) 1 else 2
                muxerManager = MediaMuxerManager(outputFd = output.pfd.fileDescriptor, expectedTrackCount = expectedTracks)

                // Initialize Video Encoder (with KEY_PRIORITY, KEY_OPERATING_RATE, dynamic bitrate, and VideoCapabilities fallback)
                val videoEnc = ScreenVideoEncoder(
                    mediaProjection = projection,
                    width = videoWidth,
                    height = videoHeight,
                    dpi = screenDpi,
                    fps = currentSettings.fps.fps,
                    bitrate = currentSettings.bitrate.bps,
                    useHevc = currentSettings.useHevcCodec,
                    muxerManager = muxerManager!!
                )
                videoEnc.prepare()
                videoWidth = videoEnc.actualWidth
                videoHeight = videoEnc.actualHeight
                videoEnc.startEncoding(serviceScope)
                videoEncoder = videoEnc

                // TAREFA 1, 2 e 3: Inicializa o pipeline unificado de áudio (48.000 Hz, Estéreo, AAC LC síncrono com AudioPlaybackCapture, Mixer ShortArray e Detector de Bloqueio DRM)
                if (currentSettings.audioMode != AudioCaptureMode.MUTE) {
                    val audioPipe = AudioCaptureAndEncoder(
                        context = this@ScreenRecordingService,
                        mediaProjection = projection,
                        audioMode = currentSettings.audioMode,
                        muxerManager = muxerManager!!,
                        internalVolume = currentSettings.internalAudioVolume,
                        micVolume = currentSettings.micAudioVolume
                    )
                    audioPipe.prepare()
                    audioPipe.start()
                    audioPipeline = audioPipe
                }

                recordedDurationMs = 0L
                isPaused = false
                _recordingStatus.value = RecordingStatus.Recording(0L)
                startTimer()
            } catch (e: Exception) {
                Log.e(tag, "Erro ao iniciar sessão de gravação", e)
                _recordingStatus.value = RecordingStatus.Error("Erro ao iniciar gravação: ${e.message}")
                stopRecordingSession()
            }
        }
    }

    /**
     * OTIMIZAÇÃO PARA O MODO SAMSUNG DEX E ORIENTAÇÃO DINÂMICA (720p, 1080p, 2K 1440p, 4K e Nativo)
     *
     * - Se isDeXMode(this) for verdadeiro, busca o monitor do DeX no DisplayManager (displayId != DEFAULT_DISPLAY).
     * - No modo DeX:
     *   - Garante formato Paisagem Widescreen (16:9) invertendo largura e altura se rawWidth < rawHeight.
     *   - Se o DPI do monitor for reportado incorretamente como alto (> 320), define o padrão desktop mdpi (160).
     *   - Se a resolução for NATIVE, utiliza a resolução exata do monitor externo DeX; caso contrário, aplica a
     *     resolução selecionada (720p, 1080p, 2K 2560x1440, 4K 3840x2160) em Paisagem.
     * - No modo convencional (celular):
     *   - Respeita a orientação (Paisagem: 2560x1440 em 2K / Retrato: 1440x2560 em 2K, múltiplos de 16).
     */
    private fun calculateScreenDimensions() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val inDeX = isDeXMode(this)

        val targetDisplay: Display = if (inDeX) {
            // O monitor externo do DeX é o display secundário (ID diferente de DEFAULT_DISPLAY)
            displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
                ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        targetDisplay.getRealMetrics(metrics)

        var rawWidth = metrics.widthPixels
        var rawHeight = metrics.heightPixels
        var densityDpi = metrics.densityDpi

        if (inDeX) {
            // Garantir formato Paisagem Widescreen (16:9) no modo DeX
            if (rawWidth < rawHeight) {
                val temp = rawWidth
                rawWidth = rawHeight
                rawHeight = temp
            }
            // Se o DPI do monitor for reportado incorretamente como alto, defina o padrão desktop mdpi (160)
            if (densityDpi > 320) densityDpi = 160
            screenDpi = densityDpi

            if (currentSettings.resolution == ResolutionOption.NATIVE) {
                videoWidth = if (rawWidth % 2 == 0) rawWidth else rawWidth - 1
                videoHeight = if (rawHeight % 2 == 0) rawHeight else rawHeight - 1
            } else {
                val maxTarget = maxOf(currentSettings.resolution.width, currentSettings.resolution.height)
                val minTarget = minOf(currentSettings.resolution.width, currentSettings.resolution.height)
                videoWidth = if (currentSettings.resolution == ResolutionOption.RES_2K) {
                    ScreenVideoEncoder.alignTo16(maxTarget)
                } else {
                    if (maxTarget % 2 == 0) maxTarget else maxTarget - 1
                }
                videoHeight = if (currentSettings.resolution == ResolutionOption.RES_2K) {
                    ScreenVideoEncoder.alignTo16(minTarget)
                } else {
                    if (minTarget % 2 == 0) minTarget else minTarget - 1
                }
            }

            Log.i(tag, "Modo DeX ativo: Monitor Externo configurado para ${videoWidth}x${videoHeight} @ ${screenDpi} DPI (Display ID: ${targetDisplay.displayId})")
        } else {
            @Suppress("DEPRECATION")
            val rotation = targetDisplay.rotation
            val isLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
            screenDpi = densityDpi

            if (currentSettings.resolution == ResolutionOption.NATIVE) {
                // Em resolução nativa, adapta diretamente para a orientação atual
                val rawW = if (isLandscape) maxOf(rawWidth, rawHeight) else minOf(rawWidth, rawHeight)
                val rawH = if (isLandscape) minOf(rawWidth, rawHeight) else maxOf(rawWidth, rawHeight)
                videoWidth = if (rawW % 2 == 0) rawW else rawW - 1
                videoHeight = if (rawH % 2 == 0) rawH else rawH - 1
            } else {
                val targetDimension1 = currentSettings.resolution.width
                val targetDimension2 = currentSettings.resolution.height
                val maxTarget = maxOf(targetDimension1, targetDimension2)
                val minTarget = minOf(targetDimension1, targetDimension2)

                val (outW, outH) = if (isLandscape) {
                    // Modo Paisagem: Largura maior (ex: 2560 em 2K), Altura menor (ex: 1440 em 2K)
                    Pair(maxTarget, minTarget)
                } else {
                    // Modo Retrato: Largura menor (ex: 1440 em 2K), Altura maior (ex: 2560 em 2K)
                    Pair(minTarget, maxTarget)
                }

                if (currentSettings.resolution == ResolutionOption.RES_2K) {
                    // TAREFA 2: Garante múltiplos de 16 para 2K (2560x1440 / 1440x2560)
                    videoWidth = ScreenVideoEncoder.alignTo16(outW)
                    videoHeight = ScreenVideoEncoder.alignTo16(outH)
                } else {
                    videoWidth = if (outW % 2 == 0) outW else outW - 1
                    videoHeight = if (outH % 2 == 0) outH else outH - 1
                }
            }

            Log.d(tag, "Modo Celular ativo: ${videoWidth}x${videoHeight} @ ${screenDpi} DPI (Rotação=$rotation, Paisagem=$isLandscape)")
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                if (!isPaused) {
                    recordedDurationMs += 1000
                    _recordingStatus.value = RecordingStatus.Recording(recordedDurationMs)

                    val formatted = formatDuration(recordedDurationMs)
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(isPaused = false, elapsedText = formatted))
                }
            }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun pauseRecording() {
        if (isPaused) return
        isPaused = true
        videoEncoder?.pause()
        audioPipeline?.pause()
        _recordingStatus.value = RecordingStatus.Paused(recordedDurationMs)

        val formatted = formatDuration(recordedDurationMs)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(isPaused = true, elapsedText = formatted))
        Log.d(tag, "Gravação pausada com sucesso via notificação/controle.")
    }

    private fun resumeRecording() {
        if (!isPaused) return
        isPaused = false
        videoEncoder?.resume()
        audioPipeline?.resume()
        _recordingStatus.value = RecordingStatus.Recording(recordedDurationMs)

        val formatted = formatDuration(recordedDurationMs)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(isPaused = false, elapsedText = formatted))
        Log.d(tag, "Gravação retomada com sucesso via notificação/controle.")
    }

    private fun stopRecordingSession() {
        if (!isStopping.compareAndSet(false, true)) {
            Log.d(tag, "stopRecordingSession() já em execução. Ignorando chamada duplicada.")
            return
        }
        _recordingStatus.value = RecordingStatus.Stopping
        timerJob?.cancel()
        timerJob = null

        serviceScope.launch(Dispatchers.IO) {
            try {
                // TAREFA 3: Validação de Trilha no Encerramento
                // Envia BUFFER_FLAG_END_OF_STREAM para o codec de áudio e drena todos os pacotes antes de mediaMuxer.stop()
                audioPipeline?.stop()
                audioPipeline = null

                videoEncoder?.stop()
                videoEncoder = null

                muxerManager?.stopAndRelease()
                muxerManager = null

                mediaProjection?.stop()
                mediaProjection = null

                mediaStoreOutput?.let { out ->
                    try {
                        out.pfd.close()
                    } catch (_: Exception) {}

                    MediaStoreHelper.finishPendingVideo(this@ScreenRecordingService, out.uri)

                    val videoFile = File(out.approximatePath)
                    val size = if (videoFile.exists()) videoFile.length() else 0L
                    val mediaId = out.uri.lastPathSegment?.toLongOrNull() ?: System.currentTimeMillis()

                    val recordedVideo = RecordedVideo(
                        id = mediaId,
                        title = out.displayName,
                        filePath = out.approximatePath,
                        uriString = out.uri.toString(),
                        durationMs = recordedDurationMs,
                        sizeBytes = size,
                        width = videoWidth,
                        height = videoHeight,
                        fps = currentSettings.fps.fps,
                        audioSource = currentSettings.audioMode.name,
                        createdAt = System.currentTimeMillis()
                    )

                    val app = application as? DexRecorderApplication
                    app?.videoRepository?.saveRecordedVideo(recordedVideo)
                    Log.d(tag, "Vídeo gravado e indexado com sucesso no Room: ${recordedVideo.title}")
                }
            } catch (e: Exception) {
                Log.e(tag, "Erro ao finalizar gravação", e)
            } finally {
                _recordingStatus.value = RecordingStatus.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DexRecorder Gravação",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação permanente durante a gravação de tela"
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                setSound(null, null)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            audioPipeline?.stop()
        } catch (_: Exception) {}
        try {
            videoEncoder?.stop()
        } catch (_: Exception) {}
        try {
            muxerManager?.stopAndRelease()
        } catch (_: Exception) {}
        try {
            unregisterReceiver(notificationActionReceiver)
        } catch (_: Exception) {}
        serviceScope.cancel()
    }
}
