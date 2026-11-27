package com.example

import com.example.bluetooth.BluetoothHidManager
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test

class BluetoothHidPayloadTest {

    private val testConfig = BluetoothKeyboardConfig(
        isEnabled = true,
        isAutoSendEnabled = false,
        sendTarget = KeyboardSendTarget.WHOLE_RAW,
        selectedVariableName = "",
        selectedVariables = emptyList(),
        variableSeparator = "\t",
        endingKey = KeyboardEndingKey.ENTER,
        customTemplate = "{a}\t{b}",
        prefixText = "",
        postfixText = "",
        typingDelayMs = 10L,
        sendCooldownMs = 1500L,
        convertTurkishChars = true
    )

    private val sampleResult = ParsedQrResult(
        rawText = "BARCODE-12345-67890",
        extractedVariables = mapOf("price" to 149.99, "qty" to 3.0),
        extractedStrings = mapOf("sku" to "ABC-99"),
        variablePillDisplay = mapOf(
            "price" to "149.99 ₺",
            "qty" to "3",
            "sku" to "ABC-99"
        ),
        status = CalculationStatus.Success(
            resultNumber = 449.97,
            formattedText = "449.97 ₺"
        )
    )

    @Test
    fun testResolveWholeRawBarcode() {
        val hidManager = BluetoothHidManager()
        val config = testConfig.copy(sendTarget = KeyboardSendTarget.WHOLE_RAW)
        val payload = hidManager.resolvePayload(sampleResult, config)
        assertEquals("BARCODE-12345-67890", payload)
    }

    @Test
    fun testResolveFormulaResult() {
        val hidManager = BluetoothHidManager()
        val config = testConfig.copy(sendTarget = KeyboardSendTarget.FORMULA_RESULT)
        val payload = hidManager.resolvePayload(sampleResult, config)
        assertEquals("449.97 ₺", payload)
    }

    @Test
    fun testResolveSingleVariable() {
        val hidManager = BluetoothHidManager()
        val configPrice = testConfig.copy(
            sendTarget = KeyboardSendTarget.SINGLE_VARIABLE,
            selectedVariableName = "price"
        )
        val payloadPrice = hidManager.resolvePayload(sampleResult, configPrice)
        assertEquals("149.99 ₺", payloadPrice)

        val configQty = testConfig.copy(
            sendTarget = KeyboardSendTarget.SINGLE_VARIABLE,
            selectedVariableName = "qty"
        )
        val payloadQty = hidManager.resolvePayload(sampleResult, configQty)
        assertEquals("3", payloadQty)
    }

    @Test
    fun testResolveMultipleVariablesWithTabSeparator() {
        val hidManager = BluetoothHidManager()
        val configMulti = testConfig.copy(
            sendTarget = KeyboardSendTarget.MULTIPLE_VARIABLES,
            selectedVariables = listOf("sku", "price", "qty"),
            variableSeparator = "\t"
        )
        val payload = hidManager.resolvePayload(sampleResult, configMulti)
        assertEquals("ABC-99\t149.99 ₺\t3", payload)
    }

    @Test
    fun testResolveMultipleVariablesWithCustomSeparator() {
        val hidManager = BluetoothHidManager()
        val configMulti = testConfig.copy(
            sendTarget = KeyboardSendTarget.MULTIPLE_VARIABLES,
            selectedVariables = listOf("sku", "qty"),
            variableSeparator = " | "
        )
        val payload = hidManager.resolvePayload(sampleResult, configMulti)
        assertEquals("ABC-99 | 3", payload)
    }

    @Test
    fun testResolveCustomTemplate() {
        val hidManager = BluetoothHidManager()
        val configTpl = testConfig.copy(
            sendTarget = KeyboardSendTarget.CUSTOM_TEMPLATE,
            customTemplate = "SKU: {sku} -> ADET: {qty} = {result}"
        )
        val payload = hidManager.resolvePayload(sampleResult, configTpl)
        assertEquals("SKU: ABC-99 -> ADET: 3 = 449.97 ₺", payload)
    }

    @Test
    fun testPrefixAndPostfix() {
        val hidManager = BluetoothHidManager()
        val config = testConfig.copy(
            sendTarget = KeyboardSendTarget.WHOLE_RAW,
            prefixText = ">>",
            postfixText = "<<"
        )
        val payload = hidManager.resolvePayload(sampleResult, config)
        assertEquals(">>BARCODE-12345-67890<<", payload)
    }

