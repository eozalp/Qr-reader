package com.example.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Defines what part of the barcode or calculated output should be transmitted as keystrokes.
 */
enum class KeyboardSendTarget(val label: String, val description: String) {
    WHOLE_RAW("Tüm Ham Barkod", "Okunan barkodun ham içeriğinin tamamı"),
    FORMULA_RESULT("Hesaplama Sonucu", "Formülden çıkan nihai sayısal veya biçimlendirilmiş sonuç"),
    SINGLE_VARIABLE("Tek Bir Değişken (Parça)", "Yalnızca seçilen tek bir değişken değeri"),
    MULTIPLE_VARIABLES("Çoklu Değişkenler (Parçalar)", "Seçilen birden fazla değişken sırayla ve aralarına tuş konularak"),
    CUSTOM_TEMPLATE("Özel Şablon", "{degisken} etiketleriyle serbest metin şablonu")
}

/**
 * Key pressed automatically after the payload is transmitted (Terminator / Suffix).
 */
enum class KeyboardEndingKey(val label: String, val hidKeyCode: Byte?) {
    ENTER("Enter (↵ Satır Sonu)", 0x28.toByte()),
    TAB("Tab (⇥ Sonraki Hücre/Alan)", 0x2B.toByte()),
    TAB_THEN_ENTER("Tab ⇥ ardından Enter ↵", null),
    SPACE("Boşluk (Space)", 0x2C.toByte()),
    NONE("Yok (Yalnızca Metin)", null)
}

/**
 * Target computer keyboard layout so HID USB scancodes match the OS layout
 * (prevents '.' typing 'ç' or '-' typing '*' on Turkish Q Windows/Mac PCs).
 */
enum class KeyboardLayoutType(val label: String) {
    TR_Q("Türkçe Q (TR)"),
    US_Q("İngilizce Q (US)")
}

/**
 * Configuration for transmitting barcode data to computers/tablets as a Bluetooth HID Keyboard
 * or to local phone apps via the ALVETAK QR IME Keyboard.
 */
data class BluetoothKeyboardConfig(
    val isEnabled: Boolean = true,
    val isAutoSendEnabled: Boolean = false, // Auto-send to computer upon scan
    val sendTarget: KeyboardSendTarget = KeyboardSendTarget.WHOLE_RAW,
    val selectedVariableName: String = "", // e.g. "a", "fiyat", "weight"
    val selectedVariables: List<String> = emptyList(), // e.g. ["fiyat", "adet", "kdv"]
    val variableSeparator: String = "\t", // Separator between variables (default TAB for Excel/ERP)
    val endingKey: KeyboardEndingKey = KeyboardEndingKey.ENTER,
    val customTemplate: String = "{a}\t{b}",
    val prefixText: String = "",
    val postfixText: String = "",
    val typingDelayMs: Long = 12L, // Safe Bluetooth HID frame timing to prevent key auto-repeat
    val sendCooldownMs: Long = 2000L, // Minimum cooldown between distinct repeat confirmations
    val convertTurkishChars: Boolean = false, // Only needed if US_Q is selected on target PC
    val keyboardLayout: KeyboardLayoutType = KeyboardLayoutType.TR_Q,
    val requireDuplicateConfirmation: Boolean = true // Batch Inventory Count guard: ask before re-sending same barcode
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("isEnabled", isEnabled)
            put("isAutoSendEnabled", isAutoSendEnabled)
            put("sendTarget", sendTarget.name)
            put("selectedVariableName", selectedVariableName)
            val varArray = JSONArray()
            selectedVariables.forEach { varArray.put(it) }
            put("selectedVariables", varArray)
            put("variableSeparator", variableSeparator)
            put("endingKey", endingKey.name)
            put("customTemplate", customTemplate)
            put("prefixText", prefixText)
            put("postfixText", postfixText)
            put("typingDelayMs", typingDelayMs)
            put("sendCooldownMs", sendCooldownMs)
            put("convertTurkishChars", convertTurkishChars)
            put("keyboardLayout", keyboardLayout.name)
            put("requireDuplicateConfirmation", requireDuplicateConfirmation)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): BluetoothKeyboardConfig {
            val varsList = mutableListOf<String>()
            val varArr = json.optJSONArray("selectedVariables")
            if (varArr != null) {
                for (i in 0 until varArr.length()) {
                    varsList.add(varArr.optString(i))
                }
            }

            return BluetoothKeyboardConfig(
                isEnabled = json.optBoolean("isEnabled", true),
                isAutoSendEnabled = json.optBoolean("isAutoSendEnabled", false),
                sendTarget = runCatching {
                    KeyboardSendTarget.valueOf(json.optString("sendTarget", KeyboardSendTarget.WHOLE_RAW.name))
                }.getOrDefault(KeyboardSendTarget.WHOLE_RAW),
                selectedVariableName = json.optString("selectedVariableName", ""),
                selectedVariables = varsList,
                variableSeparator = json.optString("variableSeparator", "\t"),
                endingKey = runCatching {
                    KeyboardEndingKey.valueOf(json.optString("endingKey", KeyboardEndingKey.ENTER.name))
                }.getOrDefault(KeyboardEndingKey.ENTER),
                customTemplate = json.optString("customTemplate", "{a}\t{b}"),
                prefixText = json.optString("prefixText", ""),
                postfixText = json.optString("postfixText", ""),
                typingDelayMs = json.optLong("typingDelayMs", 12L),
                sendCooldownMs = json.optLong("sendCooldownMs", 2000L),
                convertTurkishChars = json.optBoolean("convertTurkishChars", false),
                keyboardLayout = runCatching {
                    KeyboardLayoutType.valueOf(json.optString("keyboardLayout", KeyboardLayoutType.TR_Q.name))
                }.getOrDefault(KeyboardLayoutType.TR_Q),
                requireDuplicateConfirmation = json.optBoolean("requireDuplicateConfirmation", true)
            )
        }
    }
}
