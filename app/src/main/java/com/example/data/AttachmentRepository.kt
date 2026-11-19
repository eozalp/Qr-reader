package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.debug.AppDebugLogger
import com.example.model.ImageOptimizationQuality
import com.example.model.QrAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.max

data class SavedMediaResult(
    val filePath: String,
    val isVideo: Boolean,
    val originalSizeBytes: Long,
    val savedSizeBytes: Long
)

class AttachmentRepository(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + AppDebugLogger.coroutineHandler("DATA-ATT"))
    private val storageFile = File(context.filesDir, "qr_attachments_store.json")
    val mediaDir = File(context.filesDir, "qr_media").apply { if (!exists()) mkdirs() }

    private val _attachments = MutableStateFlow<List<QrAttachment>>(emptyList())
    val attachments: StateFlow<List<QrAttachment>> = _attachments.asStateFlow()

    init {
        scope.launch {
            loadAttachments()
        }
    }

    private suspend fun loadAttachments() = withContext(Dispatchers.IO) {
        if (!storageFile.exists()) {
            _attachments.value = emptyList()
            return@withContext
        }
        try {
            val jsonStr = storageFile.readText(Charsets.UTF_8)
            val list = mutableListOf<QrAttachment>()
            if (jsonStr.isNotBlank()) {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        list.add(QrAttachment.fromJson(obj))
                    }
                }
            }
            _attachments.value = list
        } catch (e: Exception) {
            AppDebugLogger.e("DATA-ATT", "Ekler diskten yüklenemedi", e)
            _attachments.value = emptyList()
        }
    }

    private fun persistAttachments(list: List<QrAttachment>) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val array = JSONArray()
                list.forEach { array.put(it.toJson()) }
                storageFile.writeText(array.toString(), Charsets.UTF_8)
            }.onFailure { err ->
                AppDebugLogger.e("DATA-ATT", "Ekler diske yazılamadı", err)
            }
        }
    }

    fun saveAttachment(attachment: QrAttachment) {
        val current = _attachments.value.toMutableList()
        val index = current.indexOfFirst { it.id == attachment.id || (it.qrContent.trim() == attachment.qrContent.trim() && it.qrContent.isNotBlank()) }
        if (index != -1) {
            current[index] = attachment
        } else {
            current.add(attachment)
        }
        _attachments.value = current
        persistAttachments(current)
    }

    fun deleteAttachment(id: String) {
        val current = _attachments.value.toMutableList()
        val removed = current.find { it.id == id }
        current.removeAll { it.id == id }
        _attachments.value = current
        persistAttachments(current)

        // Clean up all stored local media files (multiple images & videos)
        removed?.let { att ->
            scope.launch(Dispatchers.IO) {
                att.allImages.forEach { path ->
                    runCatching {
                        val f = File(path)
                        if (f.exists() && f.parentFile?.absolutePath == mediaDir.absolutePath) f.delete()
                    }
                }
                att.allVideos.forEach { path ->
                    runCatching {
                        val f = File(path)
                        if (f.exists() && f.parentFile?.absolutePath == mediaDir.absolutePath) f.delete()
                    }
                }
            }
        }
    }

    fun findAttachmentForQr(rawText: String): QrAttachment? {
        val trimmed = rawText.trim()
        val list = _attachments.value
        // 1. Exact match
        val exact = list.find { it.qrContent.trim().equals(trimmed, ignoreCase = true) }
        if (exact != null) return exact

        // 2. Prefix / tag match if content has tag (e.g. attachment qrContent = "TESBIH-" and scanned = "TESBIH-50-250")
        val prefixMatch = list.find { it.qrContent.isNotBlank() && trimmed.startsWith(it.qrContent.trim(), ignoreCase = true) }
        if (prefixMatch != null) return prefixMatch

        // 3. Contains match
        return list.find { it.qrContent.isNotBlank() && it.qrContent.length >= 3 && trimmed.contains(it.qrContent.trim(), ignoreCase = true) }
    }

    /**
     * Backward-compatible convenience wrapper for saving media.
     */
    suspend fun saveMediaLocally(
        sourceUri: Uri,
        isVideo: Boolean,
        quality: ImageOptimizationQuality = ImageOptimizationQuality.BALANCED
    ): String? = withContext(Dispatchers.IO) {
        val res = if (isVideo) {
            saveVideoLocally(sourceUri)
        } else {
            saveImageLocally(sourceUri, quality)
        }
        res?.filePath
    }

    /**
     * Optimizes and compresses picked photo to drastically reduce storage footprint.
     */
    suspend fun saveImageLocally(
        sourceUri: Uri,
        quality: ImageOptimizationQuality
    ): SavedMediaResult? = withContext(Dispatchers.IO) {
        runCatching {
            var origSize = 0L

            // 1. Read input bytes
            val rawBytes = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(8192)
                var n: Int
                while (input.read(data).also { n = it } != -1) {
                    buffer.write(data, 0, n)
                }
                buffer.toByteArray()
            } ?: return@runCatching null

            origSize = rawBytes.size.toLong()
            val destFile = File(mediaDir, "att_img_${UUID.randomUUID()}.jpg")

            if (quality == ImageOptimizationQuality.ORIGINAL || quality.maxDimension <= 0) {
                // Keep original
                destFile.writeBytes(rawBytes)
            } else {
                // Decode bounds first
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)

                val origW = options.outWidth
                val origH = options.outHeight

                var sampleSize = 1
                val maxDim = quality.maxDimension
                if (origW > maxDim || origH > maxDim) {
                    val halfW = origW / 2
                    val halfH = origH / 2
                    while ((halfW / sampleSize) >= maxDim && (halfH / sampleSize) >= maxDim) {
                        sampleSize *= 2
                    }
                }

                // Decode bitmap with downsampling
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565 // 16-bit color saves 50% memory
                }
                var decodedBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)

                if (decodedBitmap != null) {
                    // Refine scale if still larger than maxDim
                    val currentMax = max(decodedBitmap.width, decodedBitmap.height)
                    if (currentMax > maxDim) {
                        val ratio = maxDim.toFloat() / currentMax.toFloat()
                        val targetW = (decodedBitmap.width * ratio).toInt()
                        val targetH = (decodedBitmap.height * ratio).toInt()
                        val scaled = Bitmap.createScaledBitmap(decodedBitmap, targetW, targetH, true)
                        if (scaled != decodedBitmap) {
                            decodedBitmap.recycle()
                            decodedBitmap = scaled
                        }
                    }

                    // Compress to JPEG with chosen quality
                    FileOutputStream(destFile).use { outStream ->
                        decodedBitmap.compress(Bitmap.CompressFormat.JPEG, quality.compressionQuality, outStream)
                    }
                    decodedBitmap.recycle()
                } else {
                    // Fallback to saving raw bytes if decoding fails
                    destFile.writeBytes(rawBytes)
                }
            }

            SavedMediaResult(
                filePath = destFile.absolutePath,
                isVideo = false,
                originalSizeBytes = origSize,
                savedSizeBytes = destFile.length()
            )
        }.onFailure { err ->
            AppDebugLogger.e("DATA-ATT", "Görsel kaydetme/sıkıştırma hatası", err)
        }.getOrNull()
    }

    /**
     * Copies video to persistent internal app storage.
     */
    suspend fun saveVideoLocally(sourceUri: Uri): SavedMediaResult? = withContext(Dispatchers.IO) {
        runCatching {
            val destFile = File(mediaDir, "att_vid_${UUID.randomUUID()}.mp4")
            var size = 0L
            context.contentResolver.openInputStream(sourceUri)?.use { input: InputStream ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(16384)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        size += read
                    }
                }
            }
            SavedMediaResult(
                filePath = destFile.absolutePath,
                isVideo = true,
                originalSizeBytes = size,
                savedSizeBytes = destFile.length()
            )
        }.onFailure { err ->
            AppDebugLogger.e("DATA-ATT", "Video kaydetme hatası", err)
        }.getOrNull()
    }

    /**
     * Exports all linked QR attachments into a shareable JSON backup string.
     */
    fun exportAttachmentsJson(): String {
        val array = JSONArray()
        _attachments.value.forEach { array.put(it.toJson()) }
        return array.toString(2)
    }

    /**
     * Imports QR attachments from a JSON backup string, merging with existing items.
     * Returns the count of successfully imported attachments.
     */
    fun importAttachmentsJson(jsonStr: String): Int {
        return runCatching {
            val array = JSONArray(jsonStr)
            val importedList = mutableListOf<QrAttachment>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i)
                if (obj != null) {
                    importedList.add(QrAttachment.fromJson(obj))
                }
            }
            if (importedList.isNotEmpty()) {
                val current = _attachments.value.toMutableList()
                for (item in importedList) {
                    val idx = current.indexOfFirst { it.id == item.id || (it.qrContent.trim() == item.qrContent.trim() && it.qrContent.isNotBlank()) }
                    if (idx != -1) {
                        current[idx] = item
                    } else {
                        current.add(item)
                    }
                }
                _attachments.value = current
                persistAttachments(current)
            }
            importedList.size
        }.onFailure { err ->
            AppDebugLogger.e("DATA-ATT", "Ek JSON içe aktarma hatası", err)
        }.getOrDefault(0)
    }

    fun saveAllAttachments(list: List<QrAttachment>) {
        _attachments.value = list
        persistAttachments(list)
    }

    /**
     * Computes the total byte size of stored media files on device.
     */
    fun getTotalMediaSizeBytes(): Long {
        var total = 0L
        mediaDir.listFiles()?.forEach { file ->
            if (file.isFile) total += file.length()
        }
        return total
    }

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            return "%.1f MB".format(mb)
        }
    }
}
