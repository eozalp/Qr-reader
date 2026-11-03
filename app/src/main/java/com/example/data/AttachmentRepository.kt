package com.example.data

import android.content.Context
import android.net.Uri
import com.example.model.QrAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class AttachmentRepository(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val storageFile = File(context.filesDir, "qr_attachments_store.json")
    private val mediaDir = File(context.filesDir, "qr_media").apply { if (!exists()) mkdirs() }

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
        } catch (_: Exception) {
            _attachments.value = emptyList()
        }
    }

    private fun persistAttachments(list: List<QrAttachment>) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val array = JSONArray()
                list.forEach { array.put(it.toJson()) }
                storageFile.writeText(array.toString(), Charsets.UTF_8)
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

        // Clean up stored local media files
        removed?.let { att ->
            scope.launch(Dispatchers.IO) {
                att.imageUri?.let { path ->
                    val f = File(path)
                    if (f.exists() && f.parentFile?.absolutePath == mediaDir.absolutePath) f.delete()
                }
                att.videoUri?.let { path ->
                    val f = File(path)
                    if (f.exists() && f.parentFile?.absolutePath == mediaDir.absolutePath) f.delete()
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
     * Copies media from a picked content Uri into permanent private internal storage.
     */
    suspend fun saveMediaLocally(sourceUri: Uri, isVideo: Boolean): String? = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            val ext = if (isVideo) "mp4" else "jpg"
            val destFile = File(mediaDir, "att_${UUID.randomUUID()}.$ext")
            context.contentResolver.openInputStream(sourceUri)?.use { input: InputStream ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        }.getOrNull()
    }
}
