package com.example.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Image storage & compression quality presets.
 */
enum class ImageOptimizationQuality(
    val label: String,
    val maxDimension: Int,
    val compressionQuality: Int
) {
    COMPACT("Compact (~150 KB, 800px)", 800, 70),
    BALANCED("Balanced (~350 KB, 1280px)", 1280, 80),
    HIGH("High (~700 KB, 1920px)", 1920, 90),
    ORIGINAL("Original (Uncompressed)", 0, 100)
}

/**
 * Custom data and media attached to a specific scanned QR code pattern.
 * Supports multiple photos and videos per QR with local storage optimization.
 */
data class QrAttachment(
    val id: String = UUID.randomUUID().toString(),
    val qrContent: String,                               // QR content to match (e.g. "150-25-3", "TESBIH-01", etc.)
    val title: String = "",                              // e.g. "Baltic Amber Tesbih Master Piece"
    val notes: String = "",                              // Notes/description
    val images: List<String> = emptyList(),              // List of attached photo file paths / URIs
    val videos: List<String> = emptyList(),              // List of attached video file paths / URIs
    val customFields: Map<String, String> = emptyMap(),  // Key-value metadata (e.g. "Origin": "Poland")
    val showInOverlay: Boolean = true,                   // Project image badge / pills onto AR overlay
    val showInTable: Boolean = true,                     // Inject title & custom fields into Mini Excel table
    val createdAtMs: Long = System.currentTimeMillis(),
    // Legacy single uri fallback fields for backward compatibility
    val legacyImageUri: String? = null,
    val legacyVideoUri: String? = null
) {
    /**
     * Backward-compatible secondary constructor supporting legacy single imageUri and videoUri arguments.
     */
    constructor(
        id: String = UUID.randomUUID().toString(),
        qrContent: String,
        title: String = "",
        notes: String = "",
        imageUri: String? = null,
        videoUri: String? = null,
        customFields: Map<String, String> = emptyMap(),
        showInOverlay: Boolean = true,
        showInTable: Boolean = true,
        createdAtMs: Long = System.currentTimeMillis()
    ) : this(
        id = id,
        qrContent = qrContent,
        title = title,
        notes = notes,
        images = listOfNotNull(imageUri),
        videos = listOfNotNull(videoUri),
        customFields = customFields,
        showInOverlay = showInOverlay,
        showInTable = showInTable,
        createdAtMs = createdAtMs,
        legacyImageUri = imageUri,
        legacyVideoUri = videoUri
    )

    // Backwards-compatible single media accessors
    val imageUri: String?
        get() = images.firstOrNull() ?: legacyImageUri

    val videoUri: String?
        get() = videos.firstOrNull() ?: legacyVideoUri

    val allImages: List<String>
        get() = if (images.isNotEmpty()) images else listOfNotNull(legacyImageUri)

    val allVideos: List<String>
        get() = if (videos.isNotEmpty()) videos else listOfNotNull(legacyVideoUri)

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("qrContent", qrContent)
            put("title", title)
            put("notes", notes)

            // Multiple images & videos arrays
            val imgArr = JSONArray()
            allImages.forEach { imgArr.put(it) }
            put("images", imgArr)

            val vidArr = JSONArray()
            allVideos.forEach { vidArr.put(it) }
            put("videos", vidArr)

            // Legacy fields for backward compatibility with existing parsers
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

            // Parse multiple images array
            val imgList = mutableListOf<String>()
            val imgArr = json.optJSONArray("images")
            if (imgArr != null) {
                for (i in 0 until imgArr.length()) {
                    val p = imgArr.optString(i, "")
                    if (p.isNotBlank()) imgList.add(p)
                }
            }
            // Fallback to single legacy imageUri
            val legacyImg = if (json.has("imageUri") && !json.isNull("imageUri")) json.getString("imageUri") else null
            if (imgList.isEmpty() && !legacyImg.isNullOrBlank()) {
                imgList.add(legacyImg)
            }

            // Parse multiple videos array
            val vidList = mutableListOf<String>()
            val vidArr = json.optJSONArray("videos")
            if (vidArr != null) {
                for (i in 0 until vidArr.length()) {
                    val p = vidArr.optString(i, "")
                    if (p.isNotBlank()) vidList.add(p)
                }
            }
            // Fallback to single legacy videoUri
            val legacyVid = if (json.has("videoUri") && !json.isNull("videoUri")) json.getString("videoUri") else null
            if (vidList.isEmpty() && !legacyVid.isNullOrBlank()) {
                vidList.add(legacyVid)
            }

            return QrAttachment(
                id = json.optString("id", UUID.randomUUID().toString()),
                qrContent = json.optString("qrContent", ""),
                title = json.optString("title", ""),
                notes = json.optString("notes", ""),
                images = imgList,
                videos = vidList,
                customFields = customFieldsMap,
                showInOverlay = json.optBoolean("showInOverlay", true),
                showInTable = json.optBoolean("showInTable", true),
                createdAtMs = json.optLong("createdAtMs", System.currentTimeMillis()),
                legacyImageUri = legacyImg,
                legacyVideoUri = legacyVid
            )
        }
    }
}
