package com.example.model

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/**
 * TAREFA 1: Ordem do Modo de Captura ("Tela cheia" no topo e padrão, "Um único aplicativo" logo abaixo)
 */
enum class ScreenCaptureMode(val title: String, val description: String) {
    FULL_SCREEN(
        "Tela cheia",
        "Grava a tela inteira diretamente (Monitor DeX ou Display Principal) sem perguntar o aplicativo."
    ),
    SINGLE_APP(
        "Um único aplicativo",
        "Permite escolher uma janela ou aplicativo específico no seletor do sistema."
    )
}

/**
 * TAREFA 2: Ordem estritamente crescente de Resoluções:
 * 720p (1280x720) -> 1080p (1920x1080) -> 2K (2560x1440) -> 4K (3840x2160) -> Nativo
 */
enum class ResolutionOption(val shortLabel: String, val label: String, val width: Int, val height: Int) {
    RES_720P("720p", "720p (1280x720)", 1280, 720),
    RES_1080P("1080p", "1080p (1920x1080)", 1920, 1080),
    RES_2K("2K", "2K (2560x1440)", 2560, 1440),
    RES_4K("4K", "4K (3840x2160)", 3840, 2160),
    NATIVE("Nativo", "Nativo (Resolução real do display/monitor DeX)", 0, 0)
}

/**
 * TAREFA 3: Taxa de Quadros (FPS) ordenada estritamente: 30 FPS, 60 FPS, 90 FPS, 120 FPS
 */
enum class FpsOption(val label: String, val fps: Int) {
    FPS_30("30 FPS", 30),
    FPS_60("60 FPS", 60),
    FPS_90("90 FPS", 90),
    FPS_120("120 FPS", 120)
}

enum class BitrateOption(val label: String, val bps: Int) {
    MBPS_4("4 Mbps", 4_000_000),
    MBPS_8("8 Mbps", 8_000_000),
    MBPS_12("12 Mbps (Padrão 1080p)", 12_000_000),
    MBPS_16("16 Mbps (Alta Fidelidade)", 16_000_000),
    MBPS_24("24 Mbps (2K / 90 FPS)", 24_000_000),
    MBPS_32("32 Mbps (2K / 120 FPS)", 32_000_000),
    MBPS_40("40 Mbps (4K Ultra)", 40_000_000)
}

enum class AudioCaptureMode(val title: String, val description: String) {
    INTERNAL_ONLY(
        "Apenas Áudio Interno",
        "Captura o som dos jogos/apps (Android 10+). Funciona com alto-falante ou fones de ouvido."
    ),
    MIXED_INTERNAL_MIC(
        "Áudio Interno + Microfone",
        "Ideal para Gameplays e Reações. Mixagem de som em tempo real em alta fidelidade."
    ),
    MIC_ONLY(
        "Apenas Microfone",
        "Grava a sua voz pelo microfone do aparelho ou fone conectado."
    ),
    MUTE(
        "Mudo / Sem Áudio",
        "Grava apenas a imagem da tela sem trilha de áudio."
    )
}

data class RecordingSettings(
    val captureMode: ScreenCaptureMode = ScreenCaptureMode.FULL_SCREEN,
    val resolution: ResolutionOption = ResolutionOption.RES_1080P,
    val fps: FpsOption = FpsOption.FPS_60,
    val bitrate: BitrateOption = BitrateOption.MBPS_12,
    val audioMode: AudioCaptureMode = AudioCaptureMode.MIXED_INTERNAL_MIC,
    val internalAudioVolume: Float = 1.0f,
    val micAudioVolume: Float = 1.0f,
    val countdownSeconds: Int = 3,
    val showFloatingBall: Boolean = true,
    val hideFloatingBallWhileRecording: Boolean = false,
    val floatingBallOpacity: Float = 0.85f,
    val enableFacecam: Boolean = false,
    val enableBrush: Boolean = false,
    val useHevcCodec: Boolean = false // H.265 vs H.264
)

/**
 * TAREFA 1: Integração com Android 14+ (API 34+ / UPSIDE_DOWN_CAKE) para criação do Intent do MediaProjectionManager.
 * - Se for "Tela cheia" (FULL_SCREEN): MediaProjectionConfig.createConfigForDefaultDisplay()
 * - Se for "Um único aplicativo" (SINGLE_APP): MediaProjectionConfig.createConfigForUserChoice()
 */
fun createConfiguredScreenCaptureIntent(
    projectionManager: MediaProjectionManager,
    captureMode: ScreenCaptureMode
): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val config = when (captureMode) {
            ScreenCaptureMode.FULL_SCREEN -> MediaProjectionConfig.createConfigForDefaultDisplay()
            ScreenCaptureMode.SINGLE_APP -> MediaProjectionConfig.createConfigForUserChoice()
        }
        projectionManager.createScreenCaptureIntent(config)
    } else {
        projectionManager.createScreenCaptureIntent()
    }
}

/**
 * TAREFA 4: Persistência local de todas as configurações do usuário via SharedPreferences ("dexrecorder_prefs").
 */
object SettingsPreferences {
    const val PREFS_NAME = "dexrecorder_prefs"

