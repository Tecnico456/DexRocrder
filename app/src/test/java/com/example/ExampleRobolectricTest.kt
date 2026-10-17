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
}
