package com.example

import com.example.bluetooth.BluetoothHidManager
import com.example.ui.remote.AnalogWasdMapper
import com.example.ui.remote.GamepadPreset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingHidEngineTest {

    @Test
    fun buildSixKeyReport_emptyReleasesAllKeysAndModifiers() {
        val report = BluetoothHidManager.buildSixKeyReport(emptyList(), 0)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
            report
        )
    }

    @Test
    fun buildSixKeyReport_packsSimultaneousModifierAndMultipleKeys() {
        val keys = listOf(
            BluetoothHidManager.KEY_W,
            BluetoothHidManager.KEY_D,
            BluetoothHidManager.KEY_SPACE
        )
        val report = BluetoothHidManager.buildSixKeyReport(
            pressedKeys = keys,
            modifiers = BluetoothHidManager.MODIFIER_SHIFT
        )
        assertEquals(8, report.size)
        assertEquals(BluetoothHidManager.MODIFIER_SHIFT, report[0])
        assertEquals(0.toByte(), report[1])
        assertEquals(BluetoothHidManager.KEY_W, report[2])
        assertEquals(BluetoothHidManager.KEY_D, report[3])
        assertEquals(BluetoothHidManager.KEY_SPACE, report[4])
        assertEquals(0.toByte(), report[5])
        assertEquals(0.toByte(), report[6])
        assertEquals(0.toByte(), report[7])
    }

    @Test
    fun buildSixKeyReport_capsAtSixKeysAndSkipsZeroBytes() {
        val keys = listOf(
            0.toByte(),
            BluetoothHidManager.KEY_W,
            BluetoothHidManager.KEY_A,
            BluetoothHidManager.KEY_S,
            BluetoothHidManager.KEY_D,
            BluetoothHidManager.KEY_E,
            BluetoothHidManager.KEY_R,
            BluetoothHidManager.KEY_SPACE
        )
        val report = BluetoothHidManager.buildSixKeyReport(keys, BluetoothHidManager.MODIFIER_LEFT_CTRL)
        assertArrayEquals(
            byteArrayOf(
                BluetoothHidManager.MODIFIER_LEFT_CTRL,
                0,
                BluetoothHidManager.KEY_W,
                BluetoothHidManager.KEY_A,
                BluetoothHidManager.KEY_S,
                BluetoothHidManager.KEY_D,
                BluetoothHidManager.KEY_E,
                BluetoothHidManager.KEY_R
            ),
            report
        )
    }

    @Test
    fun analogWasdMapper_deadzoneReturnsNoKeys() {
        val out = AnalogWasdMapper.mapStick(0.1f, -0.15f, GamepadPreset.FPS_WASD)
        assertTrue(out.keys.isEmpty())
        assertFalse(out.sprintShift)
    }

    @Test
    fun analogWasdMapper_mapsAllEightFpsDirectionsAccurately() {
        // Forward (W) — screen Y negative is up
        assertEquals(setOf(BluetoothHidManager.KEY_W), AnalogWasdMapper.mapStick(0f, -0.6f, GamepadPreset.FPS_WASD).keys)
        // Forward-Right (W+D)
        assertEquals(
            setOf(BluetoothHidManager.KEY_W, BluetoothHidManager.KEY_D),
            AnalogWasdMapper.mapStick(0.5f, -0.5f, GamepadPreset.FPS_WASD).keys
        )
        // Right (D)
        assertEquals(setOf(BluetoothHidManager.KEY_D), AnalogWasdMapper.mapStick(0.6f, 0f, GamepadPreset.FPS_WASD).keys)
        // Back-Right (S+D)
        assertEquals(
            setOf(BluetoothHidManager.KEY_S, BluetoothHidManager.KEY_D),
            AnalogWasdMapper.mapStick(0.5f, 0.5f, GamepadPreset.FPS_WASD).keys
        )
        // Backward (S)
        assertEquals(setOf(BluetoothHidManager.KEY_S), AnalogWasdMapper.mapStick(0f, 0.6f, GamepadPreset.FPS_WASD).keys)
        // Back-Left (S+A)
        assertEquals(
            setOf(BluetoothHidManager.KEY_S, BluetoothHidManager.KEY_A),
            AnalogWasdMapper.mapStick(-0.5f, 0.5f, GamepadPreset.FPS_WASD).keys
        )
        // Left (A)
        assertEquals(setOf(BluetoothHidManager.KEY_A), AnalogWasdMapper.mapStick(-0.6f, 0f, GamepadPreset.FPS_WASD).keys)
        // Forward-Left (W+A)
        assertEquals(
            setOf(BluetoothHidManager.KEY_W, BluetoothHidManager.KEY_A),
            AnalogWasdMapper.mapStick(-0.5f, -0.5f, GamepadPreset.FPS_WASD).keys
        )
    }

    @Test
    fun analogWasdMapper_outerRingForwardTriggersAutoSprintButBackwardDoesNot() {
        val sprintForward = AnalogWasdMapper.mapStick(0f, -0.95f, GamepadPreset.FPS_WASD, autoSprintEnabled = true)
        assertEquals(setOf(BluetoothHidManager.KEY_W), sprintForward.keys)
        assertTrue(sprintForward.sprintShift)

        val sprintDiag = AnalogWasdMapper.mapStick(0.65f, -0.65f, GamepadPreset.FPS_WASD, autoSprintEnabled = true)
        assertEquals(setOf(BluetoothHidManager.KEY_W, BluetoothHidManager.KEY_D), sprintDiag.keys)
        assertTrue(sprintDiag.sprintShift)

        val walkBackward = AnalogWasdMapper.mapStick(0f, 0.98f, GamepadPreset.FPS_WASD, autoSprintEnabled = true)
        assertEquals(setOf(BluetoothHidManager.KEY_S), walkBackward.keys)
        assertFalse(walkBackward.sprintShift)

        val sprintDisabled = AnalogWasdMapper.mapStick(0f, -0.98f, GamepadPreset.FPS_WASD, autoSprintEnabled = false)
        assertFalse(sprintDisabled.sprintShift)
    }

    @Test
    fun analogWasdMapper_racingPresetUsesArrowKeysWithoutShiftSprint() {
        val steerLeftForward = AnalogWasdMapper.mapStick(-0.7f, -0.7f, GamepadPreset.RACING_ARROWS, autoSprintEnabled = true)
        assertEquals(setOf(BluetoothHidManager.KEY_UP, BluetoothHidManager.KEY_LEFT), steerLeftForward.keys)
        assertFalse(steerLeftForward.sprintShift)
    }

    @Test
    fun remoteControlTab_rotatedWrapsForwardAndBackwardCleanly() {
        val start = com.example.ui.RemoteControlTab.KUMANDA
        assertEquals(com.example.ui.RemoteControlTab.KEYBOARD, start.rotated(1))
        assertEquals(com.example.ui.RemoteControlTab.GAMEPAD, start.rotated(-1))
        assertEquals(com.example.ui.RemoteControlTab.KUMANDA, com.example.ui.RemoteControlTab.GAMEPAD.rotated(1))
    }
}
