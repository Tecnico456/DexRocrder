package com.example.model

enum class ResolutionOption(val label: String, val width: Int, val height: Int) {
    NATIVE("Nativo do Dispositivo", 0, 0),
    RES_4K("4K Ultra HD (3840x2160)", 3840, 2160),
    RES_1080P("1080p Full HD (1920x1080)", 1920, 1080),
    RES_720P("720p HD (1280x720)", 1280, 720),
    RES_480P("480p SD (854x480)", 854, 480),
    RES_240P("240p Baixa (426x240)", 426, 240)
}

enum class FpsOption(val label: String, val fps: Int) {
    FPS_15("15 FPS (Econômico)", 15),
    FPS_30("30 FPS (Padrão)", 30),
    FPS_60("60 FPS (Fluido / Jogos)", 60)
}

enum class BitrateOption(val label: String, val bps: Int) {
    MBPS_2("2 Mbps", 2_000_000),
    MBPS_4("4 Mbps", 4_000_000),
    MBPS_8("8 Mbps", 8_000_000),
    MBPS_12("12 Mbps (Recomendado)", 12_000_000),
    MBPS_16("16 Mbps (Alta Fidelidade)", 16_000_000),
    MBPS_24("24 Mbps (Ultra Qualidade)", 24_000_000)
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

sealed class RecordingStatus {
    object Idle : RecordingStatus()
    data class Countdown(val secondsLeft: Int) : RecordingStatus()
    data class Recording(val durationMs: Long) : RecordingStatus()
    data class Paused(val durationMs: Long) : RecordingStatus()
    object Stopping : RecordingStatus()
    data class Error(val message: String) : RecordingStatus()
}
