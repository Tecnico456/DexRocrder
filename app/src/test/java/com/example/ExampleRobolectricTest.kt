package com.example

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.ScreenRecordingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("DexRecorder", appName)
    }

    @Test
    fun `verify service action constants`() {
        assertEquals("com.example.action.START", ScreenRecordingService.ACTION_START)
        assertEquals("com.example.action.PAUSE", ScreenRecordingService.ACTION_PAUSE)
        assertEquals("com.example.action.RESUME", ScreenRecordingService.ACTION_RESUME)
        assertEquals("com.example.action.STOP", ScreenRecordingService.ACTION_STOP)
        assertEquals("dex_recorder_control_channel_v2", ScreenRecordingService.CHANNEL_ID)
    }

    @Test
    fun `verify isDeXMode function executes without crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inDex = ScreenRecordingService.isDeXMode(context)
        // In standard Robolectric test environment, desktop mode is false
        assertEquals(false, inDex)
    }

    @Test
    fun `verify PCM ShortArray mixer sums, applies noise gate, and clamps properly`() {
        val internalShorts = shortArrayOf(0, 0, 25000, -25000, 10000, -10000)
        // Samples 4 and 5 (80, -120) are low-level mic hiss below NOISE_GATE_THRESHOLD (180)
        val micShorts = shortArrayOf(12000, -8000, 15000, -15000, 80, -120)
        val outputShorts = ShortArray(6)

        com.example.audio.AudioCaptureAndEncoder.mixPcmShortBuffers(
            internalBuffer = internalShorts,
            internalShortsRead = 6,
            internalVol = 1.0f,
            micBuffer = micShorts,
            micShortsRead = 6,
            micVol = 1.0f,
            outputShorts = outputShorts,
            sampleCount = 6
        )

        // When internal channel delivers 0 (DRM blocked), active mic audio passes through intact
        assertEquals(12000.toShort(), outputShorts[0])
        assertEquals((-8000).toShort(), outputShorts[1])
        // Clamping at Short.MAX_VALUE and Short.MIN_VALUE
        assertEquals(Short.MAX_VALUE, outputShorts[2])
        assertEquals(Short.MIN_VALUE, outputShorts[3])
        // Noise Gate blocks microphone hiss (< 180) from polluting clean internal game audio
        assertEquals(10000.toShort(), outputShorts[4])
        assertEquals((-10000).toShort(), outputShorts[5])
        // Verify AAC bitrate is 192 kbps
        assertEquals(192_000, com.example.audio.AudioCaptureAndEncoder.AUDIO_BIT_RATE)
    }

    @Test
    fun `verify blocked audio zero buffer detector on ByteArray and direct ByteBuffer`() {
        val zeroBuffer = ByteArray(4096)
        assertEquals(true, com.example.audio.AudioCaptureAndEncoder.isBufferAllZeros(zeroBuffer, 4096))

        zeroBuffer[100] = 5
        assertEquals(false, com.example.audio.AudioCaptureAndEncoder.isBufferAllZeros(zeroBuffer, 4096))

        val directBuf = java.nio.ByteBuffer.allocateDirect(1024)
        assertEquals(true, com.example.audio.AudioCaptureAndEncoder.isDirectBufferAllZeros(directBuf, 1024))
        directBuf.put(512, 1.toByte())
        assertEquals(false, com.example.audio.AudioCaptureAndEncoder.isDirectBufferAllZeros(directBuf, 1024))

        assertEquals(
            "Aviso: Este aplicativo/jogo bloqueia a gravação de áudio interno pelo Android.",
            com.example.audio.AudioCaptureAndEncoder.BLOCKED_AUDIO_WARNING_MESSAGE
        )
    }

    @Test
    fun `verify capture mode ordering, 2K resolution, 90 and 120 FPS ordering, dynamic bitrate, and SharedPreferences persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // TAREFA 1: "Tela cheia" at the top (index 0) and selected by default, "Um único aplicativo" below (index 1)
        val captureModes = com.example.model.ScreenCaptureMode.values()
        assertEquals(2, captureModes.size)
        assertEquals(com.example.model.ScreenCaptureMode.FULL_SCREEN, captureModes[0])
        assertEquals("Tela cheia", captureModes[0].title)
        assertEquals(com.example.model.ScreenCaptureMode.SINGLE_APP, captureModes[1])
        assertEquals("Um único aplicativo", captureModes[1].title)

        val defaultSettings = com.example.model.RecordingSettings()
        assertEquals(com.example.model.ScreenCaptureMode.FULL_SCREEN, defaultSettings.captureMode)
        // TAREFA 4: Floating Ball active (true) by default
        assertEquals(true, defaultSettings.showFloatingBall)

        // TAREFA 2: Resolutions strictly ordered: 720p, 1080p, 2K, 4K, Nativo
        val resolutions = com.example.model.ResolutionOption.values()
        assertEquals(
            listOf("720p", "1080p", "2K", "4K", "Nativo"),
            resolutions.map { it.shortLabel }
        )
        assertEquals(2560, com.example.model.ResolutionOption.RES_2K.width)
        assertEquals(1440, com.example.model.ResolutionOption.RES_2K.height)
        assertEquals(0, com.example.model.ResolutionOption.RES_2K.width % 16)
        assertEquals(0, com.example.model.ResolutionOption.RES_2K.height % 16)

        // TAREFA 3: FPS strictly ordered: 30, 60, 90, 120 FPS
        val fpsList = com.example.model.FpsOption.values().map { it.fps }
        assertEquals(listOf(30, 60, 90, 120), fpsList)

        // TAREFA 3: Dynamic Bitrate scaling between 16 Mbps and 40 Mbps for 2K/4K and 90/120 FPS
        val bitrate2K120 = com.example.video.ScreenVideoEncoder.calculateDynamicBitrate(2560, 1440, 120, 12_000_000)
        assertEquals(true, bitrate2K120 in 16_000_000..40_000_000)
        val bitrate4K90 = com.example.video.ScreenVideoEncoder.calculateDynamicBitrate(3840, 2160, 90, 12_000_000)
        assertEquals(true, bitrate4K90 in 16_000_000..40_000_000)

        // TAREFA 5: SharedPreferences persistence ("dexrecorder_prefs")
        val customSettings = com.example.model.RecordingSettings(
            captureMode = com.example.model.ScreenCaptureMode.SINGLE_APP,
            resolution = com.example.model.ResolutionOption.RES_2K,
            fps = com.example.model.FpsOption.FPS_120,
            audioMode = com.example.model.AudioCaptureMode.INTERNAL_ONLY,
            showFloatingBall = false,
            floatingBallOpacity = 0.6f
        )
        com.example.model.SettingsPreferences.save(context, customSettings)

        val loadedSettings = com.example.model.SettingsPreferences.load(context)
        assertEquals(com.example.model.ScreenCaptureMode.SINGLE_APP, loadedSettings.captureMode)
        assertEquals(com.example.model.ResolutionOption.RES_2K, loadedSettings.resolution)
        assertEquals(com.example.model.FpsOption.FPS_120, loadedSettings.fps)
        assertEquals(com.example.model.AudioCaptureMode.INTERNAL_ONLY, loadedSettings.audioMode)
        assertEquals(false, loadedSettings.showFloatingBall)
        assertEquals(0.6f, loadedSettings.floatingBallOpacity, 0.001f)
    }

    @Test
    fun `verify FloatingBallView clean action buttons, dynamic recording state, drag-to-close target, and FloatingOverlayService onTaskRemoved`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

        var startRecordingTriggered = false
        var closeCallbackTriggered = false
        val ballView = com.example.overlay.FloatingBallView(
            context = context,
            windowManager = wm,
            onStartRecording = { startRecordingTriggered = true },
            onToggleBrush = {},
            onToggleFacecam = {},
            onCloseBall = { closeCallbackTriggered = true }
        )

        // TAREFA 1 & 3: Verify only 6 buttons exist in menuContainer:
        // Record, Pause/Resume, Stop, Brush, Facecam, Close (no FastRewind or Crosshair)
        assertEquals(6, ballView.menuContainer.childCount)

        // TAREFA 2: When Idle (!isRecording), Record button is visible, Pause/Resume and Stop are GONE
        ballView.updateRecordingState(com.example.model.RecordingStatus.Idle)
        assertEquals(false, ballView.isRecordingState)
        assertEquals(false, ballView.isPausedState)
        assertEquals(android.view.View.VISIBLE, ballView.btnRecord.visibility)
        assertEquals(android.view.View.GONE, ballView.btnPauseResume.visibility)
        assertEquals(android.view.View.GONE, ballView.btnStop.visibility)

        // Clicking Record button triggers start recording callback
        ballView.btnRecord.performClick()
        assertEquals(true, startRecordingTriggered)

        // When Recording (!isPaused), Record is GONE, Pause/Resume ("Pausar Gravação") and Stop are VISIBLE
        ballView.updateRecordingState(com.example.model.RecordingStatus.Recording(5000L))
        assertEquals(true, ballView.isRecordingState)
        assertEquals(false, ballView.isPausedState)
        assertEquals(android.view.View.GONE, ballView.btnRecord.visibility)
        assertEquals(android.view.View.VISIBLE, ballView.btnPauseResume.visibility)
        assertEquals(android.view.View.VISIBLE, ballView.btnStop.visibility)
        assertEquals("Pausar Gravação", ballView.btnPauseResume.contentDescription)
        assertEquals("Parar Gravação", ballView.btnStop.contentDescription)

        // When Paused (isPaused), Pause/Resume switches dynamically to Resume ("Continuar Gravação")
        ballView.updateRecordingState(com.example.model.RecordingStatus.Paused(5000L))
        assertEquals(true, ballView.isRecordingState)
        assertEquals(true, ballView.isPausedState)
        assertEquals(android.view.View.GONE, ballView.btnRecord.visibility)
        assertEquals(android.view.View.VISIBLE, ballView.btnPauseResume.visibility)
        assertEquals(android.view.View.VISIBLE, ballView.btnStop.visibility)
        assertEquals("Continuar Gravação", ballView.btnPauseResume.contentDescription)

        // Verify menu expands on toggleMenu()
        assertEquals(false, ballView.isMenuExpanded)
        ballView.toggleMenu()
        assertEquals(true, ballView.isMenuExpanded)

        // Verify bottom-center drag-to-close zone detection
        val metrics = context.resources.displayMetrics
        val centerBottomX = metrics.widthPixels / 2f
        val centerBottomY = metrics.heightPixels - (64 * metrics.density)
        assertEquals(true, ballView.isInsideCloseTarget(centerBottomX, centerBottomY))
        assertEquals(false, ballView.isInsideCloseTarget(10f, 10f))

        // Verify onTaskRemoved on FloatingOverlayService removes overlay when not recording
        val service = org.robolectric.Robolectric.setupService(com.example.service.FloatingOverlayService::class.java)
        service.onTaskRemoved(android.content.Intent())
        assertEquals(false, com.example.service.FloatingOverlayService.isFloatingBallVisible)
        assertNotNull(ballView.closeTargetOverlay)
        assertEquals(false, closeCallbackTriggered)
    }

    @Test
    fun `verify Samsung DeX Notification RemoteViews layout, native Chronometer, and dynamic button states`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = android.widget.FrameLayout(context)

        // 1. Estado PRONTO (!isRecording)
        val readyRv = ScreenRecordingService.buildCustomControlRemoteViews(
            context = context,
            isRecording = false,
            isPaused = false,
            isBrushActive = false,
            isFacecamActive = false
        )
        val readyView = readyRv.apply(context, parent)
        val readyChronometer = readyView.findViewById<android.widget.Chronometer>(R.id.notif_chronometer)
        val readyStatus = readyView.findViewById<android.widget.TextView>(R.id.notif_status_label)
        val readyRecordBtn = readyView.findViewById<android.widget.ImageButton>(R.id.btn_notif_record)
        val readyPauseResumeBtn = readyView.findViewById<android.widget.ImageButton>(R.id.btn_notif_pause_resume)
        val readyStopBtn = readyView.findViewById<android.widget.ImageButton>(R.id.btn_notif_stop)
        val readyBrushBtn = readyView.findViewById<android.widget.ImageButton>(R.id.btn_notif_brush)
        val readyFacecamBtn = readyView.findViewById<android.widget.ImageButton>(R.id.btn_notif_facecam)
        val readyCloseBtn = readyView.findViewById<android.widget.ImageButton>(R.id.btn_notif_close)

        assertNotNull(readyChronometer)
        assertEquals("00:00", readyChronometer.text.toString())
        assertEquals("PRONTO", readyStatus.text.toString())
        assertEquals(android.view.View.VISIBLE, readyRecordBtn.visibility)
        assertEquals(android.view.View.GONE, readyPauseResumeBtn.visibility)
        assertEquals(android.view.View.GONE, readyStopBtn.visibility)
        assertEquals(android.view.View.VISIBLE, readyBrushBtn.visibility)
        assertEquals(android.view.View.VISIBLE, readyFacecamBtn.visibility)
        assertEquals(android.view.View.VISIBLE, readyCloseBtn.visibility)

        // 2. Estado GRAVANDO (isRecording = true, isPaused = false) com <Chronometer> nativo
        val base105s = android.os.SystemClock.elapsedRealtime() - 105_000L
        val recRv = ScreenRecordingService.buildCustomControlRemoteViews(
            context = context,
            isRecording = true,
            isPaused = false,
            isBrushActive = true,
            isFacecamActive = false,
            chronometerBase = base105s
        )
        val recView = recRv.apply(context, parent)
        val recChronometer = recView.findViewById<android.widget.Chronometer>(R.id.notif_chronometer)
        val recStatus = recView.findViewById<android.widget.TextView>(R.id.notif_status_label)
        val recRecordBtn = recView.findViewById<android.widget.ImageButton>(R.id.btn_notif_record)
        val recPauseResumeBtn = recView.findViewById<android.widget.ImageButton>(R.id.btn_notif_pause_resume)
        val recStopBtn = recView.findViewById<android.widget.ImageButton>(R.id.btn_notif_stop)

        assertEquals(base105s, recChronometer.base)
        assertEquals("REC", recStatus.text.toString())
        assertEquals(android.view.View.GONE, recRecordBtn.visibility)
        assertEquals(android.view.View.VISIBLE, recPauseResumeBtn.visibility)
        assertEquals(android.view.View.VISIBLE, recStopBtn.visibility)

        // 3. Estado PAUSADO (isRecording = true, isPaused = true)
        val pausedRv = ScreenRecordingService.buildCustomControlRemoteViews(
            context = context,
            isRecording = true,
            isPaused = true,
            isBrushActive = false,
            isFacecamActive = true,
            chronometerBase = base105s
        )
        val pausedView = pausedRv.apply(context, parent)
        val pausedChronometer = pausedView.findViewById<android.widget.Chronometer>(R.id.notif_chronometer)
        val pausedStatus = pausedView.findViewById<android.widget.TextView>(R.id.notif_status_label)
        val pausedPauseResumeBtn = pausedView.findViewById<android.widget.ImageButton>(R.id.btn_notif_pause_resume)
        assertEquals(base105s, pausedChronometer.base)
        assertEquals("PAUSADO", pausedStatus.text.toString())
        assertEquals(android.view.View.VISIBLE, pausedPauseResumeBtn.visibility)

        // 4. Transição via reapply: 00:00 PRONTO -> REC
        val base7s = android.os.SystemClock.elapsedRealtime() - 7_000L
        val tick7Rv = ScreenRecordingService.buildCustomControlRemoteViews(
            context = context,
            isRecording = true,
            isPaused = false,
            isBrushActive = false,
            isFacecamActive = false,
            chronometerBase = base7s
        )
        tick7Rv.reapply(context, readyView)
        assertEquals(base7s, readyChronometer.base)
        assertEquals("REC", readyStatus.text.toString())
        assertEquals(android.view.View.GONE, readyRecordBtn.visibility)
        assertEquals(android.view.View.VISIBLE, readyPauseResumeBtn.visibility)
        assertEquals(android.view.View.VISIBLE, readyStopBtn.visibility)
        assertEquals(android.view.View.VISIBLE, readyBrushBtn.visibility)
        assertEquals(android.view.View.VISIBLE, readyFacecamBtn.visibility)
        assertEquals(android.view.View.VISIBLE, readyCloseBtn.visibility)

        // 5. Verifica Notification completa no estilo CUSTOM (contentView, bigContentView, camada reserva nativa + VISIBILITY_PUBLIC)
        val notification = ScreenRecordingService.buildControlNotification(
            context = context,
            isRecording = true,
            isPaused = false,
            chronometerBase = base7s,
            notificationStyle = com.example.model.NotificationControlStyle.CUSTOM,
            tapOpensMiniControl = true
        )
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(ScreenRecordingService.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        @Suppress("DEPRECATION")
        assertNotNull(notification.contentView)
        @Suppress("DEPRECATION")
        assertNotNull(notification.bigContentView)
        assertEquals(0L, notification.`when`)
        assertEquals(false, notification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_WHEN, true))
        assertEquals("DexRecorder • Gravando", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertNotNull(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(2, notification.actions?.size)
        assertEquals("Pausar", notification.actions?.get(0)?.title?.toString())
        assertEquals("Parar", notification.actions?.get(1)?.title?.toString())
        assertEquals(true, (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals(true, (notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE) != 0)

        // 6. Verifica Notification completa no estilo MEDIA_STYLE (MediaStyle compacto, cronômetro nativo, ações Pausar/Continuar + Parar)
        val mediaNotification = ScreenRecordingService.buildControlNotification(
            context = context,
            isRecording = true,
            isPaused = false,
            chronometerBase = base7s,
            notificationStyle = com.example.model.NotificationControlStyle.MEDIA_STYLE,
            tapOpensMiniControl = true
        )
        assertEquals("DexRecorder • Gravando", mediaNotification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertEquals(true, mediaNotification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER, false))
        assertEquals(true, mediaNotification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_WHEN, false))
        val compactActions = mediaNotification.extras.getIntArray(android.app.Notification.EXTRA_COMPACT_ACTIONS)
        assertNotNull(compactActions)
        assertEquals(2, compactActions?.size)
        assertEquals(0, compactActions?.get(0))
        assertEquals(1, compactActions?.get(1))
        assertEquals(2, mediaNotification.actions?.size)
        assertEquals("Pausar", mediaNotification.actions?.get(0)?.title?.toString())
        assertEquals("Parar", mediaNotification.actions?.get(1)?.title?.toString())

        val pausedMediaNotification = ScreenRecordingService.buildControlNotification(
            context = context,
            isRecording = true,
            isPaused = true,
            elapsedText = "00:12",
            chronometerBase = base7s,
            notificationStyle = com.example.model.NotificationControlStyle.MEDIA_STYLE,
            tapOpensMiniControl = false
        )
        assertEquals("DexRecorder • Pausado", pausedMediaNotification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertEquals("Continuar", pausedMediaNotification.actions?.get(0)?.title?.toString())
        assertEquals("Parar", pausedMediaNotification.actions?.get(1)?.title?.toString())

        // 7. Verifica que múltiplas gravações seguidas alocam IDs de notificação exclusivos e inéditos
        // (separados de NOTIFICATION_ID = 1001) para impedir Cooldown / CustomView Stripping na One UI 7.0
        val sessionId1 = ScreenRecordingService.nextNotificationId()
        val sessionId2 = ScreenRecordingService.nextNotificationId()
        val sessionId3 = ScreenRecordingService.nextNotificationId()
        assertEquals(true, sessionId1 > ScreenRecordingService.NOTIFICATION_ID)
        assertEquals(sessionId1 + 1, sessionId2)
        assertEquals(sessionId2 + 1, sessionId3)
        ScreenRecordingService.cancelControlNotification(context)
        assertEquals(ScreenRecordingService.NOTIFICATION_ID, ScreenRecordingService.activeNotificationId)
    }

    @Test
    fun `verify new DeX Mini Bar settings persistence, DexMiniBarView collapse modes, and NotificationMiniControlView`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

        // 1. Verifica valores padrão e persistência no SharedPreferences
        val defaults = com.example.model.RecordingSettings()
        assertEquals(com.example.model.DexMiniBarMode.DEX_ONLY, defaults.dexMiniBarMode)
        assertEquals(com.example.model.MiniBarCollapseBehavior.COLLAPSE_TO_EDGE_TAB, defaults.miniBarCollapseBehavior)
        assertEquals(com.example.model.NotificationControlStyle.CUSTOM, defaults.notificationStyle)
        assertEquals(true, defaults.notificationTapOpensMiniControl)

        val custom = defaults.copy(
            dexMiniBarMode = com.example.model.DexMiniBarMode.ALWAYS,
            miniBarCollapseBehavior = com.example.model.MiniBarCollapseBehavior.HIDE_UNTIL_NOTIFICATION_TAP,
            notificationStyle = com.example.model.NotificationControlStyle.MEDIA_STYLE,
            notificationTapOpensMiniControl = false
        )
        com.example.model.SettingsPreferences.save(context, custom)
        val reloaded = com.example.model.SettingsPreferences.load(context)
        assertEquals(com.example.model.DexMiniBarMode.ALWAYS, reloaded.dexMiniBarMode)
        assertEquals(com.example.model.MiniBarCollapseBehavior.HIDE_UNTIL_NOTIFICATION_TAP, reloaded.miniBarCollapseBehavior)
        assertEquals(com.example.model.NotificationControlStyle.MEDIA_STYLE, reloaded.notificationStyle)
        assertEquals(false, reloaded.notificationTapOpensMiniControl)

        // Persistência de posição (x, y) da Mini Barra DeX
        com.example.model.SettingsPreferences.saveDexMiniBarPosition(context, 120, 80)
        val savedPos = com.example.model.SettingsPreferences.loadDexMiniBarPosition(context)
        assertEquals(Pair(120, 80), savedPos)

        // 2. Verifica DexMiniBarView: estado, pausa/continuar, recolher em aba lateral e ocultar total
        var pauseResumeClicked = 0
        var stopClicked = 0
        var hideTotalClicked = 0
        val miniBar = com.example.overlay.DexMiniBarView(
            context = context,
            windowManager = wm,
            initialCollapseBehavior = com.example.model.MiniBarCollapseBehavior.COLLAPSE_TO_EDGE_TAB,
            onTogglePauseResume = { pauseResumeClicked++ },
            onStopRecording = { stopClicked++ },
            onHideUntilNotificationTap = { hideTotalClicked++ }
        )

        miniBar.updateRecordingState(com.example.model.RecordingStatus.Recording(65_000L))
        assertEquals("01:05", miniBar.timerText.text.toString())
        assertEquals(false, miniBar.isPausedState)
        assertEquals("Pausar Gravação", miniBar.btnPauseResume.contentDescription)

        miniBar.updateRecordingState(com.example.model.RecordingStatus.Paused(65_000L))
        assertEquals(true, miniBar.isPausedState)
        assertEquals("Continuar Gravação", miniBar.btnPauseResume.contentDescription)

        miniBar.btnPauseResume.performClick()
        miniBar.btnStop.performClick()
        assertEquals(1, pauseResumeClicked)
        assertEquals(1, stopClicked)

        // Modo Aba lateral: recolhe e reexpande
        miniBar.btnCollapseOrHide.performClick()
        assertEquals(true, miniBar.isCollapsedToEdgeTab)
        assertEquals(android.view.View.GONE, miniBar.pillContainer.visibility)
        assertEquals(android.view.View.VISIBLE, miniBar.edgeTabContainer.visibility)
        miniBar.expandFromEdgeTab()
        assertEquals(false, miniBar.isCollapsedToEdgeTab)
        assertEquals(android.view.View.VISIBLE, miniBar.pillContainer.visibility)

        // Modo Ocultar completamente até tocar na notificação
        miniBar.updateCollapseBehavior(com.example.model.MiniBarCollapseBehavior.HIDE_UNTIL_NOTIFICATION_TAP)
        miniBar.btnCollapseOrHide.performClick()
        assertEquals(1, hideTotalClicked)

        // 3. Verifica NotificationMiniControlView
        var miniPauseClicked = 0
        var miniStopClicked = 0
        var miniDismissClicked = 0
        val miniControl = com.example.overlay.NotificationMiniControlView(
            context = context,
            windowManager = wm,
            onTogglePauseResume = { miniPauseClicked++ },
            onStopRecording = { miniStopClicked++ },
            onDismissMenu = { miniDismissClicked++ }
        )
        miniControl.updateRecordingState(com.example.model.RecordingStatus.Recording(12_000L))
        assertEquals("00:12", miniControl.timerText.text.toString())
        assertEquals("Pausar", miniControl.pauseResumeLabel.text.toString())

        miniControl.updateRecordingState(com.example.model.RecordingStatus.Paused(12_000L))
        assertEquals("Continuar", miniControl.pauseResumeLabel.text.toString())

        miniControl.btnPauseResume.performClick()
        miniControl.btnStop.performClick()
        miniControl.btnCloseMenu.performClick()
        assertEquals(1, miniPauseClicked)
        assertEquals(1, miniStopClicked)
        assertEquals(1, miniDismissClicked)
    }

    @Test
    fun `verify overlay size and opacity SharedPreferences clamping and real proportional WindowManager resizing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

        // 1. Defaults e clamping de segurança (coerceIn + passos de 5%)
        val defaults = com.example.model.RecordingSettings()
        assertEquals(120, defaults.floatingBubbleSizePercent)
        assertEquals(120, defaults.dexMiniBarSizePercent)
        assertEquals(90, defaults.dexMiniBarOpacityPercent)

        assertEquals(80, com.example.model.SettingsPreferences.sanitizeSizePercent(50))
        assertEquals(180, com.example.model.SettingsPreferences.sanitizeSizePercent(250))
        assertEquals(125, com.example.model.SettingsPreferences.sanitizeSizePercent(123))
        assertEquals(25, com.example.model.SettingsPreferences.sanitizeMiniBarOpacityPercent(5))
        assertEquals(100, com.example.model.SettingsPreferences.sanitizeMiniBarOpacityPercent(150))
        assertEquals(65, com.example.model.SettingsPreferences.sanitizeMiniBarOpacityPercent(64))

        // Opacidade proporcional da Aba Lateral Recolhida: min(userAlpha * 0.65, 0.55) >= 0.25
        assertEquals(0.25f, com.example.model.SettingsPreferences.computeEdgeTabAlpha(25), 0.001f)
        assertEquals(0.325f, com.example.model.SettingsPreferences.computeEdgeTabAlpha(50), 0.001f)
        assertEquals(0.55f, com.example.model.SettingsPreferences.computeEdgeTabAlpha(90), 0.001f)
        assertEquals(0.55f, com.example.model.SettingsPreferences.computeEdgeTabAlpha(100), 0.001f)

        // Persistência + coerceIn ao salvar/recarregar SharedPreferences
        val outOfBounds = defaults.copy(
            floatingBubbleSizePercent = 240,
            dexMiniBarSizePercent = 40,
            dexMiniBarOpacityPercent = 10
        )
        com.example.model.SettingsPreferences.save(context, outOfBounds)
        val clampedReload = com.example.model.SettingsPreferences.load(context)
        assertEquals(180, clampedReload.floatingBubbleSizePercent)
        assertEquals(80, clampedReload.dexMiniBarSizePercent)
        assertEquals(25, clampedReload.dexMiniBarOpacityPercent)

        val validCustom = defaults.copy(
            floatingBubbleSizePercent = 155,
            dexMiniBarSizePercent = 140,
            dexMiniBarOpacityPercent = 75
        )
        com.example.model.SettingsPreferences.save(context, validCustom)
        val validReload = com.example.model.SettingsPreferences.load(context)
        assertEquals(155, validReload.floatingBubbleSizePercent)
        assertEquals(140, validReload.dexMiniBarSizePercent)
        assertEquals(75, validReload.dexMiniBarOpacityPercent)

        // 2. Redimensionamento proporcional real da FloatingBallView (sem scaleX/scaleY)
        val ball = com.example.overlay.FloatingBallView(
            context = context,
            windowManager = wm,
            onStartRecording = {},
            onToggleBrush = {},
            onToggleFacecam = {},
            onCloseBall = {},
            initialSizePercent = 80
        )
        val widthAt80 = ball.bubbleContainer.layoutParams.width
        val btnWidthAt80 = ball.btnPauseResume.layoutParams.width
        ball.applySizePercent(160)
        val widthAt160 = ball.bubbleContainer.layoutParams.width
        val btnWidthAt160 = ball.btnPauseResume.layoutParams.width
        assertEquals(160, ball.currentSizePercent)
        assertEquals(true, widthAt160 > widthAt80)
        assertEquals(true, btnWidthAt160 > btnWidthAt80)
        assertEquals(1.0f, ball.scaleX, 0.001f)
        assertEquals(1.0f, ball.scaleY, 0.001f)
        assertEquals(android.view.WindowManager.LayoutParams.WRAP_CONTENT, ball.layoutParams.width)
        assertEquals(android.view.WindowManager.LayoutParams.WRAP_CONTENT, ball.layoutParams.height)

        // 3. Redimensionamento proporcional real + opacidade da DexMiniBarView (+ Aba Lateral)
        val miniBar = com.example.overlay.DexMiniBarView(
            context = context,
            windowManager = wm,
            initialCollapseBehavior = com.example.model.MiniBarCollapseBehavior.COLLAPSE_TO_EDGE_TAB,
            onTogglePauseResume = {},
            onStopRecording = {},
            onHideUntilNotificationTap = {},
            initialSizePercent = 80,
            initialOpacityPercent = 90
        )
        val pillBtnWidthAt80 = miniBar.btnPauseResume.layoutParams.width
        miniBar.applyStyle(160, 50)
        val pillBtnWidthAt160 = miniBar.btnPauseResume.layoutParams.width
        assertEquals(160, miniBar.currentSizePercent)
        assertEquals(50, miniBar.currentOpacityPercent)
        assertEquals(true, pillBtnWidthAt160 > pillBtnWidthAt80)
        assertEquals(0.50f, miniBar.pillContainer.alpha, 0.001f)
        assertEquals(
            com.example.model.SettingsPreferences.computeEdgeTabAlpha(50),
            miniBar.edgeTabContainer.alpha,
            0.001f
        )
        assertEquals("tnum", miniBar.timerText.fontFeatureSettings)
        assertEquals(1.0f, miniBar.scaleX, 0.001f)
        assertEquals(android.view.WindowManager.LayoutParams.WRAP_CONTENT, miniBar.layoutParams.width)

        // 4. Redimensionamento proporcional real + opacidade do NotificationMiniControlView
        val miniControl = com.example.overlay.NotificationMiniControlView(
            context = context,
            windowManager = wm,
            onTogglePauseResume = {},
            onStopRecording = {},
            onDismissMenu = {},
            initialSizePercent = 80,
            initialOpacityPercent = 100
        )
        val closeWidthAt80 = miniControl.btnCloseMenu.layoutParams.width
        val btnPadAt80 = miniControl.btnPauseResume.paddingLeft
        val timerTextSizeAt80 = miniControl.timerText.textSize
        miniControl.applyStyle(150, 75)
        val closeWidthAt150 = miniControl.btnCloseMenu.layoutParams.width
        val btnPadAt150 = miniControl.btnPauseResume.paddingLeft
        val timerTextSizeAt150 = miniControl.timerText.textSize
        assertEquals(150, miniControl.currentSizePercent)
        assertEquals(75, miniControl.currentOpacityPercent)
        assertEquals(true, closeWidthAt150 > closeWidthAt80)
        assertEquals(true, btnPadAt150 > btnPadAt80)
        assertEquals(true, timerTextSizeAt150 > timerTextSizeAt80)
        assertEquals(0.75f, miniControl.cardContainer.alpha, 0.001f)
        assertEquals("tnum", miniControl.timerText.fontFeatureSettings)
        assertEquals(1.0f, miniControl.scaleX, 0.001f)
        assertEquals(android.view.WindowManager.LayoutParams.WRAP_CONTENT, miniControl.layoutParams.width)
    }
}




