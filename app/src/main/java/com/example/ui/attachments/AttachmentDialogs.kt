package com.example.ui.attachments

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.AttachmentRepository
import com.example.model.QrAttachment
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full lightbox & metadata viewer for a QR attachment.
 */
@Composable
fun AttachmentDetailDialog(
    attachment: QrAttachment,
    onDismiss: () -> Unit,
    onEdit: (QrAttachment) -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Header: Title & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = attachment.title.ifBlank { "Linked QR Data" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Linked to QR: '${attachment.qrContent}'",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Attached Photo (Lightbox style)
                if (!attachment.imageUri.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        AsyncImage(
                            model = File(attachment.imageUri).takeIf { it.exists() } ?: Uri.parse(attachment.imageUri),
                            contentDescription = "Attached Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Attached Video Affordance
                if (!attachment.videoUri.isNullOrBlank()) {
                    Button(
                        onClick = {
                            runCatching {
                                val uriToOpen = if (attachment.videoUri.startsWith("http")) {
                                    Uri.parse(attachment.videoUri)
                                } else {
                                    Uri.fromFile(File(attachment.videoUri))
                                }
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uriToOpen, "video/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(context, "Cannot open video player: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play Attached Video", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Notes
                if (attachment.notes.isNotBlank()) {
                    Text(
                        text = "Notes & Specs:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = attachment.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Custom Key-Value Fields
                if (attachment.customFields.isNotEmpty()) {
                    Text(
                        text = "Properties:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        attachment.customFields.forEach { (k, v) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = {
                            onDismiss()
                            onEdit(attachment)
                        }
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit Attachment")
                    }
                }
            }
        }
    }
}

/**
 * Editor dialog for attaching data, photo, and video to a pre-scanned QR code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAttachmentDialog(
    initialAttachment: QrAttachment? = null,
    defaultQrCode: String = "",
    attachmentRepository: AttachmentRepository,
    onDismiss: () -> Unit,
    onSaved: (QrAttachment) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var qrContent by remember { mutableStateOf(initialAttachment?.qrContent ?: defaultQrCode) }
    var title by remember { mutableStateOf(initialAttachment?.title ?: "") }
    var notes by remember { mutableStateOf(initialAttachment?.notes ?: "") }
    var imagePath by remember { mutableStateOf(initialAttachment?.imageUri) }
    var videoPath by remember { mutableStateOf(initialAttachment?.videoUri) }
    var showInOverlay by remember { mutableStateOf(initialAttachment?.showInOverlay ?: true) }
    var showInTable by remember { mutableStateOf(initialAttachment?.showInTable ?: true) }

    // Key-value attributes
    var newKey by remember { mutableStateOf("") }
    var newVal by remember { mutableStateOf("") }
    var customFields by remember { mutableStateOf(initialAttachment?.customFields ?: emptyMap()) }

    var isSaving by remember { mutableStateOf(false) }

    // Photo Picker Launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val savedPath = attachmentRepository.saveMediaLocally(uri, isVideo = false)
                if (savedPath != null) {
                    imagePath = savedPath
                    Toast.makeText(context, "Photo attached successfully!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Video Picker Launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val savedPath = attachmentRepository.saveMediaLocally(uri, isVideo = true)
                if (savedPath != null) {
                    videoPath = savedPath
                    Toast.makeText(context, "Video attached successfully!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialAttachment != null) "Edit QR Attachment" else "Attach Data to QR")
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = qrContent,
                        onValueChange = { qrContent = it },
                        label = { Text("Target QR Code Text / Match") },
                        placeholder = { Text("e.g. 150-25 or TESBIH-01") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Item Title / Label") },
                        placeholder = { Text("e.g. Amber Rosary Model X") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                // Media: Photo Section
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Photo Attachment", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }

                                if (imagePath != null) {
                                    IconButton(onClick = { imagePath = null }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove photo", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (imagePath != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black)
                                ) {
                                    AsyncImage(
                                        model = File(imagePath!!).takeIf { it.exists() } ?: Uri.parse(imagePath!!),
                                        contentDescription = "Selected photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            } else {
                                Button(
                                    onClick = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Choose Photo from Gallery")
                                }
                            }
                        }
                    }
                }

                // Media: Video Section
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Video Attachment", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }

                                if (videoPath != null) {
                                    IconButton(onClick = { videoPath = null }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove video", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (videoPath != null) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.VideoFile, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Video attached (${File(videoPath!!).name})",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            } else {
                                Button(
                                    onClick = {
                                        videoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Choose Video from Gallery")
                                }
                            }
                        }
                    }
                }

                // Notes Field
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes & Specifications") },
                        placeholder = { Text("Any details, remarks, or instructions...") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }

                // Custom Key-Value Properties
                item {
                    Column {
                        Text("Custom Properties (Key-Value):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))

                        customFields.forEach { (k, v) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("$k: $v", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                IconButton(onClick = {
                                    val map = customFields.toMutableMap()
                                    map.remove(k)
                                    customFields = map
                                }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = newKey,
                                onValueChange = { newKey = it },
                                placeholder = { Text("e.g. Origin") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = newVal,
                                onValueChange = { newVal = it },
                                placeholder = { Text("e.g. Poland") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(onClick = {
                                if (newKey.isNotBlank() && newVal.isNotBlank()) {
                                    customFields = customFields + (newKey.trim() to newVal.trim())
                                    newKey = ""
                                    newVal = ""
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Add Property")
                            }
                        }
                    }
                }

                // Display Toggles
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Badge/Thumbnail in AR Overlay", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = showInOverlay, onCheckedChange = { showInOverlay = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show in Mini Excel Table", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = showInTable, onCheckedChange = { showInTable = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (qrContent.isNotBlank()) {
                        isSaving = true
                        val attachment = (initialAttachment ?: QrAttachment(qrContent = qrContent)).copy(
                            qrContent = qrContent.trim(),
                            title = title.trim(),
                            notes = notes.trim(),
                            imageUri = imagePath,
                            videoUri = videoPath,
                            customFields = customFields,
                            showInOverlay = showInOverlay,
                            showInTable = showInTable
                        )
                        attachmentRepository.saveAttachment(attachment)
                        onSaved(attachment)
                        onDismiss()
                    }
                },
                enabled = qrContent.isNotBlank() && !isSaving
            ) {
                Text("Save Attachment")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Manager sheet listing all linked QR codes with attached photos and videos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedAttachmentsSheet(
    attachmentRepository: AttachmentRepository,
    onDismiss: () -> Unit,
    onAddNew: () -> Unit,
    onViewDetail: (QrAttachment) -> Unit
) {
    val attachments by attachmentRepository.attachments.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Attachment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Linked QR Data & Media (${attachments.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onAddNew,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pre-Scan & Link")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (attachments.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No QR attachments yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pre-scan any QR code to attach photos, videos, specifications, or notes that appear when scanned.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(attachments) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onViewDetail(item) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail or Icon
                                if (!item.imageUri.isNullOrBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .size(50.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black)
                                    ) {
                                        AsyncImage(
                                            model = File(item.imageUri).takeIf { it.exists() } ?: Uri.parse(item.imageUri),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(50.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.QrCodeScanner,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title.ifBlank { item.qrContent },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "QR: '${item.qrContent}'",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1
                                    )
                                    if (item.videoUri != null) {
                                        Text(
                                            text = "🎥 Video attached",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }

                                IconButton(onClick = { attachmentRepository.deleteAttachment(item.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
