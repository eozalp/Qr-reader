package com.example.data

import android.content.Context
import android.util.Base64
import com.example.model.QrAttachment
import com.example.model.QrProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Data preview of an import payload before the user commits it to storage.
 */
data class ImportPreviewData(
    val profiles: List<QrProfile> = emptyList(),
    val attachments: List<QrAttachment> = emptyList(),
    val mediaMap: Map<String, String> = emptyMap(), // mediaKey -> base64 string
    val hasMedia: Boolean = false,
    val totalMediaBytesEstimate: Long = 0L,
    val rawJson: String = ""
)

/**
 * Result statistics after executing an import.
 */
data class ImportResult(
    val profilesCount: Int,
    val attachmentsCount: Int,
    val mediaFilesCount: Int
)

/**
 * Manager handling granular selection, serialization, media bundling, and restoration
 * for Profiles and QR Linked Data (with or without media).
 */
object BackupTransferManager {

    /**
     * Serializes selected profiles and/or attachments into a JSON bundle.
     * When [includeMedia] is true, photos and short videos are encoded in Base64
     * for a 100% self-contained portable backup file.
     * When [includeMedia] is false, photos and videos are excluded for an ultra-lightweight export.
     */
    fun createExportJson(
        profiles: List<QrProfile>,
        attachments: List<QrAttachment>,
        includeMedia: Boolean
    ): String {
        val root = JSONObject()
        root.put("format", "qr_live_calc_bundle")
        root.put("version", 2)
        root.put("exportedAtMs", System.currentTimeMillis())
        root.put("includeMedia", includeMedia)

        // 1. Profiles array
        val profArray = JSONArray()
        profiles.forEach { profArray.put(it.toJson()) }
        root.put("profiles", profArray)

        // 2. Attachments & Media Payloads
        val mediaObj = JSONObject()
        val attArray = JSONArray()

        for (att in attachments) {
            if (!includeMedia) {
                // Strip all local media paths for lightweight metadata-only export
                val stripped = att.copy(
                    images = emptyList(),
                    videos = emptyList(),
                    legacyImageUri = null,
                    legacyVideoUri = null
                )
                attArray.put(stripped.toJson())
            } else {
                val packagedImages = mutableListOf<String>()
                val packagedVideos = mutableListOf<String>()

                // Package attached photos
                for (imgPath in att.allImages) {
                    val file = File(imgPath)
                    if (file.exists() && file.isFile && file.length() <= 15 * 1024 * 1024) {
                        val key = "img_${UUID.randomUUID().toString().take(8)}_${file.name}"
                        val b64 = runCatching {
                            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        }.getOrNull()

                        if (b64 != null) {
                            mediaObj.put(key, b64)
                            packagedImages.add(key)
                        } else {
                            packagedImages.add(imgPath)
                        }
                    } else {
                        packagedImages.add(imgPath)
                    }
                }

                // Package attached videos (up to 20 MB)
                for (vidPath in att.allVideos) {
                    val file = File(vidPath)
                    if (file.exists() && file.isFile && file.length() <= 20 * 1024 * 1024) {
                        val key = "vid_${UUID.randomUUID().toString().take(8)}_${file.name}"
                        val b64 = runCatching {
                            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        }.getOrNull()

                        if (b64 != null) {
                            mediaObj.put(key, b64)
                            packagedVideos.add(key)
                        } else {
                            packagedVideos.add(vidPath)
                        }
                    } else {
                        packagedVideos.add(vidPath)
                    }
                }

                val packagedAtt = att.copy(images = packagedImages, videos = packagedVideos)
                attArray.put(packagedAtt.toJson())
            }
        }

        root.put("attachments", attArray)
        root.put("media", mediaObj)

        return root.toString(2)
    }

