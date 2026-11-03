package com.example.model

import org.json.JSONObject
import java.util.UUID

/**
 * Custom data and media attached to a specific scanned QR code pattern.
 */
data class QrAttachment(
    val id: String = UUID.randomUUID().toString(),
    val qrContent: String,                         // QR content to match (e.g. "150-25-3", "TESBIH-01", etc.)
    val title: String = "",                        // e.g. "Baltic Amber Tesbih Master Piece"
    val notes: String = "",                        // Notes/description
    val imageUri: String? = null,                  // Photo URI or local file path
    val videoUri: String? = null,                  // Video URI or external link
    val customFields: Map<String, String> = emptyMap(), // Key-value metadata (e.g. "Origin": "Poland")
    val showInOverlay: Boolean = true,             // Project image badge / pills onto AR overlay
    val showInTable: Boolean = true,               // Inject title & custom fields into Mini Excel table
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("qrContent", qrContent)
            put("title", title)
            put("notes", notes)
            if (imageUri != null) put("imageUri", imageUri)
            if (videoUri != null) put("videoUri", videoUri)
            val fieldsObj = JSONObject()
            customFields.forEach { (k, v) -> fieldsObj.put(k, v) }
            put("customFields", fieldsObj)
            put("showInOverlay", showInOverlay)
            put("showInTable", showInTable)
            put("createdAtMs", createdAtMs)
        }
    }

    fun matchesQr(rawText: String): Boolean {
        val trimmed = rawText.trim()
        val pattern = qrContent.trim()
        if (pattern.isBlank()) return false
        if (pattern.equals(trimmed, ignoreCase = true)) return true
        if (trimmed.startsWith(pattern, ignoreCase = true)) return true
        if (pattern.length >= 3 && trimmed.contains(pattern, ignoreCase = true)) return true
        return false
    }

    companion object {
        fun fromJson(json: JSONObject): QrAttachment {
            val customFieldsMap = mutableMapOf<String, String>()
            val fieldsJson = json.optJSONObject("customFields")
            if (fieldsJson != null) {
                val keys = fieldsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    customFieldsMap[key] = fieldsJson.optString(key, "")
                }
            }

            return QrAttachment(
                id = json.optString("id", UUID.randomUUID().toString()),
                qrContent = json.optString("qrContent", ""),
                title = json.optString("title", ""),
                notes = json.optString("notes", ""),
                imageUri = if (json.has("imageUri") && !json.isNull("imageUri")) json.getString("imageUri") else null,
                videoUri = if (json.has("videoUri") && !json.isNull("videoUri")) json.getString("videoUri") else null,
                customFields = customFieldsMap,
                showInOverlay = json.optBoolean("showInOverlay", true),
                showInTable = json.optBoolean("showInTable", true),
                createdAtMs = json.optLong("createdAtMs", System.currentTimeMillis())
            )
        }
    }
}
