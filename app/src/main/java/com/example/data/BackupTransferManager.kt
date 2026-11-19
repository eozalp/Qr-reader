package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.debug.AppDebugLogger
import com.example.model.QrAttachment
import com.example.model.QrProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Data preview of an import payload before the user commits it to storage.
 */
data class ImportPreviewData(
    val profiles: List<QrProfile> = emptyList(),
    val attachments: List<QrAttachment> = emptyList(),
    val mediaMap: Map<String, String> = emptyMap(), // mediaKey -> base64 string (JSON import)
    val stagedMediaFiles: Map<String, File> = emptyMap(), // relativeZipPath -> temp file (ZIP import)
    val hasMedia: Boolean = false,
    val totalMediaBytesEstimate: Long = 0L,
    val rawJson: String = "",
    val isZipArchive: Boolean = false
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
 * Manager handling granular selection, serialization, media bundling, zip archiving, and restoration
 * for Profiles and QR Linked Data (with or without media).
 */
object BackupTransferManager {

    /**
     * Serializes selected profiles and/or attachments into a JSON bundle.
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

                for (imgPath in att.allImages) {
                    val file = File(imgPath)
                    if (file.exists() && file.isFile && file.length() <= 15 * 1024 * 1024) {
                        val key = "img_${UUID.randomUUID().toString().take(8)}_${file.name}"
                        val b64 = runCatching {
                            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        }.onFailure { err ->
                            AppDebugLogger.w("DATA-BACKUP", "Görsel Base64 kodlanamadı: ${file.name}", err)
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

                for (vidPath in att.allVideos) {
                    val file = File(vidPath)
                    if (file.exists() && file.isFile && file.length() <= 20 * 1024 * 1024) {
                        val key = "vid_${UUID.randomUUID().toString().take(8)}_${file.name}"
                        val b64 = runCatching {
                            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        }.onFailure { err ->
                            AppDebugLogger.w("DATA-BACKUP", "Video Base64 kodlanamadı: ${file.name}", err)
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
     * Creates a single portable .zip archive file containing JSON and media files.
     * Perfect for sharing via Android share sheet or exporting to local storage.
     */
    fun createExportZipFile(
        context: Context,
        profiles: List<QrProfile>,
        attachments: List<QrAttachment>,
        includeMedia: Boolean,
        filePrefix: String = "qr_bundle"
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zipFile = File(exportDir, "${filePrefix}_${System.currentTimeMillis()}.zip")
        FileOutputStream(zipFile).use { fos ->
            writeExportZipToStream(fos, profiles, attachments, includeMedia)
        }
        return zipFile
    }

    /**
     * Writes the ZIP archive directly into any output stream (e.g. SAF OutputStream).
     * Structure inside the ZIP:
     * - "data.json": Manifest containing profiles and attachments
     * - "media/img_xxx.jpg": Attached photos
     * - "media/vid_xxx.mp4": Attached videos
     */
    fun writeExportZipToStream(
        outputStream: OutputStream,
        profiles: List<QrProfile>,
        attachments: List<QrAttachment>,
        includeMedia: Boolean
    ) {
        val zipOut = ZipOutputStream(outputStream.buffered())
        val addedZipEntries = mutableSetOf<String>()

        val manifestObj = JSONObject()
        manifestObj.put("format", "qr_live_calc_zip_bundle")
        manifestObj.put("version", 3)
        manifestObj.put("exportedAtMs", System.currentTimeMillis())
        manifestObj.put("includeMedia", includeMedia)

        // 1. Profiles
        val profArray = JSONArray()
        profiles.forEach { profArray.put(it.toJson()) }
        manifestObj.put("profiles", profArray)

        // 2. Attachments & Media entries inside ZIP
        val attArray = JSONArray()

        for (att in attachments) {
            if (!includeMedia) {
                attArray.put(
                    att.copy(
                        images = emptyList(),
                        videos = emptyList(),
                        legacyImageUri = null,
                        legacyVideoUri = null
                    ).toJson()
                )
            } else {
                val relativeImages = mutableListOf<String>()
                val relativeVideos = mutableListOf<String>()

                for (imgPath in att.allImages) {
                    val file = File(imgPath)
                    if (file.exists() && file.isFile) {
                        val cleanName = file.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                        val zipEntryName = "media/img_${UUID.randomUUID().toString().take(6)}_$cleanName"
                        if (addedZipEntries.add(zipEntryName)) {
                            zipOut.putNextEntry(ZipEntry(zipEntryName))
                            FileInputStream(file).use { fis -> fis.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                        relativeImages.add(zipEntryName)
                    } else {
                        relativeImages.add(imgPath)
                    }
                }

                for (vidPath in att.allVideos) {
                    val file = File(vidPath)
                    if (file.exists() && file.isFile) {
                        val cleanName = file.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                        val zipEntryName = "media/vid_${UUID.randomUUID().toString().take(6)}_$cleanName"
                        if (addedZipEntries.add(zipEntryName)) {
                            zipOut.putNextEntry(ZipEntry(zipEntryName))
                            FileInputStream(file).use { fis -> fis.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                        relativeVideos.add(zipEntryName)
                    } else {
                        relativeVideos.add(vidPath)
                    }
                }

                val packagedAtt = att.copy(images = relativeImages, videos = relativeVideos)
                attArray.put(packagedAtt.toJson())
            }
        }

        manifestObj.put("attachments", attArray)

        // Write "data.json" into the ZIP
        val jsonBytes = manifestObj.toString(2).toByteArray(Charsets.UTF_8)
        zipOut.putNextEntry(ZipEntry("data.json"))
        zipOut.write(jsonBytes)
        zipOut.closeEntry()

        zipOut.finish()
        zipOut.flush()
    }

    /**
     * Builds an Android share intent for a single ZIP file containing media and JSON.
     */
    fun getShareIntentForZip(
        context: Context,
        zipFile: File,
        subject: String = "QR Live Calc Backup Archive"
    ): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Parses an incoming ZIP archive stream into an ImportPreviewData.
     * Extracts media into a temporary cache staging directory.
     */
    fun parseImportFromZip(inputStream: InputStream, cacheDir: File): ImportPreviewData {
        val stagingDir = File(cacheDir, "import_staging_${System.currentTimeMillis()}").apply { mkdirs() }
        val stagedFiles = mutableMapOf<String, File>()
        var manifestJsonString: String? = null
        var totalMediaBytes = 0L

        ZipInputStream(inputStream.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (!entry.isDirectory) {
                    if (entryName == "data.json" || entryName.endsWith(".json")) {
                        val byteOut = ByteArrayOutputStream()
                        zis.copyTo(byteOut)
                        manifestJsonString = byteOut.toString(Charsets.UTF_8.name())
                    } else if (entryName.startsWith("media/") || entryName.endsWith(".jpg") || entryName.endsWith(".png") || entryName.endsWith(".mp4") || entryName.endsWith(".webm")) {
                        val cleanFileName = entryName.substringAfterLast("/")
                        val tempFile = File(stagingDir, cleanFileName)
                        FileOutputStream(tempFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        stagedFiles[entryName] = tempFile
                        stagedFiles[cleanFileName] = tempFile
                        totalMediaBytes += tempFile.length()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (manifestJsonString.isNullOrBlank()) {
            return ImportPreviewData()
        }

        val basePreview = parseImportPreview(manifestJsonString!!)
        return basePreview.copy(
            stagedMediaFiles = stagedFiles,
            hasMedia = stagedFiles.isNotEmpty() || basePreview.hasMedia,
            totalMediaBytesEstimate = if (totalMediaBytes > 0) totalMediaBytes else basePreview.totalMediaBytesEstimate,
            isZipArchive = true
        )
    }

    /**
     * Inspects incoming URI or stream automatically. Detects whether it is a ZIP archive or JSON text,
     * and constructs the import preview seamlessly.
     */
    fun parseImportAuto(context: Context, uri: Uri): ImportPreviewData {
        return runCatching {
            val stream = context.contentResolver.openInputStream(uri) ?: return ImportPreviewData()
            val buffered = BufferedInputStream(stream)
            buffered.mark(1024)

            val header = ByteArray(4)
            val bytesRead = buffered.read(header, 0, 4)
            buffered.reset()

            // Check PK zip signature (0x50, 0x4B, 0x03, 0x04) or (0x50, 0x4B, 0x05, 0x06)
            val isZip = bytesRead >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()

            if (isZip) {
                parseImportFromZip(buffered, context.cacheDir)
            } else {
                val text = buffered.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseImportPreview(text)
            }
        }.onFailure { err ->
            AppDebugLogger.e("DATA-BACKUP", "İçe aktarma dosyası okunamadı", err)
        }.getOrElse {
            ImportPreviewData()
        }
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
                val format = obj.optString("format")
                if (format == "qr_live_calc_bundle" || format == "qr_live_calc_zip_bundle") {
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
                    profilesList.add(QrProfile.fromJson(obj))
                } else if (obj.has("qrContent")) {
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
        }.onFailure { err ->
            AppDebugLogger.e("DATA-BACKUP", "Yedek JSON ayrıştırılamadı", err)
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
     * writing selected attachments, and conditionally restoring media files
     * from either a single ZIP archive or Base64 payloads.
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

                if (importMedia) {
                    // Extract photos: check staged ZIP files first, then Base64
                    for (imgKey in att.allImages) {
                        val stagedFile = preview.stagedMediaFiles[imgKey] ?: preview.stagedMediaFiles[imgKey.substringAfterLast("/")]
                        if (stagedFile != null && stagedFile.exists()) {
                            val dest = File(mediaDir, "zip_${System.currentTimeMillis()}_${stagedFile.name}")
                            runCatching {
                                stagedFile.copyTo(dest, overwrite = true)
                                resolvedImages.add(dest.absolutePath)
                                mediaRestored++
                            }.onFailure { err ->
                                AppDebugLogger.w("DATA-BACKUP", "ZIP görsel geri yüklenemedi: $imgKey", err)
                                resolvedImages.add(imgKey)
                            }
                        } else {
                            val b64 = preview.mediaMap[imgKey]
                            if (b64 != null) {
                                val cleanName = imgKey.substringAfterLast("/")
                                val file = File(mediaDir, "imp_${System.currentTimeMillis()}_$cleanName")
                                runCatching {
                                    file.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                                    resolvedImages.add(file.absolutePath)
                                    mediaRestored++
                                }.onFailure { err ->
                                    AppDebugLogger.w("DATA-BACKUP", "Base64 görsel yazılamadı: $imgKey", err)
                                    resolvedImages.add(imgKey)
                                }
                            } else if (File(imgKey).exists()) {
                                resolvedImages.add(imgKey)
                            }
                        }
                    }

                    // Extract videos: check staged ZIP files first, then Base64
                    for (vidKey in att.allVideos) {
                        val stagedFile = preview.stagedMediaFiles[vidKey] ?: preview.stagedMediaFiles[vidKey.substringAfterLast("/")]
                        if (stagedFile != null && stagedFile.exists()) {
                            val dest = File(mediaDir, "zip_${System.currentTimeMillis()}_${stagedFile.name}")
                            runCatching {
                                stagedFile.copyTo(dest, overwrite = true)
                                resolvedVideos.add(dest.absolutePath)
                                mediaRestored++
                            }.onFailure { err ->
                                AppDebugLogger.w("DATA-BACKUP", "ZIP video geri yüklenemedi: $vidKey", err)
                                resolvedVideos.add(vidKey)
                            }
                        } else {
                            val b64 = preview.mediaMap[vidKey]
                            if (b64 != null) {
                                val cleanName = vidKey.substringAfterLast("/")
                                val file = File(mediaDir, "imp_${System.currentTimeMillis()}_$cleanName")
                                runCatching {
                                    file.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                                    resolvedVideos.add(file.absolutePath)
                                    mediaRestored++
                                }.onFailure { err ->
                                    AppDebugLogger.w("DATA-BACKUP", "Base64 video yazılamadı: $vidKey", err)
                                    resolvedVideos.add(vidKey)
                                }
                            } else if (File(vidKey).exists()) {
                                resolvedVideos.add(vidKey)
                            }
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
