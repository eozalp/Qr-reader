package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.QrCodeAnalyzer
import com.example.camera.QrTracker
import com.example.camera.RawDetectedCode
import com.example.data.AttachmentRepository
import com.example.data.ProfileRepository
import com.example.model.QrAttachment
import com.example.model.QrProfile
import com.example.model.TrackedQr
import com.example.model.BarcodeCatalog
import com.example.model.BarcodeScanPreset
import com.example.ui.attachments.AttachmentDetailDialog
import com.example.ui.attachments.EditAttachmentDialog
import com.example.ui.attachments.LinkedAttachmentsSheet
import com.example.ui.components.BarcodeFormatOptionsSheet
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    profileRepository: ProfileRepository,
    attachmentRepository: AttachmentRepository,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val activeProfile by profileRepository.activeProfile.collectAsState()
    val allProfiles by profileRepository.profiles.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    // Camera control states
    var isFrontCamera by remember { mutableStateOf(false) }
    var isTorchEnabled by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var zoomLevel by remember { mutableFloatStateOf(0.0f) }
    var showProfileDropdown by remember { mutableStateOf(false) }

    // Screen dimensions
    var screenWidthPx by remember { mutableFloatStateOf(1080f) }
    var screenHeightPx by remember { mutableFloatStateOf(1920f) }

    // Tracking state
    val qrTracker = remember { QrTracker() }
    var trackedQrs by remember { mutableStateOf<List<TrackedQr>>(emptyList()) }
    var simulatedCodes by remember { mutableStateOf<List<RawDetectedCode>>(emptyList()) }
    var isSimulatingMovement by remember { mutableStateOf(false) }
    var showSimulationSheet by remember { mutableStateOf(false) }

    // Linked Attachment UI state
    var showAttachmentsSheet by remember { mutableStateOf(false) }
    var showBarcodeOptionsSheet by remember { mutableStateOf(false) }
    var editingAttachment by remember { mutableStateOf<QrAttachment?>(null) }
    var viewingAttachment by remember { mutableStateOf<QrAttachment?>(null) }
    var initialQrForEdit by remember { mutableStateOf("") }
    var showNewAttachmentDialog by remember { mutableStateOf(false) }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }

    val coroutineScope = rememberCoroutineScope()
    var liveOverlayScale by remember(activeProfile.overlay.scale) {
        mutableFloatStateOf(activeProfile.overlay.scale)
    }
    var showScaleToast by remember { mutableStateOf(false) }
    var saveScaleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Auto-switch notification & embedded profile import states
    var autoSwitchedProfileName by remember { mutableStateOf<String?>(null) }
    var autoSwitchedSignal by remember { mutableStateOf("") }
    var detectedEmbeddedProfile by remember { mutableStateOf<QrProfile?>(null) }

    val analyzer = remember {
        QrCodeAnalyzer { rawList ->
            if (!isPaused) {
                val combined = rawList + simulatedCodes

                for (code in combined) {
                    // Check for embedded profile QR code
                    val embedded = com.example.engine.QrParser.detectEmbeddedProfile(code.rawValue)
                    if (embedded != null && detectedEmbeddedProfile?.id != embedded.id) {
                        detectedEmbeddedProfile = embedded
                    }

                    // Check for trigger signal auto-switch
                    val matched = com.example.engine.QrParser.findMatchingProfileBySignal(code.rawValue, allProfiles)
                    if (matched != null && matched.id != activeProfile.id) {
                        autoSwitchedProfileName = matched.name
                        autoSwitchedSignal = matched.triggerSignal
                        profileRepository.selectProfile(matched.id)
                    }
                }

                val updated = qrTracker.update(
                    detectedScreenBoxes = combined,
                    profile = activeProfile,
                    attachmentFinder = { attachmentRepository.findAttachmentForQr(it) }
                )
                trackedQrs = updated
            }
        }
    }

    LaunchedEffect(autoSwitchedProfileName) {
        if (autoSwitchedProfileName != null) {
            delay(2800)
            autoSwitchedProfileName = null
        }
    }

    // Motion animation ticker for simulated codes
    var motionAngle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isSimulatingMovement, simulatedCodes) {
        if (isSimulatingMovement && simulatedCodes.isNotEmpty()) {
            while (true) {
                kotlinx.coroutines.delay(30)
                motionAngle += 0.05f
                val dx = kotlin.math.cos(motionAngle) * 70f
                val dy = kotlin.math.sin(motionAngle) * 50f
                val moved = simulatedCodes.map { raw ->
                    val orig = raw.uprightBoundingBox
                    val shifted = RectF(orig.left + dx, orig.top + dy, orig.right + dx, orig.bottom + dy)
                    raw.copy(uprightBoundingBox = shifted)
                }
                trackedQrs = qrTracker.update(
                    detectedScreenBoxes = moved,
                    profile = activeProfile,
                    attachmentFinder = { attachmentRepository.findAttachmentForQr(it) }
                )
            }
        }
    }

    // Keep analyzer config in sync
    LaunchedEffect(isPaused) {
        analyzer.isPaused = isPaused
    }
    LaunchedEffect(isFrontCamera) {
        analyzer.isFrontCamera = isFrontCamera
    }
    LaunchedEffect(screenWidthPx, screenHeightPx) {
        analyzer.viewWidth = screenWidthPx
        analyzer.viewHeight = screenHeightPx
    }
    LaunchedEffect(activeProfile.tracking.scanPreset, activeProfile.tracking.enabledFormats, activeProfile.tracking.enableInvertedDetection) {
        analyzer.updateBarcodeFormats(
            preset = activeProfile.tracking.scanPreset,
            customFormats = activeProfile.tracking.enabledFormats,
            enableInverted = activeProfile.tracking.enableInvertedDetection
        )
    }

    // Periodic ticker to smoothly fade out lost QRs
    LaunchedEffect(activeProfile) {
        while (true) {
            kotlinx.coroutines.delay(50)
            if (trackedQrs.isNotEmpty()) {
                val updated = qrTracker.update(
                    detectedScreenBoxes = emptyList<RawDetectedCode>(),
                    profile = activeProfile,
                    attachmentFinder = { attachmentRepository.findAttachmentForQr(it) }
                )
                trackedQrs = updated
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                screenWidthPx = coordinates.size.width.toFloat()
                screenHeightPx = coordinates.size.height.toFloat()
            }
    ) {
        if (!hasCameraPermission) {
            // Permission request screen
            CameraPermissionPlaceholder(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            )
        } else {
            // Live Camera Preview
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val cameraExecutor = Executors.newSingleThreadExecutor()
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                            .also {
                                it.setAnalyzer(cameraExecutor, analyzer)
                            }

                        val cameraSelector = if (isFrontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }

                        try {
                            cameraProvider.unbindAll()
                            val cam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                            cameraControl = cam.cameraControl
                            cameraInfo = cam.cameraInfo
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // AR Overlay Layer with Multitouch Zoom to resize the overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activeProfile.id) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1.0f) {
                                val updated = (liveOverlayScale * zoom).coerceIn(0.5f, 3.0f)
                                liveOverlayScale = updated
                                showScaleToast = true
                                saveScaleJob?.cancel()
                                saveScaleJob = coroutineScope.launch {
                                    // Keep last settings: persist the scale to profile repository
                                    profileRepository.saveProfile(
                                        activeProfile.copy(
                                            overlay = activeProfile.overlay.copy(scale = updated)
                                        )
                                    )
                                    kotlinx.coroutines.delay(1200)
                                    showScaleToast = false
                                }
                            }
                        }
                    }
            ) {
                QrOverlaysCanvas(
                    trackedItems = trackedQrs,
                    overlayConfig = activeProfile.overlay.copy(scale = liveOverlayScale),
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    onAttachmentClick = { att -> viewingAttachment = att },
                    modifier = Modifier.fillMaxSize()
                )

                // Brief floating badge indicator when user pinches to resize overlay
                if (showScaleToast) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.82f))
                            .border(1.dp, Color(0xFF00F5D4), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Overlay Size: ${(liveOverlayScale * 100).toInt()}%",
                            color = Color(0xFF00F5D4),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Top HUD Controls & Profile Bar
            TopHudBar(
                activeProfile = activeProfile,
                allProfiles = allProfiles,
                onSelectProfile = { profileRepository.selectProfile(it) },
                activeCount = trackedQrs.size,
                isTorchEnabled = isTorchEnabled,
                onToggleTorch = {
                    isTorchEnabled = !isTorchEnabled
                    cameraControl?.enableTorch(isTorchEnabled)
                },
                isPaused = isPaused,
                onTogglePause = { isPaused = !isPaused },
                isFrontCamera = isFrontCamera,
                onToggleCamera = { isFrontCamera = !isFrontCamera },
                onOpenSimulation = { showSimulationSheet = true },
                onOpenBarcodeOptions = { showBarcodeOptionsSheet = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Auto-switched Profile Notification Banner
            AnimatedVisibility(
                visible = autoSwitchedProfileName != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 70.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Auto-Switched to '$autoSwitchedProfileName'${if (autoSwitchedSignal.isNotBlank()) " ($autoSwitchedSignal)" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Embedded Profile Detected Bottom Card
            AnimatedVisibility(
                visible = detectedEmbeddedProfile != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 90.dp, start = 16.dp, end = 16.dp)
            ) {
                detectedEmbeddedProfile?.let { detected ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.QrCode2,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Embedded Profile Scanned",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                IconButton(
                                    onClick = { detectedEmbeddedProfile = null },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Profile: ${detected.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Formula: ${detected.formula.expression} · Delimiter: '${detected.parsingRules.effectiveDelimiter}' · ${detected.parsingRules.variables.size} variables",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        profileRepository.importSingleProfile(detected, activate = true)
                                        android.widget.Toast.makeText(context, "Profile '${detected.name}' imported & activated!", android.widget.Toast.LENGTH_SHORT).show()
                                        detectedEmbeddedProfile = null
                                    },
                                    modifier = Modifier.weight(1f).testTag("import_embedded_profile_btn")
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Import & Activate")
                                }

                                OutlinedButton(
                                    onClick = { detectedEmbeddedProfile = null },
                                    modifier = Modifier.weight(0.6f)
                                ) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }

            // Zoom slider overlay (subtle at right side)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                ) {
                    IconButton(
                        onClick = {
                            zoomLevel = (zoomLevel + 0.1f).coerceAtMost(1.0f)
                            cameraControl?.setLinearZoom(zoomLevel)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", tint = Color.White)
                    }

                    Text(
                        text = "${(1.0f + zoomLevel * 3f).toInt()}x",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(
                        onClick = {
                            zoomLevel = (zoomLevel - 0.1f).coerceAtLeast(0.0f)
                            cameraControl?.setLinearZoom(zoomLevel)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", tint = Color.White)
                    }
                }
            }

            // Quick Attach pill when a QR is currently in camera view
            val visibleQr = trackedQrs.firstOrNull()
            if (visibleQr != null) {
                Surface(
                    color = Color(0xEE090D16),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (visibleQr.attachment != null) Color(0xFF00F5D4) else Color(0xFFFFB703)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 24.dp, start = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            if (visibleQr.attachment != null) {
                                viewingAttachment = visibleQr.attachment
                            } else {
                                initialQrForEdit = visibleQr.rawContent
                                showNewAttachmentDialog = true
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (visibleQr.attachment != null) Icons.Default.CheckCircle else Icons.Default.AttachFile,
                            contentDescription = null,
                            tint = if (visibleQr.attachment != null) Color(0xFF00F5D4) else Color(0xFFFFB703),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (visibleQr.attachment != null) {
                                "📎 ${visibleQr.attachment?.title?.ifBlank { visibleQr.rawContent }?.take(16)}"
                            } else {
                                "+ Attach to '${visibleQr.rawContent.take(10)}...'"
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Bottom action buttons: Linked Data, Barcode Formats, Simulation Wand & Settings FAB
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 24.dp, end = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { showBarcodeOptionsSheet = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xDD090D16),
                        contentColor = Color(0xFF60A5FA)
                    ),
                    modifier = Modifier.testTag("barcode_reading_options_btn")
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when (activeProfile.tracking.scanPreset) {
                            BarcodeScanPreset.ALL_FORMATS -> "All Barcodes"
                            BarcodeScanPreset.TWO_D_ONLY -> "2D / 3D"
                            BarcodeScanPreset.ONE_D_ONLY -> "1D Barcodes"
                            BarcodeScanPreset.CUSTOM -> "Custom"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                FilledTonalButton(
                    onClick = { showAttachmentsSheet = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xDD090D16),
                        contentColor = Color(0xFFFFB703)
                    ),
                    modifier = Modifier.testTag("manage_attachments_btn")
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Linked Data", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = { showSimulationSheet = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xDD090D16),
                        contentColor = Color(0xFF00F5D4)
                    ),
                    modifier = Modifier.testTag("test_ar_simulation_btn")
                ) {
                    Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("AR Codes", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                FloatingActionButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("open_settings_button"),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Linked Data & Media Management Sheet
            if (showAttachmentsSheet) {
                LinkedAttachmentsSheet(
                    attachmentRepository = attachmentRepository,
                    onDismiss = { showAttachmentsSheet = false },
                    onAddNew = {
                        showAttachmentsSheet = false
                        initialQrForEdit = trackedQrs.firstOrNull()?.rawContent ?: ""
                        showNewAttachmentDialog = true
                    },
                    onViewDetail = { att ->
                        showAttachmentsSheet = false
                        viewingAttachment = att
                    }
                )
            }

            // Barcode Format Options Sheet
            if (showBarcodeOptionsSheet) {
                BarcodeFormatOptionsSheet(
                    currentSettings = activeProfile.tracking,
                    onSettingsChanged = { updatedTracking ->
                        profileRepository.saveProfile(activeProfile.copy(tracking = updatedTracking))
                    },
                    onDismiss = { showBarcodeOptionsSheet = false }
                )
            }

            // Edit / Create Attachment Dialog
            if (showNewAttachmentDialog || editingAttachment != null) {
                EditAttachmentDialog(
                    initialAttachment = editingAttachment,
                    defaultQrCode = initialQrForEdit,
                    attachmentRepository = attachmentRepository,
                    onDismiss = {
                        showNewAttachmentDialog = false
                        editingAttachment = null
                        initialQrForEdit = ""
                    },
                    onSaved = { _ ->
                        showNewAttachmentDialog = false
                        editingAttachment = null
                        initialQrForEdit = ""
                        trackedQrs = qrTracker.update(
                            detectedScreenBoxes = emptyList<RawDetectedCode>(),
                            profile = activeProfile,
                            attachmentFinder = { attachmentRepository.findAttachmentForQr(it) }
                        )
                    }
                )
            }

            // Attachment Detail Lightbox / Viewer
            if (viewingAttachment != null) {
                AttachmentDetailDialog(
                    attachment = viewingAttachment!!,
                    onDismiss = { viewingAttachment = null },
                    onEdit = { att ->
                        viewingAttachment = null
                        editingAttachment = att
                    }
                )
            }

            // Simulation Bottom Sheet
            if (showSimulationSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showSimulationSheet = false },
                    containerColor = Color(0xFF0F172A)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Emulator AR Code Simulation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Since emulator cameras display a virtual mosaic pattern, inject test QR coordinates directly onto the live feed to test tracking, formulas, and CSS overlay styling:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val centerX = screenWidthPx / 2f
                                    val centerY = screenHeightPx / 2f
                                    simulatedCodes = listOf(
                                        RawDetectedCode("10-20-30-40", RectF(centerX - 100f, centerY - 100f, centerX + 100f, centerY + 100f), Barcode.FORMAT_QR_CODE, "QR CODE")
                                    )
                                    showSimulationSheet = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("QR: 10-20-30-40", fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    val w = screenWidthPx; val h = screenHeightPx
                                    simulatedCodes = listOf(
                                        RawDetectedCode("8690504018089", RectF(w * 0.15f, h * 0.35f, w * 0.85f, h * 0.48f), Barcode.FORMAT_EAN_13, "EAN-13")
                                    )
                                    showSimulationSheet = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("1D: EAN-13", fontSize = 11.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val w = screenWidthPx; val h = screenHeightPx
                                    simulatedCodes = listOf(
                                        RawDetectedCode("SHIP-984210", RectF(w * 0.15f, h * 0.30f, w * 0.85f, h * 0.44f), Barcode.FORMAT_CODE_128, "CODE 128")
                                    )
                                    showSimulationSheet = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("1D: Code 128", fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    val w = screenWidthPx; val h = screenHeightPx
                                    simulatedCodes = listOf(
                                        RawDetectedCode("PDF417-ID-9941", RectF(w * 0.15f, h * 0.50f, w * 0.85f, h * 0.65f), Barcode.FORMAT_PDF417, "PDF417")
                                    )
                                    showSimulationSheet = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("3D: PDF417 Stacked", fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = {
                                val w = screenWidthPx; val h = screenHeightPx
                                simulatedCodes = listOf(
                                    RawDetectedCode("10-20-30-40", RectF(w * 0.12f, h * 0.22f, w * 0.12f + 160f, h * 0.22f + 160f), Barcode.FORMAT_QR_CODE, "QR CODE"),
                                    RawDetectedCode("8690504018089", RectF(w * 0.50f, h * 0.38f, w * 0.90f, h * 0.48f), Barcode.FORMAT_EAN_13, "EAN-13"),
                                    RawDetectedCode("DM-PART-5542", RectF(w * 0.20f, h * 0.60f, w * 0.20f + 150f, h * 0.60f + 150f), Barcode.FORMAT_DATA_MATRIX, "DATA MATRIX")
                                )
                                showSimulationSheet = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            modifier = Modifier.fillMaxWidth().testTag("simulate_multi_format_btn")
                        ) {
                            Icon(Icons.Default.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF00F5D4))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Mixed Multi-Barcode (QR + EAN-13 + Data Matrix)", fontSize = 11.sp, color = Color.White)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Simulate Spatial Movement (Drift)", color = Color.White)
                            Switch(
                                checked = isSimulatingMovement,
                                onCheckedChange = { isSimulatingMovement = it }
                            )
                        }

                        if (simulatedCodes.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    simulatedCodes = emptyList()
                                    trackedQrs = emptyList()
                                    showSimulationSheet = false
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clear Simulated Codes")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TopHudBar(
    activeProfile: QrProfile,
    allProfiles: List<QrProfile>,
    onSelectProfile: (String) -> Unit,
    activeCount: Int,
    isTorchEnabled: Boolean,
    onToggleTorch: () -> Unit,
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    isFrontCamera: Boolean,
    onToggleCamera: () -> Unit,
    onOpenSimulation: () -> Unit,
    onOpenBarcodeOptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedDropdown by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xCC090D16))
            .border(1.dp, Color(0x3300F5D4), RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Active Profile Selector
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { expandedDropdown = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isPaused) Color(0xFFF59E0B) else Color(0xFF10B981))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = activeProfile.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (activeCount > 0) "$activeCount detected" else if (isPaused) "Paused" else "Scanning...",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select Profile",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = expandedDropdown,
                onDismissRequest = { expandedDropdown = false }
            ) {
                allProfiles.forEach { profile ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = profile.name,
                                    fontWeight = if (profile.id == activeProfile.id) FontWeight.Bold else FontWeight.Normal
                                )
                                if (profile.description.isNotBlank()) {
                                    Text(
                                        text = profile.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        trailingIcon = {
                            if (profile.id == activeProfile.id) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        onClick = {
                            onSelectProfile(profile.id)
                            expandedDropdown = false
                        }
                    )
                }
            }
        }

        // Action Buttons Row
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onToggleTorch,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = if (isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Torch Toggle",
                    tint = if (isTorchEnabled) Color(0xFFFACC15) else Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onTogglePause,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = "Pause/Resume Scanning",
                    tint = if (isPaused) Color(0xFFF59E0B) else Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onOpenBarcodeOptions,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = "Barcode Reading Options",
                    tint = Color(0xFF00F5D4),
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onOpenSimulation,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Science,
                    contentDescription = "Test Simulated Codes",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onToggleCamera,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FlipCameraAndroid,
                    contentDescription = "Switch Camera",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun CameraPermissionPlaceholder(
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(Color(0x2200F5D4))
                .border(2.dp, Color(0xFF00F5D4), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = Color(0xFF00F5D4),
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Camera Access Required",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "QR Live Calculator continuously analyzes QR codes in the physical world to compute your formulas and project AR results directly over each code.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF94A3B8),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00F5D4),
                contentColor = Color(0xFF090D16)
            ),
            modifier = Modifier.testTag("grant_camera_permission_button")
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Grant Permission", fontWeight = FontWeight.Bold)
        }
    }
}
