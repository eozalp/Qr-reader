package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun testAppNameResource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("QR Live Calc", appName)
    }

    @Test
    fun testProfileRepositoryInitialization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = ProfileRepository(context)
        val presets = ProfileRepository.createDefaultPresets()
        assertTrue(presets.isNotEmpty())
        assertEquals("Quick Sum (a-b-c-d)", presets.first().name)
    }
}
