package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.VideoRepository
import com.example.model.SettingsPreferences
import com.example.service.ScreenRecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DexRecorderApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { AppDatabase.getDatabase(this) }
    val videoRepository by lazy { VideoRepository(database.recordedVideoDao(), this) }

    override fun onCreate() {
        super.onCreate()
        // TAREFA 4: Carrega imediatamente as configurações salvas do SharedPreferences na inicialização
        ScreenRecordingService.currentSettings = SettingsPreferences.load(this)

        // Auto-purge any videos left in trash for more than 3 days
        applicationScope.launch {
            try {
                videoRepository.purgeExpiredTrash()
            } catch (e: Exception) {
                // Log and ignore
            }
        }
    }
}
