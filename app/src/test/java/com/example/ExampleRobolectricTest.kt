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
    fun `verify PCM ShortArray mixer sums and clamps properly and handles zeroed internal audio`() {
        val internalShorts = shortArrayOf(0, 0, 25000, -25000)
        val micShorts = shortArrayOf(12000, -8000, 15000, -15000)
        val outputShorts = ShortArray(4)

        com.example.audio.AudioCaptureAndEncoder.mixPcmShortBuffers(
            internalBuffer = internalShorts,
            internalShortsRead = 4,
            internalVol = 1.0f,
            micBuffer = micShorts,
            micShortsRead = 4,
            micVol = 1.0f,
            outputShorts = outputShorts,
            sampleCount = 4
        )

        // When internal channel delivers 0 (DRM blocked), mic audio passes through intact
        assertEquals(12000.toShort(), outputShorts[0])
        assertEquals((-8000).toShort(), outputShorts[1])
        // Clamping at Short.MAX_VALUE and Short.MIN_VALUE
        assertEquals(Short.MAX_VALUE, outputShorts[2])
        assertEquals(Short.MIN_VALUE, outputShorts[3])
    }

    @Test
    fun `verify blocked audio zero buffer detector and warning message`() {
        val zeroBuffer = ByteArray(4096)
        assertEquals(true, com.example.audio.AudioCaptureAndEncoder.isBufferAllZeros(zeroBuffer, 4096))

        zeroBuffer[100] = 5
        assertEquals(false, com.example.audio.AudioCaptureAndEncoder.isBufferAllZeros(zeroBuffer, 4096))

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
}