    @Test
    fun testConfigJsonSerialization() {
        val config = BluetoothKeyboardConfig(
            isEnabled = true,
            isAutoSendEnabled = true,
            sendTarget = KeyboardSendTarget.MULTIPLE_VARIABLES,
            selectedVariableName = "price",
            selectedVariables = listOf("a", "b", "c"),
            variableSeparator = ";",
            endingKey = KeyboardEndingKey.TAB_THEN_ENTER,
            customTemplate = "{a}-{b}",
            prefixText = "START:",
            postfixText = ":END",
            typingDelayMs = 25L,
            sendCooldownMs = 3000L,
            convertTurkishChars = true
        )

        val json = config.toJson()
        val restored = BluetoothKeyboardConfig.fromJson(json)

        assertEquals(config.isEnabled, restored.isEnabled)
        assertEquals(config.isAutoSendEnabled, restored.isAutoSendEnabled)
        assertEquals(config.sendTarget, restored.sendTarget)
        assertEquals(config.selectedVariableName, restored.selectedVariableName)
        assertEquals(config.selectedVariables, restored.selectedVariables)
        assertEquals(config.variableSeparator, restored.variableSeparator)
        assertEquals(config.endingKey, restored.endingKey)
        assertEquals(config.customTemplate, restored.customTemplate)
        assertEquals(config.prefixText, restored.prefixText)
        assertEquals(config.postfixText, restored.postfixText)
        assertEquals(config.typingDelayMs, restored.typingDelayMs)
        assertEquals(config.sendCooldownMs, restored.sendCooldownMs)
        assertEquals(config.convertTurkishChars, restored.convertTurkishChars)
        assertEquals(config.keyboardLayout, restored.keyboardLayout)
        assertEquals(config.requireDuplicateConfirmation, restored.requireDuplicateConfirmation)
    }

    @Test
    fun testAutoSendDebounceCooldown() {
        val hidManager = BluetoothHidManager()
        val config = testConfig.copy(
            isAutoSendEnabled = true,
            sendCooldownMs = 1000L,
            requireDuplicateConfirmation = false
        )

        assertTrue(hidManager.shouldAutoSend("CODE-A", config))
        hidManager.recordSentBarcode("CODE-A")
        // Immediate repeat should be suppressed by debounce cooldown & in-frame lock
        assertFalse(hidManager.shouldAutoSend("CODE-A", config))

        // Different barcode should not be blocked
        assertTrue(hidManager.shouldAutoSend("CODE-B", config))
    }

    @Test
    fun testBatchDuplicateConfirmationStateMachine() {
        val hidManager = BluetoothHidManager()
        val config = testConfig.copy(
            isAutoSendEnabled = true,
            sendCooldownMs = 1000L,
            requireDuplicateConfirmation = true
        )

        // 1. First appearance -> SendNow
        val d1 = hidManager.evaluateAutoSend("SKU-777", config, nowMs = 1000L)
        assertEquals(com.example.bluetooth.AutoSendDecision.SendNow, d1)

        // Record sent ACK
        hidManager.recordSentBarcode("SKU-777", "SKU-777", nowMs = 1000L)

        // 2. Same barcode still in frame -> SkipAlreadyActive (never spams target PC!)
        val d2 = hidManager.evaluateAutoSend("SKU-777", config, nowMs = 5000L)
        assertEquals(com.example.bluetooth.AutoSendDecision.SkipAlreadyActive, d2)

        // 3. User removes barcode for > 1200ms and scans the exact same barcode again
        hidManager.syncVisibleBarcodes(emptySet(), nowMs = 7000L)
        val d3 = hidManager.evaluateAutoSend("SKU-777", config, nowMs = 7500L)
        assertTrue(d3 is com.example.bluetooth.AutoSendDecision.RequireConfirmation)
        assertEquals(1, (d3 as com.example.bluetooth.AutoSendDecision.RequireConfirmation).previousEntry.count)

        // 4. User taps "Atla" (Skip) -> suppressed until barcode leaves frame
        hidManager.dismissDuplicateForCurrentFrame("SKU-777", nowMs = 7500L)
        val d4 = hidManager.evaluateAutoSend("SKU-777", config, nowMs = 8000L)
        assertEquals(com.example.bluetooth.AutoSendDecision.SkipAlreadyActive, d4)
    }

