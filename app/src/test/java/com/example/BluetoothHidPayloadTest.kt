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
    }

    @Test
    fun testAutoSendDebounceCooldown() {
        val hidManager = BluetoothHidManager()
        val config = testConfig.copy(
            isAutoSendEnabled = true,
            sendCooldownMs = 1000L
        )

        assertTrue(hidManager.shouldAutoSend("CODE-A", config))
        hidManager.recordSentBarcode("CODE-A")
        // Immediate repeat should be suppressed by debounce cooldown
        assertFalse(hidManager.shouldAutoSend("CODE-A", config))

        // Different barcode should not be blocked
        assertTrue(hidManager.shouldAutoSend("CODE-B", config))
    }
}
