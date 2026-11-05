package com.example.ui.attachments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.data.BackupTransferManager
import com.example.data.ImportPreviewData
import java.io.BufferedReader
import java.io.InputStreamReader
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.AttachmentRepository
import com.example.model.ImageOptimizationQuality
import com.example.model.QrAttachment
import kotlinx.coroutines.launch
import java.io.File

/**
 * Embedded in-app native video player component that plays local files reliably.
 */
@Composable
fun EmbeddedVideoPlayer(
    videoPath: String,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(videoPath) {
        onDispose {
            videoViewRef?.stopPlayback()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val uri = if (videoPath.startsWith("http")) {
                        Uri.parse(videoPath)
                    } else {
                        Uri.fromFile(File(videoPath))
                    }
                    setVideoURI(uri)
                    setOnPreparedListener { mp ->
                        isPrepared = true
                        mp.isLooping = true
                    }
                    setOnErrorListener { _, _, _ ->
                        isPrepared = false
                        true
                    }
                    videoViewRef = this
                }
            },
            update = { view ->
                videoViewRef = view
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Play / Pause controls
        if (!isPlaying) {
            IconButton(
                onClick = {
                    videoViewRef?.let {
                        it.start()
                        isPlaying = true
                    }
                },
                modifier = Modifier
                    .size(54.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Video",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        } else {
            // Tap area to pause
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        videoViewRef?.let {
                            if (it.isPlaying) {
                                it.pause()
                                isPlaying = false
                            } else {
                                it.start()
                                isPlaying = true
                            }
                        }
                    }
            )
        }
    }
}

/**
 * Full lightbox & metadata viewer for a QR attachment with gallery and in-app video playback.
 */