    @Test
    fun testSeparatedBarcodesInSameBatchDoNotWarn() {
        val hidManager = BluetoothHidManager()
        val config = testConfig.copy(
            isAutoSendEnabled = true,
            sendCooldownMs = 1000L,
            requireDuplicateConfirmation = true
        )

        // 1. Scan SKU-A -> SendNow
        assertEquals(com.example.bluetooth.AutoSendDecision.SendNow, hidManager.evaluateAutoSend("SKU-A", config, nowMs = 1000L))
        hidManager.recordSentBarcode("SKU-A", "SKU-A", nowMs = 1000L)

        // 2. Scan a different barcode SKU-B -> SendNow
        assertEquals(com.example.bluetooth.AutoSendDecision.SendNow, hidManager.evaluateAutoSend("SKU-B", config, nowMs = 3000L))
        hidManager.recordSentBarcode("SKU-B", "SKU-B", nowMs = 3000L)

        // 3. SKU-A left frame earlier (> 1.2s ago) and now re-appears in the same batch after SKU-B
        // Because SKU-B was scanned in between, SKU-A is NOT an accidental consecutive repeat -> SendNow without warning!
        hidManager.syncVisibleBarcodes(setOf("SKU-B"), nowMs = 3500L)
        val separatedInSequence = hidManager.evaluateAutoSend("SKU-A", config, nowMs = 5000L)
        assertEquals(com.example.bluetooth.AutoSendDecision.SendNow, separatedInSequence)

        // 4. Record SKU-A again, wait past consecutive window (20s later) with no other barcode -> also SendNow without warning
        hidManager.recordSentBarcode("SKU-A", "SKU-A", nowMs = 5000L)
        hidManager.syncVisibleBarcodes(emptySet(), nowMs = 24000L)
        val separatedInTime = hidManager.evaluateAutoSend("SKU-A", config, nowMs = 25500L)
        assertEquals(com.example.bluetooth.AutoSendDecision.SendNow, separatedInTime)
    }

    @Test
    fun testTurkishQLayoutHidScancodesPreventGibberish() {
        // On Turkish-Q: '.' is 0x38, '-' is 0x2E, 'i' is 0x34, 'ı' is 0x0C, 'ş' is 0x33, 'ç' is 0x37
        val dotTr = BluetoothHidManager.charToHidKey('.', KeyboardLayoutType.TR_Q)
        assertNotNull(dotTr)
        assertEquals(0x38.toByte(), dotTr!!.keyCode)
        assertEquals(BluetoothHidManager.MODIFIER_NONE, dotTr.modifier)

        val dashTr = BluetoothHidManager.charToHidKey('-', KeyboardLayoutType.TR_Q)
        assertNotNull(dashTr)
        assertEquals(0x2E.toByte(), dashTr!!.keyCode)

        val dottedITr = BluetoothHidManager.charToHidKey('i', KeyboardLayoutType.TR_Q)
        assertEquals(0x34.toByte(), dottedITr!!.keyCode)

        val dotlessITr = BluetoothHidManager.charToHidKey('ı', KeyboardLayoutType.TR_Q)
        assertEquals(0x0C.toByte(), dotlessITr!!.keyCode)

        val sCedillaTr = BluetoothHidManager.charToHidKey('ş', KeyboardLayoutType.TR_Q)
        assertEquals(0x33.toByte(), sCedillaTr!!.keyCode)

        // Verify US-Q still uses standard US HID usages
        val dotUs = BluetoothHidManager.charToHidKey('.', KeyboardLayoutType.US_Q)
        assertEquals(0x37.toByte(), dotUs!!.keyCode)
        val dashUs = BluetoothHidManager.charToHidKey('-', KeyboardLayoutType.US_Q)
        assertEquals(0x2D.toByte(), dashUs!!.keyCode)

        // Verify Turkish-Q '@' uses AltGr (0x40) + Q (0x14)
        val atTr = BluetoothHidManager.charToHidKey('@', KeyboardLayoutType.TR_Q)
        assertNotNull(atTr)
        assertEquals(0x14.toByte(), atTr!!.keyCode)
        assertEquals(BluetoothHidManager.MODIFIER_ALT_GR, atTr.modifier)
    }

