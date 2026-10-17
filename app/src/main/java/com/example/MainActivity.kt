package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.service.ScreenRecordingService
import com.example.ui.DexRecorderApp
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel

/**
 * MainActivity enforcing Anti-Crash Architecture for Android 14+ (API 34+):
 *
 * ORDEM DE INICIALIZAÇÃO CRÍTICA:
 * 1. Permissões de Runtime Primeiro: POST_NOTIFICATIONS, RECORD_AUDIO e verificação de SYSTEM_ALERT_WINDOW.
 * 2. Intent de Captura de Tela: MediaProjectionManager.createScreenCaptureIntent() via registerForActivityResult.
 * 3. Início Seguro do Serviço: startForegroundService() é chamado EXCLUSIVAMENTE após o usuário clicar em "Permitir"
 *    no diálogo do sistema, transferindo resultCode e Intent de dados para o ScreenRecordingService.
 */
class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"
    private val viewModel: MainViewModel by viewModels()

    // 1. Launcher para permissões de runtime (POST_NOTIFICATIONS, RECORD_AUDIO, etc.)
    private lateinit var runtimePermissionsLauncher: ActivityResultLauncher<Array<String>>

    // Launcher opcional para SYSTEM_ALERT_WINDOW (overlay para bola flutuante e pincel)
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>

    // 2. Launcher para Intent de Captura de Tela (MediaProjection)
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Registra launchers no ciclo de vida da Activity antes do onStart
        setupActivityResultLaunchers()

        // Solicita permissões de runtime essenciais na inicialização da Activity
        requestInitialRuntimePermissions()

        setContent {
            MyApplicationTheme {
                DexRecorderApp(
                    viewModel = viewModel,
                    onRequestStartRecording = { startRecordingWorkflow() },
                    onRequestOverlayPermission = { requestOverlayPermission() }
                )
            }
        }
    }

    private fun setupActivityResultLaunchers() {
        // Callback para permissões de sistema
        runtimePermissionsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
            val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
            } else {
                true
            }

            Log.d(tag, "Permissões concedidas: Áudio=$audioGranted, Notificações=$notificationsGranted")

            // Se for acionado pelo botão de gravação, avança para a captura de tela
            launchScreenCapturePrompt()
        }

        // Callback para permissão de sobreposição (SYSTEM_ALERT_WINDOW)
        overlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permissão de sobreposição concedida!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissão de sobreposição necessária para ferramentas flutuantes.", Toast.LENGTH_SHORT).show()
            }
        }

        // Callback para captura de tela (MediaProjection)
        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // PASSO 3: INÍCIO SEGURO DO SERVIÇO
            // Só chamamos startForegroundService DEPOIS que o usuário aprovar o pop-up
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d(tag, "Usuário autorizou captura de tela. Iniciando Foreground Service de forma segura.")
                startSafeRecordingService(result.resultCode, result.data!!)
            } else {
                Log.w(tag, "Usuário recusou ou cancelou o pop-up de captura de tela.")
                Toast.makeText(this, "Captura de tela cancelada pelo usuário.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * PASSO 1: Solicitação dinâmica de permissões de Runtime no início da Activity.
     */
    private fun requestInitialRuntimePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            runtimePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    /**
     * Fluxo completo e seguro de início de gravação:
     * 1. Valida se as permissões de Runtime estão concedidas.
     * 2. Lança createScreenCaptureIntent().
     */
    fun startRecordingWorkflow() {
        val missingPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missingPermissions.isNotEmpty()) {
            runtimePermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            launchScreenCapturePrompt()
        }
    }

    /**
     * PASSO 2: Exibe o pop-up do sistema de Captura de Tela.
     */
    private fun launchScreenCapturePrompt() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = projectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Log.e(tag, "Falha ao criar ScreenCaptureIntent", e)
            Toast.makeText(this, "Erro ao iniciar captura: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * PASSO 3: Inicia o Foreground Service somente com dados válidos e autorização confirmada.
     */
    private fun startSafeRecordingService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenRecordingService::class.java).apply {
            action = ScreenRecordingService.ACTION_START
            putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    /**
     * Solicitação de SYSTEM_ALERT_WINDOW para ferramentas flutuantes.
     */
    fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            Toast.makeText(this, "Permissão de sobreposição já está ativa.", Toast.LENGTH_SHORT).show()
        }
    }
}
