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
    }
}
