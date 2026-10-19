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
        assertEquals("dex_recorder_channel", ScreenRecordingService.CHANNEL_ID)
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
    fun `verify FloatingBallView close button, drag-to-close target, and FloatingOverlayService onTaskRemoved`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

        var closeCallbackTriggered = false
        val ballView = com.example.overlay.FloatingBallView(
            context = context,
            windowManager = wm,
            onToggleBrush = {},
            onToggleFacecam = {},
            onCloseBall = { closeCallbackTriggered = true }
        )

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
}