@Composable
fun AttachmentDetailDialog(
    attachment: QrAttachment,
    onDismiss: () -> Unit,
    onEdit: (QrAttachment) -> Unit
) {
    val context = LocalContext.current
    var selectedImageIndex by remember { mutableIntStateOf(0) }
    var selectedVideoIndex by remember { mutableIntStateOf(0) }
    var isFullScreenPhoto by remember { mutableStateOf(false) }

    val images = attachment.allImages
    val videos = attachment.allVideos

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight()
                .padding(vertical = 16.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header: Title & Close
                item {
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
                                text = "Linked QR: '${attachment.qrContent}'",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                // Attached Photos Gallery
                if (images.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val currentImg = images.getOrNull(selectedImageIndex) ?: images.first()
                            val imgFile = File(currentImg)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.Black)
                                    .clickable { isFullScreenPhoto = true }
                            ) {
                                AsyncImage(
                                    model = if (imgFile.exists()) imgFile else Uri.parse(currentImg),
                                    contentDescription = "Attached Photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Image index pill
                                Surface(
                                    color = Color.Black.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "${selectedImageIndex + 1}/${images.size}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Horizontal thumbnail strip for multiple images
                            if (images.size > 1) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    itemsIndexed(images) { idx, path ->
                                        val f = File(path)
                                        val isSel = idx == selectedImageIndex
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(
                                                    width = if (isSel) 2.dp else 1.dp,
                                                    color = if (isSel) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable { selectedImageIndex = idx }
                                        ) {
                                            AsyncImage(
                                                model = if (f.exists()) f else Uri.parse(path),
                                                contentDescription = "Thumb",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // In-App Embedded Video Player
                if (videos.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Attached Video (${videos.size}):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // Video tabs if multiple videos
                            if (videos.size > 1) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    itemsIndexed(videos) { idx, _ ->
                                        FilterChip(
                                            selected = idx == selectedVideoIndex,
                                            onClick = { selectedVideoIndex = idx },
                                            label = { Text("Video ${idx + 1}", fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }

                            val currentVideo = videos.getOrNull(selectedVideoIndex) ?: videos.first()
                            EmbeddedVideoPlayer(
                                videoPath = currentVideo,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        }
                    }
                }

                // Notes
                if (attachment.notes.isNotBlank()) {
                    item {
                        Text(
                            text = "Notes & Description:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
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
                    }
                }

                // Custom Key-Value Fields
                if (attachment.customFields.isNotEmpty()) {
                    item {
                        Text(
                            text = "Specifications:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
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
                    }
                }

                // Action buttons: Edit
                item {
                    Button(
                        onClick = { onEdit(attachment) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit Attachment")
                    }
                }
            }
        }
    }

    // Full screen image zoom lightbox
    if (isFullScreenPhoto && images.isNotEmpty()) {
        val currentImg = images.getOrNull(selectedImageIndex) ?: images.first()
        val imgFile = File(currentImg)

        Dialog(
            onDismissRequest = { isFullScreenPhoto = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = if (imgFile.exists()) imgFile else Uri.parse(currentImg),
                    contentDescription = "Full Screen Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    onClick = { isFullScreenPhoto = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Editor dialog for attaching data, multiple photos, and multiple videos to a pre-scanned QR code.
 * Includes storage optimization options (size and quality).
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
    var imageList by remember { mutableStateOf<List<String>>(initialAttachment?.allImages ?: emptyList()) }
    var videoList by remember { mutableStateOf<List<String>>(initialAttachment?.allVideos ?: emptyList()) }
    var showInOverlay by remember { mutableStateOf(initialAttachment?.showInOverlay ?: true) }
    var showInTable by remember { mutableStateOf(initialAttachment?.showInTable ?: true) }

    // Optimization options
    var selectedQuality by remember { mutableStateOf(ImageOptimizationQuality.BALANCED) }

    // Key-value attributes
    var newKey by remember { mutableStateOf("") }
    var newVal by remember { mutableStateOf("") }
    var customFields by remember { mutableStateOf(initialAttachment?.customFields ?: emptyMap()) }

    var isSaving by remember { mutableStateOf(false) }
    var isOptimizingMedia by remember { mutableStateOf(false) }

    // Multiple Photo Picker Launcher
    val multiplePhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isOptimizingMedia = true
            coroutineScope.launch {
                for (uri in uris) {
                    val result = attachmentRepository.saveImageLocally(uri, selectedQuality)
                    if (result != null) {
                        imageList = imageList + result.filePath
                    }
                }
                isOptimizingMedia = false
                Toast.makeText(context, "${uris.size} photos optimized & attached!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Video Picker Launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            isOptimizingMedia = true
            coroutineScope.launch {
                val result = attachmentRepository.saveVideoLocally(uri)
                if (result != null) {
                    videoList = videoList + result.filePath
                    Toast.makeText(context, "Video attached successfully (${AttachmentRepository.formatFileSize(result.savedSizeBytes)})", Toast.LENGTH_SHORT).show()
                }
                isOptimizingMedia = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialAttachment != null) "Edit QR Attachment" else "Attach Data & Media")
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
                        label = { Text("Target QR Code Pattern / Match") },
                        placeholder = { Text("e.g. 150-25 or TESBIH-01") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Item Title / Name") },
                        placeholder = { Text("e.g. Amber Rosary Specimen") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                // Storage Optimization & Quality Preset Section
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Photo Size & Quality Preset", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ImageOptimizationQuality.entries.forEach { quality ->
                                    val isSelected = selectedQuality == quality
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedQuality = quality },
                                        label = { Text(quality.label, fontSize = 11.sp) },
                                        leadingIcon = if (isSelected) {
                                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }

                // Multiple Photos Attachment Section
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Photos (${imageList.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }

                                FilledTonalButton(
                                    onClick = {
                                        multiplePhotoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    enabled = !isOptimizingMedia
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("+ Add Photos", fontSize = 11.sp)
                                }
                            }

                            if (isOptimizingMedia) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }

                            if (imageList.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    itemsIndexed(imageList) { idx, path ->
                                        val f = File(path)
                                        Box(
                                            modifier = Modifier
                                                .size(72.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.Black)
                                        ) {
                                            AsyncImage(
                                                model = if (f.exists()) f else Uri.parse(path),
                                                contentDescription = "Photo",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )

                                            // Size badge
                                            if (f.exists()) {
                                                Surface(
                                                    color = Color.Black.copy(alpha = 0.7f),
                                                    shape = RoundedCornerShape(bottomStart = 8.dp),
                                                    modifier = Modifier.align(Alignment.BottomStart)
                                                ) {
                                                    Text(
                                                        text = AttachmentRepository.formatFileSize(f.length()),
                                                        color = Color.White,
                                                        fontSize = 8.sp,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }

                                            // Remove button
                                            IconButton(
                                                onClick = {
                                                    imageList = imageList.filterIndexed { i, _ -> i != idx }
                                                },
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .align(Alignment.TopEnd)
                                                    .background(Color.Black.copy(alpha = 0.75f), CircleShape)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Multiple Videos Attachment Section
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Videos (${videoList.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }

                                FilledTonalButton(
                                    onClick = {
                                        videoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    enabled = !isOptimizingMedia
                                ) {
                                    Icon(Icons.Default.VideoCall, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("+ Add Video", fontSize = 11.sp)
                                }
                            }

                            if (videoList.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    videoList.forEachIndexed { idx, path ->
                                        val f = File(path)
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column {
                                                        Text("Video ${idx + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                        Text(
                                                            text = if (f.exists()) AttachmentRepository.formatFileSize(f.length()) else "Saved Video",
                                                            fontSize = 10.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        videoList = videoList.filterIndexed { i, _ -> i != idx }
                                                    },
                                                    modifier = Modifier.size(26.dp)
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Notes & Description
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes & Details") },
                        placeholder = { Text("e.g. Dimensions, origin, valuation, batch #") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }

                // Specifications / Key-Value Attributes
                item {
                    Text("Custom Specifications:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))

                    if (customFields.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            customFields.forEach { (k, v) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("$k: $v", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    IconButton(
                                        onClick = { customFields = customFields - k },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newKey,
                            onValueChange = { newKey = it },
                            placeholder = { Text("Property (e.g. Origin)", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newVal,
                            onValueChange = { newVal = it },
                            placeholder = { Text("Value (e.g. Baltic)", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        IconButton(
                            onClick = {
                                if (newKey.isNotBlank() && newVal.isNotBlank()) {
                                    customFields = customFields + (newKey.trim() to newVal.trim())
                                    newKey = ""
                                    newVal = ""
                                }
                            },
                            enabled = newKey.isNotBlank() && newVal.isNotBlank()
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Add Property")
                        }
                    }
                }

                // Display Visibility Options
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Badge on AR Overlay", style = MaterialTheme.typography.bodySmall)
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
                            images = imageList,
                            videos = videoList,
                            customFields = customFields,
                            showInOverlay = showInOverlay,
                            showInTable = showInTable
                        )
                        attachmentRepository.saveAttachment(attachment)
                        onSaved(attachment)
                        onDismiss()
                    }
                },
                enabled = qrContent.isNotBlank() && !isSaving && !isOptimizingMedia
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
 * Includes Export and Import backup options.
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
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    var showExportDialog by remember { mutableStateOf(false) }
    var selectedExportIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exportWithMedia by remember { mutableStateOf(true) }
    var pendingExportJson by remember { mutableStateOf("") }
    var pendingExportFileName by remember { mutableStateOf("qr_attachments.json") }

    var pendingImportPreview by remember { mutableStateOf<ImportPreviewData?>(null) }
    var selectedImportIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importWithMedia by remember { mutableStateOf(true) }

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(pendingExportJson.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Exported attachments file successfully!", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val sb = StringBuilder()
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
                        var l = r.readLine()
                        while (l != null) {
                            sb.append(l).append("\n")
                            l = r.readLine()
                        }
                    }
                }
                val preview = BackupTransferManager.parseImportPreview(sb.toString())
                if (preview.attachments.isNotEmpty()) {
                    pendingImportPreview = preview
                    selectedImportIds = preview.attachments.map { it.id }.toSet()
                    importWithMedia = preview.hasMedia
                    showImportDialog = false
                } else {
                    Toast.makeText(context, "No attachments found in file", Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val totalMediaBytes = remember(attachments) {
        attachmentRepository.getTotalMediaSizeBytes()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Attachment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Linked QR Data (${attachments.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Storage: ${AttachmentRepository.formatFileSize(totalMediaBytes)} used",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

            Spacer(modifier = Modifier.height(10.dp))

            // Export & Import Toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        selectedExportIds = attachments.map { it.id }.toSet()
                        showExportDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    enabled = attachments.isNotEmpty()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export Data", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0).text?.toString() ?: ""
                            if (text.startsWith("[") || text.startsWith("{")) {
                                importText = text
                            }
                        }
                        showImportDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import Data", fontSize = 11.sp)
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
                    items(attachments, key = { it.id }) { item ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onViewDetail(item) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val firstImg = item.allImages.firstOrNull()
                                    if (firstImg != null) {
                                        val f = File(firstImg)
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.Black)
                                        ) {
                                            AsyncImage(
                                                model = if (f.exists()) f else Uri.parse(firstImg),
                                                contentDescription = "Thumb",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            if (item.allImages.size > 1) {
                                                Surface(
                                                    color = Color.Black.copy(alpha = 0.7f),
                                                    shape = RoundedCornerShape(topStart = 6.dp),
                                                    modifier = Modifier.align(Alignment.BottomEnd)
                                                ) {
                                                    Text(
                                                        text = "+${item.allImages.size - 1}",
                                                        color = Color.White,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 3.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.QrCode,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                    }

                                    Column {
                                        Text(
                                            text = item.title.ifBlank { "QR: ${item.qrContent}" },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Pattern: '${item.qrContent}'",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (item.allImages.isNotEmpty()) {
                                                Text(
                                                    text = "📷 ${item.allImages.size}",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (item.allVideos.isNotEmpty()) {
                                                Text(
                                                    text = "🎥 ${item.allVideos.size}",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            if (item.customFields.isNotEmpty()) {
                                                Text(
                                                    text = "• ${item.customFields.size} specs",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { attachmentRepository.deleteAttachment(item.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
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
            }
        }
    }

    // 1. Export Attachments Dialog (Choice of individual attachments, with or without media)
    if (showExportDialog) {
        val targetAttachments = attachments.filter { it.id in selectedExportIds }
        val mediaCount = targetAttachments.sumOf { it.allImages.size + it.allVideos.size }

        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export QR Attachments")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Customize what you want to export. Select individual attachments and choose whether to include media files:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 1. Item Selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SELECT ATTACHMENTS (${targetAttachments.size}/${attachments.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { selectedExportIds = attachments.map { it.id }.toSet() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("All", fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = { selectedExportIds = emptySet() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("None", fontSize = 11.sp)
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            attachments.forEach { att ->
                                val isChecked = att.id in selectedExportIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val next = selectedExportIds.toMutableSet()
                                            if (isChecked) next.remove(att.id) else next.add(att.id)
                                            selectedExportIds = next
                                        }
                                        .padding(vertical = 4.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            val next = selectedExportIds.toMutableSet()
                                            if (checked) next.add(att.id) else next.remove(att.id)
                                            selectedExportIds = next
                                        },
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(att.title.ifBlank { "QR: ${att.qrContent}" }, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(
                                            text = "Pattern: ${att.qrContent} • ${att.allImages.size} photo(s), ${att.allVideos.size} video(s)",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Media Choice (With Media vs Without Media)
                    Text(
                        text = "MEDIA INCLUSION CHOICE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (exportWithMedia) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (exportWithMedia) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { exportWithMedia = true }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = exportWithMedia,
                                        onClick = { exportWithMedia = true },
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("With Media", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Includes $mediaCount photo(s)/video(s). Portable bundle.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (!exportWithMedia) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (!exportWithMedia) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { exportWithMedia = false }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = !exportWithMedia,
                                        onClick = { exportWithMedia = false },
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Without Media", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Titles, notes & specs only. Ultra-lightweight JSON.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // 3. Actions
                    fun generatePayload(): String {
                        return BackupTransferManager.createExportJson(
                            profiles = emptyList(),
                            attachments = targetAttachments,
                            includeMedia = exportWithMedia
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            val json = generatePayload()
                            pendingExportJson = json
                            pendingExportFileName = if (exportWithMedia) "qr_attachments_with_media.json" else "qr_attachments_no_media.json"
                            exportFileLauncher.launch(pendingExportFileName)
                            showExportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        enabled = targetAttachments.isNotEmpty()
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save as JSON File", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            val json = generatePayload()
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "QR Attachments Export")
                                putExtra(Intent.EXTRA_TEXT, json)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Attachments via"))
                            showExportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        enabled = targetAttachments.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share via Apps", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val json = generatePayload()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("QR Attachments", json))
                            Toast.makeText(context, "Copied attachments JSON to clipboard!", Toast.LENGTH_SHORT).show()
                            showExportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        enabled = targetAttachments.isNotEmpty()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy JSON to Clipboard", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // 2. Input Dialog for Importing Attachments
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import QR Attachments") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Choose how to load your attachment backup:",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = {
                            importFileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open JSON File from Storage")
                    }

                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("Or Paste JSON Backup Content") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val preview = BackupTransferManager.parseImportPreview(importText.trim())
                        if (preview.attachments.isNotEmpty()) {
                            showImportDialog = false
                            pendingImportPreview = preview
                            selectedImportIds = preview.attachments.map { it.id }.toSet()
                            importWithMedia = preview.hasMedia
                            importText = ""
                        } else {
                            Toast.makeText(context, "Invalid JSON data or no attachments found.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = importText.isNotBlank()
                ) {
                    Text("Review & Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 3. Interactive Import Review Dialog: Choose individual attachments and with/without media
    val preview = pendingImportPreview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = { pendingImportPreview = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import Attachments Review")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Found ${preview.attachments.size} Attachment(s)${if (preview.hasMedia) " with embedded media (~${BackupTransferManager.formatBytes(preview.totalMediaBytesEstimate)})" else ""}. Select what to import:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 1. Checklist
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SELECT ATTACHMENTS (${selectedImportIds.size}/${preview.attachments.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { selectedImportIds = preview.attachments.map { it.id }.toSet() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("All", fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = { selectedImportIds = emptySet() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("None", fontSize = 11.sp)
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            preview.attachments.forEach { att ->
                                val isChecked = att.id in selectedImportIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val next = selectedImportIds.toMutableSet()
                                            if (isChecked) next.remove(att.id) else next.add(att.id)
                                            selectedImportIds = next
                                        }
                                        .padding(vertical = 4.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            val next = selectedImportIds.toMutableSet()
                                            if (checked) next.add(att.id) else next.remove(att.id)
                                            selectedImportIds = next
                                        },
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(att.title.ifBlank { "QR: ${att.qrContent}" }, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(
                                            text = "Pattern: ${att.qrContent} • ${att.allImages.size} photo(s)",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Media Choice
                    if (preview.hasMedia) {
                        Text(
                            text = "MEDIA IMPORT CHOICE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (importWithMedia) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (importWithMedia) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { importWithMedia = true }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = importWithMedia,
                                            onClick = { importWithMedia = true },
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("With Media", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Restore photos & videos to device storage.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (!importWithMedia) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (!importWithMedia) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { importWithMedia = false }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = !importWithMedia,
                                            onClick = { importWithMedia = false },
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Without Media", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Specs only. Skip saving media files.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val result = BackupTransferManager.executeImport(
                            preview = preview,
                            selectedProfileIds = emptySet(),
                            selectedAttachmentIds = selectedImportIds,
                            importMedia = importWithMedia,
                            overwriteExisting = true,
                            profileRepository = com.example.data.ProfileRepository(context),
                            attachmentRepository = attachmentRepository
                        )
                        Toast.makeText(
                            context,
                            "Successfully imported ${result.attachmentsCount} attachment(s)${if (result.mediaFilesCount > 0) " & ${result.mediaFilesCount} media file(s)" else ""}!",
                            Toast.LENGTH_LONG
                        ).show()
                        pendingImportPreview = null
                    },
                    enabled = selectedImportIds.isNotEmpty()
                ) {
                    Text("Import Selected (${selectedImportIds.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportPreview = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