    /**
     * Inspects incoming JSON and constructs a preview of discovered profiles,
     * attachments, and media payloads so the user can select what to import.
     */
    fun parseImportPreview(jsonStr: String): ImportPreviewData {
        val trimmed = jsonStr.trim()
        if (trimmed.isBlank()) return ImportPreviewData()

        val profilesList = mutableListOf<QrProfile>()
        val attachmentsList = mutableListOf<QrAttachment>()
        val mediaMap = mutableMapOf<String, String>()
        var totalMediaBytes = 0L

        runCatching {
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                if (obj.optString("format") == "qr_live_calc_bundle") {
                    // Modern structured bundle
                    val pArr = obj.optJSONArray("profiles")
                    if (pArr != null) {
                        for (i in 0 until pArr.length()) {
                            val pObj = pArr.optJSONObject(i)
                            if (pObj != null) profilesList.add(QrProfile.fromJson(pObj))
                        }
                    }
                    val aArr = obj.optJSONArray("attachments")
                    if (aArr != null) {
                        for (i in 0 until aArr.length()) {
                            val aObj = aArr.optJSONObject(i)
                            if (aObj != null) attachmentsList.add(QrAttachment.fromJson(aObj))
                        }
                    }
                    val mObj = obj.optJSONObject("media")
                    if (mObj != null) {
                        val keys = mObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val b64 = mObj.optString(k, "")
                            if (b64.isNotBlank()) {
                                mediaMap[k] = b64
                                totalMediaBytes += (b64.length * 3L / 4L)
                            }
                        }
                    }
                } else if (obj.has("parsingRules") || obj.has("formula")) {
                    // Single profile JSON
                    profilesList.add(QrProfile.fromJson(obj))
                } else if (obj.has("qrContent")) {
                    // Single attachment JSON
                    attachmentsList.add(QrAttachment.fromJson(obj))
                }
            } else if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    if (item != null) {
                        if (item.has("parsingRules") || item.has("formula")) {
                            profilesList.add(QrProfile.fromJson(item))
                        } else if (item.has("qrContent")) {
                            attachmentsList.add(QrAttachment.fromJson(item))
                        }
                    }
                }
            }
        }

        return ImportPreviewData(
            profiles = profilesList,
            attachments = attachmentsList,
            mediaMap = mediaMap,
            hasMedia = mediaMap.isNotEmpty(),
            totalMediaBytesEstimate = totalMediaBytes,
            rawJson = jsonStr
        )
    }

    /**
     * Executes the user-selected import options: saving selected profiles,
     * writing selected attachments, and conditionally decoding & extracting media files.
     */
    fun executeImport(
        preview: ImportPreviewData,
        selectedProfileIds: Set<String>,
        selectedAttachmentIds: Set<String>,
        importMedia: Boolean,
        overwriteExisting: Boolean,
        profileRepository: ProfileRepository,
        attachmentRepository: AttachmentRepository?
    ): ImportResult {
        var profilesImported = 0
        var attachmentsImported = 0
        var mediaRestored = 0

        // 1. Process selected profiles
        if (selectedProfileIds.isNotEmpty()) {
            val selectedProfiles = preview.profiles.filter { it.id in selectedProfileIds }
            val currentProfiles = profileRepository.profiles.value.toMutableList()

            for (p in selectedProfiles) {
                val existingIdx = currentProfiles.indexOfFirst {
                    it.id == p.id || it.name.trim().equals(p.name.trim(), ignoreCase = true)
                }
                if (existingIdx != -1) {
                    if (overwriteExisting) {
                        currentProfiles[existingIdx] = p
                    } else {
                        currentProfiles.add(p.copy(id = UUID.randomUUID().toString(), name = "${p.name} (Imported)"))
                    }
                } else {
                    currentProfiles.add(p)
                }
                profilesImported++
            }
            profileRepository.saveAllProfiles(currentProfiles)
        }

        // 2. Process selected attachments & media
        if (selectedAttachmentIds.isNotEmpty() && attachmentRepository != null) {
            val selectedAttachments = preview.attachments.filter { it.id in selectedAttachmentIds }
            val mediaDir = attachmentRepository.mediaDir
            val currentAttachments = attachmentRepository.attachments.value.toMutableList()

            for (att in selectedAttachments) {
                val resolvedImages = mutableListOf<String>()
                val resolvedVideos = mutableListOf<String>()

                if (importMedia && preview.mediaMap.isNotEmpty()) {
                    // Extract photos
                    for (imgKey in att.allImages) {
                        val b64 = preview.mediaMap[imgKey]
                        if (b64 != null) {
                            val cleanName = imgKey.substringAfterLast("/")
                            val file = File(mediaDir, "imp_${System.currentTimeMillis()}_$cleanName")
                            runCatching {
                                file.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                                resolvedImages.add(file.absolutePath)
                                mediaRestored++
                            }.onFailure {
                                resolvedImages.add(imgKey)
                            }
                        } else if (File(imgKey).exists()) {
                            resolvedImages.add(imgKey)
                        }
                    }

                    // Extract videos
                    for (vidKey in att.allVideos) {
                        val b64 = preview.mediaMap[vidKey]
                        if (b64 != null) {
                            val cleanName = vidKey.substringAfterLast("/")
                            val file = File(mediaDir, "imp_${System.currentTimeMillis()}_$cleanName")
                            runCatching {
                                file.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                                resolvedVideos.add(file.absolutePath)
                                mediaRestored++
                            }.onFailure {
                                resolvedVideos.add(vidKey)
                            }
                        } else if (File(vidKey).exists()) {
                            resolvedVideos.add(vidKey)
                        }
                    }
                }

                val finalAtt = if (importMedia) {
                    att.copy(images = resolvedImages, videos = resolvedVideos)
                } else {
                    att.copy(images = emptyList(), videos = emptyList(), legacyImageUri = null, legacyVideoUri = null)
                }

                val existingIdx = currentAttachments.indexOfFirst {
                    it.id == finalAtt.id || (it.qrContent.trim() == finalAtt.qrContent.trim() && it.qrContent.isNotBlank())
                }
                if (existingIdx != -1) {
                    if (overwriteExisting) {
                        currentAttachments[existingIdx] = finalAtt
                    } else {
                        currentAttachments.add(finalAtt.copy(id = UUID.randomUUID().toString(), title = "${finalAtt.title} (Imported)"))
                    }
                } else {
                    currentAttachments.add(finalAtt)
                }
                attachmentsImported++
            }

            attachmentRepository.saveAllAttachments(currentAttachments)
        }

        return ImportResult(profilesImported, attachmentsImported, mediaRestored)
    }

    /**
     * Formats bytes for user display in preview dialogs.
     */
    fun formatBytes(bytes: Long): String {
        return AttachmentRepository.formatFileSize(bytes)
    }
}
