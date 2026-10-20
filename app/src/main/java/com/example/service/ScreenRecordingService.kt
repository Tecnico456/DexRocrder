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
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
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
 * Foreground Service for Screen Recording with MediaProjection and Samsung DeX Notification Control Bar.
 * Adheres strictly to Android 14 (API 34+) requirements:
 * 1. startForeground() is invoked with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
 *    immediately upon start with valid MediaProjection authorization.
 * 2. MediaProjection callback is registered.
 * 3. Notification channel is configured with IMPORTANCE_LOW.
 * 4. Ongoing custom RemoteViews notification (notification_dex_controls.xml) uses a native <Chronometer>
 *    (eliminating 1-second notify() loops and SystemUI Rate Limiting in Samsung DeX), status indicator dot,
 *    and full control buttons (Record, Pause/Resume, Stop, Brush, Facecam, Close) in collapsed and expanded states.
 */
class ScreenRecordingService : Service() {

    companion object {
        private const val LEGACY_CHANNEL_ID = "dex_recorder_channel"
        const val CHANNEL_ID = "dex_recorder_control_channel_v2"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START"
        const val ACTION_PAUSE = "com.example.action.PAUSE"
        const val ACTION_RESUME = "com.example.action.RESUME"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_CLOSE_NOTIFICATION = "com.example.action.CLOSE_NOTIFICATION"
        const val ACTION_TOGGLE_BRUSH_FROM_NOTIF = "com.example.action.NOTIF_TOGGLE_BRUSH"
        const val ACTION_TOGGLE_FACECAM_FROM_NOTIF = "com.example.action.NOTIF_TOGGLE_FACECAM"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SETTINGS = "extra_settings"

        private val _recordingStatus = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
        val recordingStatus: StateFlow<RecordingStatus> = _recordingStatus.asStateFlow()

        var currentSettings = RecordingSettings()

        @Volatile
        var isStartingOrRecording: Boolean = false
            private set

        @Volatile
        var chronometerBaseRealtime: Long = 0L
            private set

        @Volatile
        private var lastNotifiedSignature: String? = null

        /**
         * Prepara o estado imediatamente após o usuário aprovar o MediaProjection na MainActivity,
         * evitando que onResume() ou outros serviços sobrescrevam a notificação com o estado ocioso.
         */
        fun prepareForRecordingStart() {
            isStartingOrRecording = true
            chronometerBaseRealtime = SystemClock.elapsedRealtime()
            _recordingStatus.value = RecordingStatus.Recording(0L)
        }

        /**
         * Identificar se o Samsung DeX está ativo.
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

        fun formatDurationStatic(durationMs: Long): String {
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / (1000 * 60)) % 60
            val hours = durationMs / (1000 * 60 * 60)
            return if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                try {
                    manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
                } catch (_: Exception) {}

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "DexRecorder Controles e Gravação",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Barra de controle permanente para o Samsung DeX e gravação de tela"
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
        }

        /**
         * TAREFA 1, 2 & 3: Constrói o RemoteViews customizado (res/layout/notification_dex_controls.xml)
         * usando <Chronometer> nativo (setChronometer) para contagem em tempo real sem loop de notify(),
         * sincronizado com o estado de gravação (isRecording, isPaused) e ferramentas (Pincel e Facecam).
         */
        fun buildCustomControlRemoteViews(
            context: Context,
            isRecording: Boolean,
            isPaused: Boolean,
            elapsedText: String = "00:00",
            isBrushActive: Boolean = FloatingOverlayService.isBrushActive,
            isFacecamActive: Boolean = FloatingOverlayService.isFacecamActive,
            blinkPhase: Boolean = true,
            chronometerBase: Long = if (chronometerBaseRealtime > 0L) chronometerBaseRealtime else SystemClock.elapsedRealtime()
        ): RemoteViews {
            val remoteViews = RemoteViews(context.packageName, R.layout.notification_dex_controls)

            // 1. Indicador de Status e <Chronometer> Nativo (sem loop de 1s de notify)
            when {
                isRecording && !isPaused -> {
                    val dotRes = if (blinkPhase) R.drawable.dot_status_recording else R.drawable.dot_status_recording_dim
                    remoteViews.setImageViewResource(R.id.notif_status_dot, dotRes)
                    remoteViews.setChronometer(R.id.notif_chronometer, chronometerBase, null, true)
                    remoteViews.setTextViewText(R.id.notif_status_label, "REC")
                    remoteViews.setTextColor(R.id.notif_status_label, 0xFFFF5252.toInt())
                }
                isRecording && isPaused -> {
                    remoteViews.setImageViewResource(R.id.notif_status_dot, R.drawable.dot_status_paused)
                    remoteViews.setChronometer(R.id.notif_chronometer, chronometerBase, null, false)
                    remoteViews.setTextViewText(R.id.notif_status_label, "PAUSADO")
                    remoteViews.setTextColor(R.id.notif_status_label, 0xFFFFC400.toInt())
                }
                else -> {
                    val nowRealtime = SystemClock.elapsedRealtime()
                    remoteViews.setImageViewResource(R.id.notif_status_dot, R.drawable.dot_status_ready)
                    remoteViews.setChronometer(R.id.notif_chronometer, nowRealtime, null, false)
                    remoteViews.setTextViewText(R.id.notif_chronometer, "00:00")
                    remoteViews.setTextViewText(R.id.notif_status_label, "PRONTO")
                    remoteViews.setTextColor(R.id.notif_status_label, 0xFF00E676.toInt())
                }
            }

            // 2. Visibilidade Dinâmica dos Botões de Gravação (Record vs Pause/Resume + Stop)
            if (!isRecording) {
                remoteViews.setViewVisibility(R.id.btn_notif_record, View.VISIBLE)
                remoteViews.setViewVisibility(R.id.btn_notif_pause_resume, View.GONE)
                remoteViews.setViewVisibility(R.id.btn_notif_stop, View.GONE)
            } else {
                remoteViews.setViewVisibility(R.id.btn_notif_record, View.GONE)
                remoteViews.setViewVisibility(R.id.btn_notif_pause_resume, View.VISIBLE)
                remoteViews.setViewVisibility(R.id.btn_notif_stop, View.VISIBLE)

                if (isPaused) {
                    remoteViews.setImageViewResource(R.id.btn_notif_pause_resume, R.drawable.ic_notif_play)
                } else {
                    remoteViews.setImageViewResource(R.id.btn_notif_pause_resume, R.drawable.ic_notif_pause)
                }
            }

            // 3. Destaque visual das ferramentas Pincel e Facecam
            remoteViews.setInt(
                R.id.btn_notif_brush,
                "setBackgroundResource",
                if (isBrushActive) R.drawable.bg_notif_btn_active else R.drawable.bg_notif_btn_circle
            )
            remoteViews.setInt(
                R.id.btn_notif_facecam,
                "setBackgroundResource",
                if (isFacecamActive) R.drawable.bg_notif_btn_active else R.drawable.bg_notif_btn_circle
            )

            // 4. Configuração de PendingIntents para cada botão de ação
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val mainPendingIntent = PendingIntent.getActivity(
                context,
                200,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            remoteViews.setOnClickPendingIntent(R.id.notif_status_container, mainPendingIntent)

            // Botão Gravar (●): Inicia a gravação via fluxo seguro do MediaProjection na MainActivity
            val recordIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_START_RECORDING_FROM_OVERLAY
                putExtra(MainActivity.EXTRA_AUTO_START_RECORDING, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val recordPendingIntent = PendingIntent.getActivity(
                context,
                201,
                recordIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            remoteViews.setOnClickPendingIntent(R.id.btn_notif_record, recordPendingIntent)

            // Botão Dinâmico Pausar (||) ⇄ Continuar (▶)
            val pauseOrResumeAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            val pauseResumeIntent = Intent(context, ScreenRecordingService::class.java).apply {
                action = pauseOrResumeAction
            }
            val pauseResumePendingIntent = PendingIntent.getService(
                context,
                if (isPaused) 202 else 203,
                pauseResumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            remoteViews.setOnClickPendingIntent(R.id.btn_notif_pause_resume, pauseResumePendingIntent)

            // Botão Parar (■)
            val stopIntent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                context,
                204,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            remoteViews.setOnClickPendingIntent(R.id.btn_notif_stop, stopPendingIntent)

            // Botão Pincel / Desenho na tela (sincronizado com FloatingOverlayService)
            val canDraw = Settings.canDrawOverlays(context)
            val brushPendingIntent = if (canDraw) {
                val brushServiceIntent = Intent(context, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_TOGGLE_BRUSH
                }
                PendingIntent.getService(
                    context,
                    205,
                    brushServiceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                val brushActIntent = Intent(context, MainActivity::class.java).apply {
                    action = FloatingOverlayService.ACTION_TOGGLE_BRUSH
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                PendingIntent.getActivity(
                    context,
                    205,
                    brushActIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            remoteViews.setOnClickPendingIntent(R.id.btn_notif_brush, brushPendingIntent)

            // Botão Câmera / Facecam (sincronizado com FloatingOverlayService)
            val hasCameraPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val facecamPendingIntent = if (canDraw && (hasCameraPerm || isFacecamActive)) {
                val facecamServiceIntent = Intent(context, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_TOGGLE_FACECAM
                }
                PendingIntent.getService(
                    context,
                    206,
                    facecamServiceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                val facecamActIntent = Intent(context, MainActivity::class.java).apply {
                    action = FloatingOverlayService.ACTION_TOGGLE_FACECAM
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                PendingIntent.getActivity(
                    context,
                    206,
                    facecamActIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            remoteViews.setOnClickPendingIntent(R.id.btn_notif_facecam, facecamPendingIntent)

            // Botão Fechar (X)
            val closeIntent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ACTION_CLOSE_NOTIFICATION
            }
            val closePendingIntent = PendingIntent.getService(
                context,
                207,
                closeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            remoteViews.setOnClickPendingIntent(R.id.btn_notif_close, closePendingIntent)

            return remoteViews
        }

        /**
         * Constrói a Notification completa EXCLUSIVAMENTE com RemoteViews (notification_dex_controls.xml)
         * em instâncias separadas para CustomContentView, CustomBigContentView e CustomHeadsUpContentView,
         * com NotificationCompat.DecoratedCustomViewStyle(), setShowWhen(false), setWhen(0L) e SEM
         * setContentTitle / setContentText para impedir colapso do card no Samsung DeX.
         */
        fun buildControlNotification(
            context: Context,
            isRecording: Boolean,
            isPaused: Boolean,
            elapsedText: String = "00:00",
            isBrushActive: Boolean = FloatingOverlayService.isBrushActive,
            isFacecamActive: Boolean = FloatingOverlayService.isFacecamActive,
            blinkPhase: Boolean = true,
            chronometerBase: Long = if (chronometerBaseRealtime > 0L) chronometerBaseRealtime else SystemClock.elapsedRealtime()
        ): Notification {
            ensureNotificationChannel(context)

            val collapsedRemoteViews = buildCustomControlRemoteViews(
                context = context,
                isRecording = isRecording,
                isPaused = isPaused,
                elapsedText = elapsedText,
                isBrushActive = isBrushActive,
                isFacecamActive = isFacecamActive,
                blinkPhase = blinkPhase,
                chronometerBase = chronometerBase
            )
            val expandedRemoteViews = buildCustomControlRemoteViews(
                context = context,
                isRecording = isRecording,
                isPaused = isPaused,
                elapsedText = elapsedText,
                isBrushActive = isBrushActive,
                isFacecamActive = isFacecamActive,
                blinkPhase = blinkPhase,
                chronometerBase = chronometerBase
            )
            val headsUpRemoteViews = buildCustomControlRemoteViews(
                context = context,
                isRecording = isRecording,
                isPaused = isPaused,
                elapsedText = elapsedText,
                isBrushActive = isBrushActive,
                isFacecamActive = isFacecamActive,
                blinkPhase = blinkPhase,
                chronometerBase = chronometerBase
            )

            val mainIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val mainPendingIntent = PendingIntent.getActivity(
                context,
                0,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_rec)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setShowWhen(false)
                .setWhen(0L)
                .setContentIntent(mainPendingIntent)
                .setCustomContentView(collapsedRemoteViews)
                .setCustomBigContentView(expandedRemoteViews)
                .setCustomHeadsUpContentView(headsUpRemoteViews)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSound(null)
                .setVibrate(null)
                .setDefaults(0)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            }

            return builder.build()
        }

        /**
         * Método unificado que exibe ou atualiza a barra de controle na notificação do sistema
         * mantendo sempre o RemoteViews customizado (notification_dex_controls.xml) e seus botões.
         * Deduplica chamadas idênticas para evitar Rate Limiting no SystemUI do Samsung DeX.
         */
        fun showOrUpdateControlNotification(
            context: Context,
            blinkPhase: Boolean = true,
            forceUpdate: Boolean = false
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }
            val status = _recordingStatus.value
            val isRecording = isStartingOrRecording ||
                status is RecordingStatus.Recording ||
                status is RecordingStatus.Paused ||
                status is RecordingStatus.Countdown
            val isPaused = status is RecordingStatus.Paused
            val brushActive = FloatingOverlayService.isBrushActive
            val facecamActive = FloatingOverlayService.isFacecamActive

            val signature = "$isRecording:$isPaused:$brushActive:$facecamActive"
            if (!forceUpdate && signature == lastNotifiedSignature) {
                return
            }
            lastNotifiedSignature = signature

            val durationMs = when (status) {
                is RecordingStatus.Recording -> status.durationMs
                is RecordingStatus.Paused -> status.durationMs
                else -> 0L
            }
            val baseTime = if (isRecording) {
                if (chronometerBaseRealtime > 0L && !isPaused) {
                    chronometerBaseRealtime
                } else {
                    SystemClock.elapsedRealtime() - durationMs
                }
            } else {
                SystemClock.elapsedRealtime()
            }
            val elapsedText = formatDurationStatic(durationMs)
            val notification = buildControlNotification(
                context = context,
                isRecording = isRecording,
                isPaused = isPaused,
                elapsedText = elapsedText,
                isBrushActive = brushActive,
                isFacecamActive = facecamActive,
                blinkPhase = blinkPhase,
                chronometerBase = baseTime
            )
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }

        fun cancelControlNotification(context: Context) {
            lastNotifiedSignature = null
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
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
    private var dismissNotificationOnStop = false

    /**
     * BroadcastReceiver to immediately listen for user action clicks
     * directly from the Ongoing Notification.
     */
    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(tag, "Broadcast action received: ${intent?.action}")
            when (intent?.action) {
                ACTION_PAUSE -> pauseRecording()
                ACTION_RESUME -> resumeRecording()
                ACTION_STOP -> stopRecordingSession(dismissNotification = false)
                ACTION_CLOSE_NOTIFICATION -> handleCloseNotificationAction()
                ACTION_TOGGLE_BRUSH_FROM_NOTIF -> forwardOverlayAction(FloatingOverlayService.ACTION_TOGGLE_BRUSH)
                ACTION_TOGGLE_FACECAM_FROM_NOTIF -> forwardOverlayAction(FloatingOverlayService.ACTION_TOGGLE_FACECAM)
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
            addAction(ACTION_CLOSE_NOTIFICATION)
            addAction(ACTION_TOGGLE_BRUSH_FROM_NOTIF)
            addAction(ACTION_TOGGLE_FACECAM_FROM_NOTIF)
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
                dismissNotificationOnStop = false
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
                    isStartingOrRecording = false
                    lastNotifiedSignature = null
                    val initialNotification = buildNotification(isRecording = false, isPaused = false, elapsedText = "00:00")
                    startForeground(NOTIFICATION_ID, initialNotification)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Atualiza imediatamente o estado para gravando antes do startForeground
                isStartingOrRecording = true
                recordedDurationMs = 0L
                isPaused = false
                chronometerBaseRealtime = SystemClock.elapsedRealtime()
                _recordingStatus.value = RecordingStatus.Recording(0L)

                // INÍCIO SEGURO DO SERVIÇO: startForeground chamado 1 única vez após aprovação
                startForegroundSafely(isPaused = false, elapsedText = "00:00")
                startRecordingSession(resultCode, resultData)
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecordingSession(dismissNotification = false)
            ACTION_CLOSE_NOTIFICATION -> handleCloseNotificationAction()
            ACTION_TOGGLE_BRUSH_FROM_NOTIF -> forwardOverlayAction(FloatingOverlayService.ACTION_TOGGLE_BRUSH)
            ACTION_TOGGLE_FACECAM_FROM_NOTIF -> forwardOverlayAction(FloatingOverlayService.ACTION_TOGGLE_FACECAM)
        }
        return START_NOT_STICKY
    }

    private fun forwardOverlayAction(overlayAction: String) {
        val overlayIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = overlayAction
        }
        startService(overlayIntent)
    }

    private fun handleCloseNotificationAction() {
        // Fecha o Balão Flutuante e ferramentas de sobreposição sincronizadamente
        try {
            val closeOverlayIntent = Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_CLOSE_ALL
            }
            startService(closeOverlayIntent)
        } catch (_: Exception) {}

        val status = _recordingStatus.value
        val activeRecording = isStartingOrRecording ||
            status is RecordingStatus.Recording ||
            status is RecordingStatus.Paused ||
            status is RecordingStatus.Countdown

        if (activeRecording) {
            stopRecordingSession(dismissNotification = true)
        } else {
            cancelControlNotification(this)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            stopSelf()
        }
    }

    /**
     * Safely initiates Foreground Service with appropriate ServiceInfo flags.
     * Guaranteed to be called before getMediaProjection() on Android 14+.
     */
    private fun startForegroundSafely(isPaused: Boolean, elapsedText: String) {
        val notification = buildNotification(isRecording = true, isPaused = isPaused, elapsedText = elapsedText)
        lastNotifiedSignature = "true:$isPaused:${FloatingOverlayService.isBrushActive}:${FloatingOverlayService.isFacecamActive}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val hasMicPerm = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasMicPerm && currentSettings.audioMode != AudioCaptureMode.MUTE) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        isRecording: Boolean = true,
        isPaused: Boolean,
        elapsedText: String,
        blinkPhase: Boolean = true
    ): Notification {
        val baseTime = if (isRecording) {
            SystemClock.elapsedRealtime() - recordedDurationMs
        } else {
            SystemClock.elapsedRealtime()
        }
        chronometerBaseRealtime = baseTime
        return buildControlNotification(
            context = this,
            isRecording = isRecording,
            isPaused = isPaused,
            elapsedText = elapsedText,
            blinkPhase = blinkPhase,
            chronometerBase = baseTime
        )
    }

    private fun updateRecordingNotification(
        isRecording: Boolean = true,
        isPaused: Boolean = this.isPaused,
        elapsedText: String = formatDuration(recordedDurationMs),
        blinkPhase: Boolean = true
    ) {
        lastNotifiedSignature = "$isRecording:$isPaused:${FloatingOverlayService.isBrushActive}:${FloatingOverlayService.isFacecamActive}"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(
                isRecording = isRecording,
                isPaused = isPaused,
                elapsedText = elapsedText,
                blinkPhase = blinkPhase
            )
        )
    }

    private fun startRecordingSession(resultCode: Int, resultData: Intent) {
        isStopping.set(false)
        serviceScope.launch(Dispatchers.Default) {
            try {
                calculateScreenDimensions()

                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                if (projection == null) {
                    isStartingOrRecording = false
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
                    isStartingOrRecording = false
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
                isStartingOrRecording = false
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

    /**
     * TAREFA 1: Atualiza apenas o estado interno para a UI do app e Balão Flutuante.
     * NÃO chama notificationManager.notify() a cada segundo — o <Chronometer> nativo
     * na notificação atualiza o tempo diretamente no SystemUI sem Rate Limiting.
     */
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                if (!isPaused) {
                    recordedDurationMs += 1000
                    _recordingStatus.value = RecordingStatus.Recording(recordedDurationMs)
                }
            }
        }
    }

    private fun formatDuration(durationMs: Long): String = formatDurationStatic(durationMs)

    private fun pauseRecording() {
        if (isPaused) return
        isPaused = true
        if (chronometerBaseRealtime > 0L) {
            recordedDurationMs = (SystemClock.elapsedRealtime() - chronometerBaseRealtime).coerceAtLeast(recordedDurationMs)
        }
        videoEncoder?.pause()
        audioPipeline?.pause()
        _recordingStatus.value = RecordingStatus.Paused(recordedDurationMs)

        val formatted = formatDuration(recordedDurationMs)
        updateRecordingNotification(
            isRecording = true,
            isPaused = true,
            elapsedText = formatted
        )
        Log.d(tag, "Gravação pausada com sucesso via notificação/controle.")
    }

    private fun resumeRecording() {
        if (!isPaused) return
        isPaused = false
        chronometerBaseRealtime = SystemClock.elapsedRealtime() - recordedDurationMs
        videoEncoder?.resume()
        audioPipeline?.resume()
        _recordingStatus.value = RecordingStatus.Recording(recordedDurationMs)

        val formatted = formatDuration(recordedDurationMs)
        updateRecordingNotification(
            isRecording = true,
            isPaused = false,
            elapsedText = formatted
        )
        Log.d(tag, "Gravação retomada com sucesso via notificação/controle.")
    }

    private fun stopRecordingSession(dismissNotification: Boolean = false) {
        if (dismissNotification) {
            dismissNotificationOnStop = true
        }
        if (!isStopping.compareAndSet(false, true)) {
            Log.d(tag, "stopRecordingSession() já em execução. Ignorando chamada duplicada.")
            return
        }
        _recordingStatus.value = RecordingStatus.Stopping
        timerJob?.cancel()
        timerJob = null

        serviceScope.launch(Dispatchers.IO) {
            try {
                // Validação de Trilha no Encerramento
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
                isStartingOrRecording = false
                chronometerBaseRealtime = 0L
                lastNotifiedSignature = null
                _recordingStatus.value = RecordingStatus.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (dismissNotificationOnStop) {
                    cancelControlNotification(this@ScreenRecordingService)
                } else {
                    // Mantém a barra de controle na notificação no estado PRONTO (00:00, botão Gravar visível)
                    showOrUpdateControlNotification(this@ScreenRecordingService, forceUpdate = true)
                }
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        ensureNotificationChannel(this)
    }

    /**
     * Fechar Automaticamente ao Encerrar o App nos Recentes (onTaskRemoved)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val status = _recordingStatus.value
        val isRecording = status is RecordingStatus.Recording ||
            status is RecordingStatus.Paused ||
            status is RecordingStatus.Countdown
        // Se não houver uma gravação ativa no momento, fecha a bola flutuante e a notificação ao fechar o app
        if (!isRecording) {
            cancelControlNotification(this)
            stopService(Intent(this, FloatingOverlayService::class.java))
            stopSelf()
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