    @Test
    fun testComputeKeyTimingPreventsAutoRepeatOnRepeatedDigits() {
        // Turbo preset (12ms) aligns with 11.25ms Bluetooth HID slots while maintaining crisp throughput
        val turbo = BluetoothHidManager.computeKeyTiming(5L, isSameKeyCodeAsPrev = false)
        assertEquals(12L, turbo.holdMs)
        assertEquals(10L, turbo.gapMs)

        // Default preset (14ms) keeps distinct Key-Down / Key-Up frames well below OS 250ms auto-repeat
        val fastDifferent = BluetoothHidManager.computeKeyTiming(14L, isSameKeyCodeAsPrev = false)
        assertEquals(14L, fastDifferent.holdMs)
        assertEquals(11L, fastDifferent.gapMs)

        // Consecutive identical characters (like '0'->'0' in "00326") get +10ms extra release separation
        val repeatedDigit = BluetoothHidManager.computeKeyTiming(26L, isSameKeyCodeAsPrev = true)
        assertEquals(26L, repeatedDigit.holdMs)
        assertEquals(30L, repeatedDigit.gapMs)

        // Slow safe preset stays within safe bounds (below OS typematic auto-repeat threshold)
        val safeSlow = BluetoothHidManager.computeKeyTiming(40L, isSameKeyCodeAsPrev = false)
        assertEquals(40L, safeSlow.holdMs)
        assertTrue(safeSlow.holdMs <= 50L)
        assertTrue(safeSlow.gapMs in 20L..45L)
    }

    @Test
    fun testBuildMouseReportEncodingAndClamping() {
        val report = BluetoothHidManager.buildMouseReport(
            buttonsMask = BluetoothHidManager.MOUSE_BTN_LEFT or BluetoothHidManager.MOUSE_BTN_RIGHT,
            dx = 200,
            dy = -250,
            wheel = 200
        )
        assertEquals(4, report.size)
        assertEquals(0x03.toByte(), report[0])
        assertEquals(127.toByte(), report[1])
        assertEquals((-127).toByte(), report[2])
        assertEquals(127.toByte(), report[3])

        val bootReport = BluetoothHidManager.buildBootMouseReport(
            buttonsMask = BluetoothHidManager.MOUSE_BTN_MIDDLE or 0x08,
            dx = 200,
            dy = -200
        )
        assertEquals(3, bootReport.size)
        assertEquals(0x04.toByte(), bootReport[0])
        assertEquals(127.toByte(), bootReport[1])
        assertEquals((-127).toByte(), bootReport[2])
    }

    @Test
    fun testComboHidDescriptorBalancedAndExposesKeyboardMouseConsumerIds() {
        val descriptor = BluetoothHidManager.HID_COMBO_REPORT_DESCRIPTOR
        assertTrue("Descriptor Collection/EndCollection items must balance cleanly",
            BluetoothHidManager.checkDescriptorCollectionBalance(descriptor))
        assertEquals(
            listOf(
                BluetoothHidManager.REPORT_ID_KEYBOARD,
                BluetoothHidManager.REPORT_ID_MOUSE,
                BluetoothHidManager.REPORT_ID_CONSUMER
            ),
            BluetoothHidManager.extractReportIdsFromDescriptor(descriptor)
        )
    }

    @Test
    fun testBuildConsumerReportLittleEndian() {
        val volUp = BluetoothHidManager.buildConsumerReport(BluetoothHidManager.CONSUMER_VOL_UP)
        assertEquals(2, volUp.size)
        assertEquals(0xE9.toByte(), volUp[0])
        assertEquals(0x00.toByte(), volUp[1])

        val release = BluetoothHidManager.buildConsumerReport(0)
        assertEquals(0.toByte(), release[0])
        assertEquals(0.toByte(), release[1])
    }

    @Test
    fun testAirMouseMathDeadzoneAndGravityProjection() {
        // 1. Sub-deadzone hand tremor produces zero movement
        val jitter = com.example.bluetooth.AirMouseMath.accumulateStep(
            wx = 0.02f,
            wy = -0.01f,
            wz = 0.015f,
            gx = 0f,
            gy = 6.9f,
            gz = 6.9f,
            dtSec = 0.016f,
            sensitivity = 1.0f,
            prevAccumX = 0f,
            prevAccumY = 0f
        )
        assertEquals(0, jitter.dx)
        assertEquals(0, jitter.dy)

        // 2. Right pan when phone lies flat (gravity along +Z): wz is negative on Android CCW-Z -> -wz > 0 -> +dx
        val flatRightPan = com.example.bluetooth.AirMouseMath.accumulateStep(
            wx = 0f,
            wy = 0f,
            wz = -1.2f,
            gx = 0f,
            gy = 0f,
            gz = 9.81f,
            dtSec = 0.016f,
            sensitivity = 1.2f,
            prevAccumX = 0f,
            prevAccumY = 0f
        )
        assertTrue("Expected positive dx on right pan, got ${flatRightPan.dx}", flatRightPan.dx > 0)
        assertEquals(0, flatRightPan.dy)

        // 3. Upward pitch (+wx on Android pitches top edge away/up) moves cursor up (negative dy)
        val pitchUp = com.example.bluetooth.AirMouseMath.accumulateStep(
            wx = 1.2f,
            wy = 0f,
            wz = 0f,
            gx = 0f,
            gy = 7.0f,
            gz = 7.0f,
            dtSec = 0.016f,
            sensitivity = 1.2f,
            prevAccumX = 0f,
            prevAccumY = 0f
        )
        assertTrue("Expected negative dy on upward pitch, got ${pitchUp.dy}", pitchUp.dy < 0)
    }

