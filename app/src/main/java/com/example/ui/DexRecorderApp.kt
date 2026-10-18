package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AudioCaptureMode
import com.example.model.RecordingStatus
import com.example.model.createConfiguredScreenCaptureIntent
import com.example.service.ScreenRecordingService
import com.example.ui.screens.RecordDashboardScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TrashScreen
import com.example.ui.screens.VideoEditorScreen
import com.example.ui.screens.VideoLibraryScreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.RedDark
import com.example.ui.theme.RedPrimary
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexRecorderApp(
    viewModel: MainViewModel,
    onRequestStartRecording: () -> Unit = {},
    onRequestOverlayPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val recordingStatus by viewModel.recordingStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Screen capture launcher for MediaProjection
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_START
                putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    // Permission launchers
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Start screen capture flow after permissions checked using Android 14+ MediaProjectionConfig
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(createConfiguredScreenCaptureIntent(projectionManager, settings.captureMode))
    }

    val requestPermissionsAndStart = {
        val permissionsToRequest = mutableListOf<String>()
        if (settings.audioMode == AudioCaptureMode.MIC_ONLY || settings.audioMode == AudioCaptureMode.MIXED_INTERNAL_MIC) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (settings.enableFacecam) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(createConfiguredScreenCaptureIntent(projectionManager, settings.captureMode))
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(RedPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "DexRecorder",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        }
                    },
                    actions = {
                        if (recordingStatus is RecordingStatus.Recording) {
                            Row(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(RedDark.copy(alpha = 0.7f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(RedPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "REC",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Samsung DeX status pill
                        Row(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DesktopWindows,
                                contentDescription = "DeX Otimizado",
                                tint = CyanAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isWideScreen) "DeX Desktop Mode" else "DeX Pronto",
                                color = CyanAccent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Videocam, contentDescription = "Gravador") },
                            label = { Text("Gravar") },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = RedPrimary, indicatorColor = RedDark.copy(alpha = 0.3f))
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Vídeos") },
                            label = { Text("Vídeos") },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = RedPrimary, indicatorColor = RedDark.copy(alpha = 0.3f))
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.ContentCut, contentDescription = "Editor") },
                            label = { Text("Editor") },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = RedPrimary, indicatorColor = RedDark.copy(alpha = 0.3f))
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Default.Delete, contentDescription = "Lixeira") },
                            label = { Text("Lixeira") },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = RedPrimary, indicatorColor = RedDark.copy(alpha = 0.3f))
                        )
                        NavigationBarItem(
                            selected = selectedTab == 4,
                            onClick = { selectedTab = 4 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                            label = { Text("Ajustes") },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = RedPrimary, indicatorColor = RedDark.copy(alpha = 0.3f))
                        )
                    }
                }
            }
        ) { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (isWideScreen) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        NavigationRailItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Videocam, contentDescription = "Gravar") },
                            label = { Text("Gravar") },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = RedPrimary, indicatorColor = RedDark.copy(alpha = 0.3f))
                        )
                        NavigationRailItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Vídeos") },
                            label = { Text("Vídeos") },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = RedPrimary, indicatorColor = RedDark.copy(alpha = 0.3f))
                        )
                        NavigationRailItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.ContentCut, contentDescription = "Editor") },
                            label = { Text("Editor") },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = RedPrimary, indicatorColor = RedDark.copy(alpha = 0.3f))
                        )
                        NavigationRailItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Default.Delete, contentDescription = "Lixeira") },
                            label = { Text("Lixeira") },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = RedPrimary, indicatorColor = RedDark.copy(alpha = 0.3f))
                        )
                        NavigationRailItem(
                            selected = selectedTab == 4,
                            onClick = { selectedTab = 4 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                            label = { Text("Ajustes") },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = RedPrimary, indicatorColor = RedDark.copy(alpha = 0.3f))
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    when (selectedTab) {
                        0 -> RecordDashboardScreen(
                            viewModel = viewModel,
                            onStartRecordingClick = onRequestStartRecording,
                            modifier = Modifier.fillMaxSize()
                        )
                        1 -> VideoLibraryScreen(
                            viewModel = viewModel,
                            onNavigateToEditor = { selectedTab = 2 },
                            modifier = Modifier.fillMaxSize()
                        )
                        2 -> VideoEditorScreen(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                        3 -> TrashScreen(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                        4 -> SettingsScreen(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