    private const val KEY_CAPTURE_MODE = "capture_mode"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_FPS = "fps"
    private const val KEY_BITRATE = "bitrate"
    private const val KEY_AUDIO_MODE = "audio_mode"
    private const val KEY_INTERNAL_VOLUME = "internal_volume"
    private const val KEY_MIC_VOLUME = "mic_volume"
    private const val KEY_COUNTDOWN_SECONDS = "countdown_seconds"
    private const val KEY_SHOW_FLOATING_BALL = "show_floating_ball"
    private const val KEY_HIDE_BALL_WHILE_RECORDING = "hide_floating_ball_while_recording"
    private const val KEY_FLOATING_BALL_OPACITY = "floating_ball_opacity"
    private const val KEY_ENABLE_FACECAM = "enable_facecam"
    private const val KEY_ENABLE_BRUSH = "enable_brush"
    private const val KEY_USE_HEVC_CODEC = "use_hevc_codec"

    fun save(context: Context, settings: RecordingSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_CAPTURE_MODE, settings.captureMode.name)
            putString(KEY_RESOLUTION, settings.resolution.name)
            putString(KEY_FPS, settings.fps.name)
            putString(KEY_BITRATE, settings.bitrate.name)
            putString(KEY_AUDIO_MODE, settings.audioMode.name)
            putFloat(KEY_INTERNAL_VOLUME, settings.internalAudioVolume)
            putFloat(KEY_MIC_VOLUME, settings.micAudioVolume)
            putInt(KEY_COUNTDOWN_SECONDS, settings.countdownSeconds)
            putBoolean(KEY_SHOW_FLOATING_BALL, settings.showFloatingBall)
            putBoolean(KEY_HIDE_BALL_WHILE_RECORDING, settings.hideFloatingBallWhileRecording)
            putFloat(KEY_FLOATING_BALL_OPACITY, settings.floatingBallOpacity)
            putBoolean(KEY_ENABLE_FACECAM, settings.enableFacecam)
            putBoolean(KEY_ENABLE_BRUSH, settings.enableBrush)
            putBoolean(KEY_USE_HEVC_CODEC, settings.useHevcCodec)
            apply()
        }
    }

    fun load(context: Context): RecordingSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = RecordingSettings()

        val captureModeName = prefs.getString(KEY_CAPTURE_MODE, defaults.captureMode.name) ?: defaults.captureMode.name
        val resolutionName = prefs.getString(KEY_RESOLUTION, defaults.resolution.name) ?: defaults.resolution.name
        val fpsName = prefs.getString(KEY_FPS, defaults.fps.name) ?: defaults.fps.name
        val bitrateName = prefs.getString(KEY_BITRATE, defaults.bitrate.name) ?: defaults.bitrate.name
        val audioModeName = prefs.getString(KEY_AUDIO_MODE, defaults.audioMode.name) ?: defaults.audioMode.name

        val captureMode = runCatching { ScreenCaptureMode.valueOf(captureModeName) }.getOrDefault(defaults.captureMode)
        val resolution = runCatching { ResolutionOption.valueOf(resolutionName) }.getOrDefault(defaults.resolution)
        val fps = runCatching { FpsOption.valueOf(fpsName) }.getOrDefault(defaults.fps)
        val bitrate = runCatching { BitrateOption.valueOf(bitrateName) }.getOrDefault(defaults.bitrate)
        val audioMode = runCatching { AudioCaptureMode.valueOf(audioModeName) }.getOrDefault(defaults.audioMode)

        return RecordingSettings(
            captureMode = captureMode,
            resolution = resolution,
            fps = fps,
            bitrate = bitrate,
            audioMode = audioMode,
            internalAudioVolume = prefs.getFloat(KEY_INTERNAL_VOLUME, defaults.internalAudioVolume),
            micAudioVolume = prefs.getFloat(KEY_MIC_VOLUME, defaults.micAudioVolume),
            countdownSeconds = prefs.getInt(KEY_COUNTDOWN_SECONDS, defaults.countdownSeconds),
            showFloatingBall = prefs.getBoolean(KEY_SHOW_FLOATING_BALL, defaults.showFloatingBall),
            hideFloatingBallWhileRecording = prefs.getBoolean(
                KEY_HIDE_BALL_WHILE_RECORDING,
                defaults.hideFloatingBallWhileRecording
            ),
            floatingBallOpacity = prefs.getFloat(KEY_FLOATING_BALL_OPACITY, defaults.floatingBallOpacity),
            enableFacecam = prefs.getBoolean(KEY_ENABLE_FACECAM, defaults.enableFacecam),
            enableBrush = prefs.getBoolean(KEY_ENABLE_BRUSH, defaults.enableBrush),
            useHevcCodec = prefs.getBoolean(KEY_USE_HEVC_CODEC, defaults.useHevcCodec)
        )
    }
}

sealed class RecordingStatus {
    object Idle : RecordingStatus()
    data class Countdown(val secondsLeft: Int) : RecordingStatus()
    data class Recording(val durationMs: Long) : RecordingStatus()
    data class Paused(val durationMs: Long) : RecordingStatus()
    object Stopping : RecordingStatus()
    data class Error(val message: String) : RecordingStatus()
}