    @Test
    fun testSyncVisibleBarcodesStaysLockedWhileVisibleAndUnlocksWhenAbsent() {
        val hidManager = BluetoothHidManager()
        val config = testConfig.copy(
            isAutoSendEnabled = true,
            sendCooldownMs = 500L,
            requireDuplicateConfirmation = true
        )

        // 1. Scan & record CODE-LOCK, then dismiss a repeat attempt
        assertEquals(com.example.bluetooth.AutoSendDecision.SendNow, hidManager.evaluateAutoSend("CODE-LOCK", config, nowMs = 1000L))
        hidManager.recordSentBarcode("CODE-LOCK", "CODE-LOCK", nowMs = 1000L)

        // Even after > 1200ms while CODE-LOCK stays in visibleRawBarcodes, it MUST remain locked in-frame
        hidManager.syncVisibleBarcodes(setOf("CODE-LOCK"), nowMs = 3500L)
        hidManager.syncVisibleBarcodes(setOf("CODE-LOCK"), nowMs = 6000L)
        assertEquals(
            com.example.bluetooth.AutoSendDecision.SkipAlreadyActive,
            hidManager.evaluateAutoSend("CODE-LOCK", config, nowMs = 6100L)
        )

        // Once absent for < 1200ms it is still within grace period
        hidManager.syncVisibleBarcodes(emptySet(), nowMs = 6500L)
        assertEquals(
            com.example.bluetooth.AutoSendDecision.SkipAlreadyActive,
            hidManager.evaluateAutoSend("CODE-LOCK", config, nowMs = 6600L)
        )

        // Once absent for > 1200ms it unlocks and triggers duplicate confirmation on re-entry
        hidManager.syncVisibleBarcodes(emptySet(), nowMs = 8000L)
        val reEntry = hidManager.evaluateAutoSend("CODE-LOCK", config, nowMs = 8100L)
        assertTrue(reEntry is com.example.bluetooth.AutoSendDecision.RequireConfirmation)
    }

    @Test
    fun testTurkishQPunctuationAndSymbolsMapped() {
        val requiredSymbols = listOf('#', '$', '{', '[', ']', '}', '\\', '|', '<', '>', '~', '`')
        for (ch in requiredSymbols) {
            val mapped = BluetoothHidManager.charToHidKey(ch, KeyboardLayoutType.TR_Q)
            assertNotNull("Expected TR-Q HID mapping for '$ch'", mapped)
        }

        // Verify '<' and '>' map to ISO 102nd key (0x64) without / with Shift
        val lt = BluetoothHidManager.charToHidKey('<', KeyboardLayoutType.TR_Q)!!
        assertEquals(0x64.toByte(), lt.keyCode)
        assertEquals(BluetoothHidManager.MODIFIER_NONE, lt.modifier)

        val gt = BluetoothHidManager.charToHidKey('>', KeyboardLayoutType.TR_Q)!!
        assertEquals(0x64.toByte(), gt.keyCode)
        assertEquals(BluetoothHidManager.MODIFIER_SHIFT, gt.modifier)
    }

    @Test
    fun testCoalesceMouseDeltaStepClampsAndPreservesRemainder() {
        val step1 = BluetoothHidManager.coalesceMouseDeltaStep(remX = 300, remY = -260, remW = 150)
        assertEquals(127, step1.stepX)
        assertEquals(-127, step1.stepY)
        assertEquals(127, step1.stepW)
        assertEquals(173, step1.nextRemX)
        assertEquals(-133, step1.nextRemY)
        assertEquals(23, step1.nextRemW)

        val step2 = BluetoothHidManager.coalesceMouseDeltaStep(step1.nextRemX, step1.nextRemY, step1.nextRemW)
        assertEquals(127, step2.stepX)
        assertEquals(-127, step2.stepY)
        assertEquals(23, step2.stepW)
        assertEquals(46, step2.nextRemX)
        assertEquals(-6, step2.nextRemY)
        assertEquals(0, step2.nextRemW)
    }

    @Test
    fun testGyroBiasEstimatorCancelsDriftWhilePassingPanMotion() {
        val estimator = com.example.bluetooth.GyroBiasEstimator()

        // Simulate stationary phone with constant hardware gyro drift (+0.05, -0.04, +0.03 rad/s)
        var compensated = Triple(0f, 0f, 0f)
        repeat(60) {
            compensated = estimator.updateAndCompensate(0.05f, -0.04f, 0.03f)
        }
        assertTrue("X drift should converge near zero: ${compensated.first}", kotlin.math.abs(compensated.first) < 0.005f)
        assertTrue("Y drift should converge near zero: ${compensated.second}", kotlin.math.abs(compensated.second) < 0.005f)
        assertTrue("Z drift should converge near zero: ${compensated.third}", kotlin.math.abs(compensated.third) < 0.005f)

        // Intentional fast wrist pan (> 0.12 rad/s) should NOT corrupt stationary bias and should pass through
        val biasZBeforePan = estimator.biasZ
        val panCompensated = estimator.updateAndCompensate(0.05f, -0.04f, 1.50f)
        assertEquals(biasZBeforePan, estimator.biasZ, 1e-6f)
        assertTrue("Wrist pan should pass unattenuated", panCompensated.third > 1.44f)
    }

    @Test
    fun testSimpleKeyboardDescriptorValidForLinuxBlueZ() {
        val desc = BluetoothHidManager.HID_SIMPLE_KEYBOARD_DESCRIPTOR
        assertEquals(63, desc.size)
        assertTrue(
            "Simple Keyboard descriptor must have balanced HID collections",
            BluetoothHidManager.checkDescriptorCollectionBalance(desc)
        )
        assertTrue(
            "Simple Keyboard boot descriptor must have no Report IDs (uses reportId=0)",
            BluetoothHidManager.extractReportIdsFromDescriptor(desc).isEmpty()
        )
    }

    @Test
    fun testSupportsMouseAndMediaFollowsHidProfileMode() {
        val hidManager = BluetoothHidManager()
        assertEquals(com.example.bluetooth.HidProfileMode.COMBO, hidManager.hidProfileMode.value)
        assertTrue("COMBO mode must allow Mouse & Consumer Control reports", hidManager.supportsMouseAndMedia())

        hidManager.setHidProfileMode(com.example.bluetooth.HidProfileMode.SIMPLE_KEYBOARD)
        assertEquals(com.example.bluetooth.HidProfileMode.SIMPLE_KEYBOARD, hidManager.hidProfileMode.value)
        assertFalse(
            "SIMPLE_KEYBOARD (id=0) mode must block illegal Report ID 2/3 mouse/media packets",
            hidManager.supportsMouseAndMedia()
        )

        hidManager.setHidProfileMode(com.example.bluetooth.HidProfileMode.COMBO)
        assertTrue("Switching back to COMBO must re-enable Mouse & Media", hidManager.supportsMouseAndMedia())
    }

    @Test
    fun testZeroAllocAirMouseStepMatchesStandardStep() {
        val standard = com.example.bluetooth.AirMouseMath.accumulateStep(
            wx = 0.72f,
            wy = -0.35f,
            wz = -1.18f,
            gx = 0.1f,
            gy = 5.4f,
            gz = 8.1f,
            dtSec = 0.01f,
            sensitivity = 1.4f,
            prevAccumX = 0.35f,
            prevAccumY = -0.22f
        )
        val scratch = FloatArray(4)
        com.example.bluetooth.AirMouseMath.accumulateStepInto(
            wx = 0.72f,
            wy = -0.35f,
            wz = -1.18f,
            gx = 0.1f,
            gy = 5.4f,
            gz = 8.1f,
            dtSec = 0.01f,
            sensitivity = 1.4f,
            prevAccumX = 0.35f,
            prevAccumY = -0.22f,
            out = scratch
        )
        assertEquals(standard.dx, scratch[0].toInt())
        assertEquals(standard.dy, scratch[1].toInt())
        assertEquals(standard.nextAccumX, scratch[2], 1e-6f)
        assertEquals(standard.nextAccumY, scratch[3], 1e-6f)
    }

    @Test
    fun testHasTelemetryFailureStartsFalse() {
        val hidManager = BluetoothHidManager()
        assertFalse(hidManager.hasTelemetryFailure.value)
    }
}
