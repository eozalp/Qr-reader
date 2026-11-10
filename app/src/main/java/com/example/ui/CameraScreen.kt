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
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import com.example.ui.theme.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.alpha
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
import com.example.model.CalculationStatus
import com.example.model.ScanHistoryItem
import com.example.ui.attachments.AttachmentDetailDialog
import com.example.ui.attachments.EditAttachmentDialog
import com.example.ui.attachments.LinkedAttachmentsSheet
import com.example.ui.components.BarcodeFormatOptionsSheet
import com.example.ui.components.ScanHistorySheet
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
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

    val isRunningOnEmulator = remember {
        android.os.Build.FINGERPRINT.startsWith("generic") ||
                android.os.Build.FINGERPRINT.startsWith("unknown") ||
                android.os.Build.MODEL.contains("google_sdk") ||
                android.os.Build.MODEL.contains("Emulator") ||
                android.os.Build.MODEL.contains("Android SDK built for") ||
                android.os.Build.MANUFACTURER.contains("Genymotion") ||
                android.os.Build.HARDWARE.contains("goldfish") ||
                android.os.Build.HARDWARE.contains("ranchu") ||
                android.os.Build.PRODUCT.contains("sdk") ||
                android.os.Build.PRODUCT.contains("emulator") ||
                android.os.Build.BOARD.contains("goldfish") ||
                android.os.Build.DEVICE.contains("generic")
    }

    var isAttemptingPhysicalCamera by remember { mutableStateOf(!isRunningOnEmulator) }
    var cameraInitError by remember {
        mutableStateOf(if (isRunningOnEmulator) "Virtual AR Simulator Active (Emulator)" else null)
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED || isRunningOnEmulator
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
    var showZoomBadge by remember { mutableStateOf(false) }
    var hideZoomBadgeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
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
    var isHoldingRawQr by remember { mutableStateOf(false) }
    var isRawQrToggled by remember { mutableStateOf(false) }
    val rawQrInteractionSource = remember { MutableInteractionSource() }
    val isRawQrPressed by rawQrInteractionSource.collectIsPressedAsState()
    var rawPressStartTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isRawQrPressed) {
        if (isRawQrPressed) {
            rawPressStartTime = System.currentTimeMillis()
            isHoldingRawQr = true
        } else {
            isHoldingRawQr = false
        }
    }

    val isRawModeActive = isHoldingRawQr || isRawQrToggled

    // Haptics & Clipboard
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current

    // Battery Saver & Performance state
    var isBatterySaverEnabled by remember { mutableStateOf(false) }

    // Scan Session History state
    var scanHistory by remember { mutableStateOf<List<ScanHistoryItem>>(emptyList()) }
    var showHistorySheet by remember { mutableStateOf(false) }
    val recentLoggedQrs = remember { mutableMapOf<String, Long>() }

    // Pinned overlay state (Scales full width and pauses scanning)
    var pinnedQr by remember { mutableStateOf<TrackedQr?>(null) }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                if (providerFuture.isDone) {
                    providerFuture.get().unbindAll()
                }
            } catch (_: Exception) {}
            cameraExecutor.shutdown()
        }
    }

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
    LaunchedEffect(isBatterySaverEnabled) {
        analyzer.isBatterySaverEnabled = isBatterySaverEnabled
    }

    // Automatically record scanned codes into session history (deduplicated by 3.5s window)
    LaunchedEffect(trackedQrs) {
        val now = System.currentTimeMillis()
        val newEntries = mutableListOf<ScanHistoryItem>()
        for (item in trackedQrs) {
            val status = item.result.status
            val formattedResult = when (status) {
                is CalculationStatus.Success -> status.formattedText
                is CalculationStatus.ParseError -> "Parse Error"
                is CalculationStatus.EvaluationError -> "Evaluation Error"
            }
            val numericVal = (status as? CalculationStatus.Success)?.resultNumber
            val lastLogged = recentLoggedQrs[item.rawContent] ?: 0L
            if (now - lastLogged > 3500L) {
                recentLoggedQrs[item.rawContent] = now
                newEntries.add(
                    ScanHistoryItem(
                        rawText = item.rawContent,
                        profileName = activeProfile.name,
                        resultText = formattedResult,
                        numericValue = numericVal,
                        formatName = item.result.formatName
                    )
                )
            }
        }
        if (newEntries.isNotEmpty()) {
            scanHistory = scanHistory + newEntries
        }
    }

    // Real-time Multi-Code Sum / Aggregate calculation when 2 or more codes are in view
    val multiCodeItems = remember(trackedQrs) {
        trackedQrs.mapNotNull { item ->
            val success = item.result.status as? CalculationStatus.Success
            if (success != null) {
                Pair(item, success.resultNumber)
            } else null
        }
    }
    val multiCodeSum = remember(multiCodeItems) {
        if (multiCodeItems.size >= 2) multiCodeItems.sumOf { it.second } else null
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

    // Safe camera lifecycle binding: checks available lenses and prevents 10000ms reopen loop on emulator
    LaunchedEffect(hasCameraPermission, isFrontCamera, isAttemptingPhysicalCamera) {
        if (!hasCameraPermission || !isAttemptingPhysicalCamera) {
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                if (providerFuture.isDone) {
                    providerFuture.get().unbindAll()
                }
            } catch (_: Exception) {}
            if (!isAttemptingPhysicalCamera && cameraInitError == null) {
                cameraInitError = "Virtual AR Simulator Active (Emulator)"
            }
            return@LaunchedEffect
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                // Verify lens availability before binding to avoid Camera2CameraImpl 10000ms reopening loops
                val targetSelector = if (isFrontCamera) {
                    when {
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        cameraProvider.availableCameraInfos.isNotEmpty() -> cameraProvider.availableCameraInfos.first().cameraSelector
                        else -> null
                    }
                } else {
                    when {
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        cameraProvider.availableCameraInfos.isNotEmpty() -> cameraProvider.availableCameraInfos.first().cameraSelector
                        else -> null
                    }
                }

                if (targetSelector == null) {
                    isAttemptingPhysicalCamera = false
                    cameraInitError = "Virtual emulator environment detected. Camera hardware sensor is simulated."
                    return@addListener
                }

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

                val cam = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    targetSelector,
                    preview,
                    imageAnalysis
                )
                cameraControl = cam.cameraControl
                cameraInfo = cam.cameraInfo
                cameraInitError = null

                // Monitor camera state; if an error occurs, unbind cleanly to stop reopen loops immediately
                cam.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
                    if (state.error != null) {
                        try {
                            cameraProvider.unbindAll()
                        } catch (_: Exception) {}
                        isAttemptingPhysicalCamera = false
                        cameraInitError = "Camera unavailable in emulator (${state.error?.code}). Virtual AR Simulator is active."
                    }
                }
            } catch (e: Exception) {
                isAttemptingPhysicalCamera = false
                cameraInitError = "Virtual camera mode: ${e.message ?: "Virtual device"}"
            }
        }, ContextCompat.getMainExecutor(context))
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
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onContinueInEmulator = { hasCameraPermission = true },
                isEmulator = isRunningOnEmulator
            )
        } else {
            // Live Camera Preview or Emulator Virtual Feed
            if (cameraInitError != null && simulatedCodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF090D16)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0x2200F5D4)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = null,
                                tint = Color(0xFF00F5D4),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Virtual Simulator Mode Active",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = cameraInitError ?: "Camera sensor simulated on emulator.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { showSimulationSheet = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00F5D4),
                                    contentColor = Color(0xFF090D16)
                                ),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Inject Codes", fontWeight = FontWeight.Bold)
                            }
                            if (!isAttemptingPhysicalCamera) {
                                OutlinedButton(
                                    onClick = {
                                        cameraInitError = null
                                        isAttemptingPhysicalCamera = true
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF00F5D4)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00F5D4)),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Try Camera", fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            } else if (cameraInitError != null && simulatedCodes.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0F1D))
                )
            } else {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
            }

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
                    showRawQrData = isRawModeActive,
                    pinnedQr = pinnedQr,
                    onPinClick = { tracked ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        pinnedQr = tracked
                        isPaused = true
                    },
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

            // Top HUD Controls & Profile Bar (Hidden during raw QR mode)
            AnimatedVisibility(
                visible = !isRawModeActive,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
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
                    onTogglePause = {
                        isPaused = !isPaused
                        if (!isPaused) {
                            pinnedQr = null
                        }
                    },
                    isFrontCamera = isFrontCamera,
                    onToggleCamera = { isFrontCamera = !isFrontCamera },
                    isBatterySaverEnabled = isBatterySaverEnabled,
                    onToggleBatterySaver = {
                        isBatterySaverEnabled = !isBatterySaverEnabled
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        android.widget.Toast.makeText(
                            context,
                            if (isBatterySaverEnabled) "Battery Saver Mode ON (~5.5 FPS)" else "Battery Saver Mode OFF (Full Performance)",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    onOpenHistory = { showHistorySheet = true },
                    onOpenSimulation = { showSimulationSheet = true },
                    onOpenBarcodeOptions = { showBarcodeOptionsSheet = true },
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Real-Time Multi-Code Sum / Aggregate Pill (When 2+ codes are detected with valid calculations)
            AnimatedVisibility(
                visible = multiCodeSum != null && !isRawModeActive && pinnedQr == null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 70.dp)
            ) {
                Surface(
                    color = Color(0xF2070D18),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, NeonCyan),
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val formattedSum = String.format(java.util.Locale.US, "%.2f", multiCodeSum)
                            clipboardManager.setText(AnnotatedString(formattedSum))
                            android.widget.Toast.makeText(context, "Copied in-view sum: $formattedSum", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .testTag("multi_code_sum_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(NeonCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Functions,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Σ IN VIEW (${multiCodeItems.size} CODES):",
                            color = OnDarkSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = String.format(java.util.Locale.US, "%.2f", multiCodeSum),
                            color = NeonCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Total",
                            tint = NeonCyan.copy(alpha = 0.65f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            // Auto-switched Profile Notification Banner (Hidden during raw QR mode)
            AnimatedVisibility(
                visible = autoSwitchedProfileName != null && !isRawModeActive,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = if (multiCodeSum != null) 115.dp else 70.dp)
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

            // Embedded Profile Detected Bottom Card (Hidden during raw QR mode)
            AnimatedVisibility(
                visible = detectedEmbeddedProfile != null && !isRawModeActive,
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

            // Raw Mode active feedback when no barcode is in view
            AnimatedVisibility(
                visible = isRawModeActive && trackedQrs.isEmpty(),
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xF2070B14),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, NeonCyan),
                    shadowElevation = 14.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.DataObject,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "RAW QR MODE ACTIVE",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = NeonCyan
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Point camera at a barcode or use AR Simulator",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap or release Raw button to exit",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnDarkSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            // Pinned Full-Width Overlay Card (Scales full width, kept on screen, scanning paused)
            AnimatedVisibility(
                visible = pinnedQr != null && !isRawModeActive,
                enter = fadeIn() + scaleIn(initialScale = 0.88f),
                exit = fadeOut() + scaleOut(targetScale = 0.88f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                pinnedQr?.let { pinned ->
                    PinnedFullWidthOverlayCard(
                        tracked = pinned,
                        config = activeProfile.overlay,
                        onUnpin = {
                            pinnedQr = null
                            isPaused = false
                        },
                        onAttachmentClick = { att -> viewingAttachment = att }
                    )
                }
            }

            // Thin Vertical Line Zoom Control (Swipe vertically to zoom in and out)
            AnimatedVisibility(
                visible = !isRawModeActive && pinnedQr == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
            ) {
                val trackHeightDp = 180.dp
                val density = LocalDensity.current
                val trackHeightPx = with(density) { trackHeightDp.toPx() }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    // Floating Zoom Multiplier Badge (Appears while swiping/tapping)
                    AnimatedVisibility(
                        visible = showZoomBadge,
                        enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 2 }),
                        exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 2 })
                    ) {
                        Surface(
                            color = Color(0xF2070B14),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.85f)),
                            shadowElevation = 8.dp,
                            modifier = Modifier.padding(end = 2.dp)
                        ) {
                            Text(
                                text = String.format(java.util.Locale.US, "%.1fx", 1.0f + zoomLevel * 3.0f),
                                color = NeonCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Thin Vertical Swipe Line Container (48dp touch width for accessible swiping)
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(trackHeightDp)
                            .testTag("vertical_zoom_slider")
                            .pointerInput(trackHeightPx) {
                                detectTapGestures(
                                    onPress = { offset ->
                                        showZoomBadge = true
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val targetZoom = (1.0f - (offset.y / trackHeightPx)).coerceIn(0.0f, 1.0f)
                                        zoomLevel = targetZoom
                                        cameraControl?.setLinearZoom(zoomLevel)
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            hideZoomBadgeJob?.cancel()
                                            hideZoomBadgeJob = coroutineScope.launch {
                                                delay(1600)
                                                showZoomBadge = false
                                            }
                                        }
                                    }
                                )
                            }
                            .pointerInput(trackHeightPx) {
                                detectVerticalDragGestures(
                                    onDragStart = { offset ->
                                        showZoomBadge = true
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        hideZoomBadgeJob?.cancel()
                                        val targetZoom = (1.0f - (offset.y / trackHeightPx)).coerceIn(0.0f, 1.0f)
                                        zoomLevel = targetZoom
                                        cameraControl?.setLinearZoom(zoomLevel)
                                    },
                                    onDragEnd = {
                                        hideZoomBadgeJob?.cancel()
                                        hideZoomBadgeJob = coroutineScope.launch {
                                            delay(1600)
                                            showZoomBadge = false
                                        }
                                    },
                                    onDragCancel = {
                                        hideZoomBadgeJob?.cancel()
                                        hideZoomBadgeJob = coroutineScope.launch {
                                            delay(1600)
                                            showZoomBadge = false
                                        }
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        showZoomBadge = true
                                        hideZoomBadgeJob?.cancel()
                                        val deltaZoom = -dragAmount / trackHeightPx
                                        val prevZoom = zoomLevel
                                        val newZoom = (zoomLevel + deltaZoom).coerceIn(0.0f, 1.0f)
                                        if (kotlin.math.abs(newZoom - prevZoom) > 0.05f) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        zoomLevel = newZoom
                                        cameraControl?.setLinearZoom(zoomLevel)
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Background Track: Thin vertical line (3.5dp wide)
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .height(trackHeightDp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.22f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                        )

                        // Active Zoom Level Fill (Neon Cyan line from bottom up)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .width(3.5.dp)
                                .height((trackHeightDp * zoomLevel).coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(2.dp))
                                .background(NeonCyan)
                        )

                        // Sleek Slider Thumb Handle on the line
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.White,
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = -((trackHeightDp - 12.dp) * zoomLevel))
                                .size(width = 12.dp, height = 8.dp)
                        ) {}
                    }
                }
            }

            // Quick Attach pill when a QR is currently in camera view (Hidden during raw QR mode or when pinned)
            val visibleQr = trackedQrs.firstOrNull()
            if (visibleQr != null && !isRawModeActive && pinnedQr == null) {
                Surface(
                    color = GlassmorphicHudBg,
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (visibleQr.attachment != null) NeonCyan else AmberGold
                    ),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 76.dp)
                        .clip(RoundedCornerShape(24.dp))
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
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (visibleQr.attachment != null) Icons.Default.CheckCircle else Icons.Default.AttachFile,
                            contentDescription = null,
                            tint = if (visibleQr.attachment != null) NeonCyan else AmberGold,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (visibleQr.attachment != null) {
                                "📎 ${visibleQr.attachment?.title?.ifBlank { visibleQr.rawContent }?.take(18)}"
                            } else {
                                "+ Attach to '${visibleQr.rawContent.take(12)}...'"
                            },
                            color = OnDarkSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            // Expressive Bottom Floating Action Dock (Icon-only buttons for visual consistency)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Quick Controls: Barcode Formats, Linked Data, Raw QR, and AR Simulator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Barcode Formats
                    FilledTonalIconButton(
                        onClick = { showBarcodeOptionsSheet = true },
                        enabled = !isRawModeActive,
                        modifier = Modifier
                            .size(48.dp)
                            .alpha(if (isRawModeActive) 0f else 1f)
                            .testTag("barcode_reading_options_btn"),
                        shape = RoundedCornerShape(20.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = GlassmorphicDockBg,
                            contentColor = NeonCyan
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Barcode Formats (${when (activeProfile.tracking.scanPreset) {
                                BarcodeScanPreset.ALL_FORMATS -> "All Barcodes"
                                BarcodeScanPreset.TWO_D_ONLY -> "2D / 3D"
                                BarcodeScanPreset.ONE_D_ONLY -> "1D Barcodes"
                                BarcodeScanPreset.CUSTOM -> "Custom"
                            }})",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 2. Linked Data & Attachments
                    FilledTonalIconButton(
                        onClick = { showAttachmentsSheet = true },
                        enabled = !isRawModeActive,
                        modifier = Modifier
                            .size(48.dp)
                            .alpha(if (isRawModeActive) 0f else 1f)
                            .testTag("manage_attachments_btn"),
                        shape = RoundedCornerShape(20.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = GlassmorphicDockBg,
                            contentColor = AmberGold
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Linked Data & Media",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 3. Raw QR (Press and Hold to view raw unparsed data, or Tap to toggle)
                    FilledTonalIconButton(
                        onClick = {
                            val duration = System.currentTimeMillis() - rawPressStartTime
                            if (duration < 400L) {
                                isRawQrToggled = !isRawQrToggled
                            } else {
                                isRawQrToggled = false
                            }
                        },
                        interactionSource = rawQrInteractionSource,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("hold_raw_qr_button"),
                        shape = RoundedCornerShape(20.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isRawModeActive) NeonCyan else GlassmorphicDockBg,
                            contentColor = if (isRawModeActive) Color(0xFF00382E) else CyberLime
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.DataObject,
                            contentDescription = "Raw QR Data",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 4. AR Simulator
                    FilledTonalIconButton(
                        onClick = { showSimulationSheet = true },
                        enabled = !isRawModeActive,
                        modifier = Modifier
                            .size(48.dp)
                            .alpha(if (isRawModeActive) 0f else 1f)
                            .testTag("open_simulation_btn"),
                        shape = RoundedCornerShape(20.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = GlassmorphicDockBg,
                            contentColor = if (simulatedCodes.isNotEmpty()) NeonCyan else Color.White.copy(alpha = 0.85f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Science,
                            contentDescription = "Simulate AR Codes",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 5. Scan Session History (CSV Export & Log)
                    FilledTonalIconButton(
                        onClick = { showHistorySheet = true },
                        enabled = !isRawModeActive,
                        modifier = Modifier
                            .size(48.dp)
                            .alpha(if (isRawModeActive) 0f else 1f)
                            .testTag("scan_history_btn"),
                        shape = RoundedCornerShape(20.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = GlassmorphicDockBg,
                            contentColor = if (scanHistory.isNotEmpty()) NeonCyan else Color.White.copy(alpha = 0.85f)
                        )
                    ) {
                        BadgedBox(
                            badge = {
                                if (scanHistory.isNotEmpty()) {
                                    Badge(
                                        containerColor = NeonCyan,
                                        contentColor = Color(0xFF00382E)
                                    ) {
                                        Text(
                                            text = if (scanHistory.size > 99) "99+" else scanHistory.size.toString(),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Scan Session History",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Right: Expressive Squircle Settings FAB
                FilledIconButton(
                    onClick = onOpenSettings,
                    enabled = !isRawModeActive,
                    modifier = Modifier
                        .size(48.dp)
                        .alpha(if (isRawModeActive) 0f else 1f)
                        .testTag("open_settings_button"),
                    shape = RoundedCornerShape(20.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = NeonCyan,
                        contentColor = Color(0xFF00382E)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Scan Session History Sheet
            if (showHistorySheet) {
                ScanHistorySheet(
                    historyItems = scanHistory,
                    onClearHistory = {
                        scanHistory = emptyList()
                        recentLoggedQrs.clear()
                    },
                    onDismiss = { showHistorySheet = false }
                )
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

                        Button(
                            onClick = {
                                val w = screenWidthPx; val h = screenHeightPx
                                val demoProfile = com.example.model.QrProfile(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = "Beamed Gold Profile",
                                    description = "Compressed Profile Beamed via QR",
                                    formula = com.example.model.ArithmeticFormula("weight * purity", "weight * purity"),
                                    parsingRules = com.example.model.QrParsingRules(
                                        delimiter = "-",
                                        variables = listOf(
                                            com.example.model.VariableDefinition("weight", position = 0, prefix = "", postfix = "g"),
                                            com.example.model.VariableDefinition("purity", position = 1, prefix = "", postfix = "k")
                                        )
                                    )
                                )
                                val compressedCode = com.example.engine.QrParser.encodeProfileToCompressedQrString(demoProfile)
                                simulatedCodes = listOf(
                                    RawDetectedCode(compressedCode, RectF(w * 0.20f, h * 0.35f, w * 0.80f, h * 0.60f), Barcode.FORMAT_QR_CODE, "QR CODE")
                                )
                                showSimulationSheet = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00F5D4).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF00F5D4))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Simulate Compressed Profile Beam Scan", fontSize = 11.sp, color = Color.White)
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
    isBatterySaverEnabled: Boolean,
    onToggleBatterySaver: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSimulation: () -> Unit,
    onOpenBarcodeOptions: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedDropdown by remember { mutableStateOf(false) }
    var expandedMoreMenu by remember { mutableStateOf(false) }

    // Pulsing animation for active scanner indicator
    val infiniteTransition = rememberInfiniteTransition(label = "hud_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = GlassmorphicHudBg,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(NeonCyan.copy(alpha = 0.45f), ElectricIndigo.copy(alpha = 0.25f))
            )
        ),
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Active Profile Selector Pill
            Box(modifier = Modifier.weight(1f, fill = false)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { expandedDropdown = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPaused) AmberGold
                                else EmeraldGreen.copy(alpha = pulseAlpha)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.widthIn(max = 135.dp)) {
                        Text(
                            text = activeProfile.name,
                            color = OnDarkSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (activeCount > 0) "$activeCount detected" else if (isPaused) "Paused" else "Scanning...",
                            color = if (activeCount > 0) NeonCyan else OnDarkSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select Profile",
                        tint = OnDarkSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = expandedDropdown,
                    onDismissRequest = { expandedDropdown = false },
                    modifier = Modifier.background(DarkSurfaceVariant)
                ) {
                    allProfiles.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = profile.name,
                                        color = OnDarkSurface,
                                        fontWeight = if (profile.id == activeProfile.id) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (profile.description.isNotBlank()) {
                                        Text(
                                            text = profile.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnDarkSurfaceVariant
                                        )
                                    }
                                }
                            },
                            trailingIcon = {
                                if (profile.id == activeProfile.id) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = NeonCyan)
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

            // Direct Expressive Action Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                // Torch
                IconButton(
                    onClick = onToggleTorch,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Torch Toggle",
                        tint = if (isTorchEnabled) AmberGold else OnDarkSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Pause
                IconButton(
                    onClick = onTogglePause,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = "Pause/Resume Scanning",
                        tint = if (isPaused) AmberGold else OnDarkSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Battery Saver Mode Toggle
                IconButton(
                    onClick = onToggleBatterySaver,
                    modifier = Modifier.size(36.dp).testTag("battery_saver_toggle_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.BatterySaver,
                        contentDescription = if (isBatterySaverEnabled) "Battery Saver ON" else "Battery Saver OFF",
                        tint = if (isBatterySaverEnabled) CyberLime else OnDarkSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Flip Camera
                IconButton(
                    onClick = onToggleCamera,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FlipCameraAndroid,
                        contentDescription = "Switch Camera",
                        tint = OnDarkSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // More Options Menu
                Box {
                    IconButton(
                        onClick = { expandedMoreMenu = true },
                        modifier = Modifier.size(36.dp).testTag("top_hud_more_menu_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options",
                            tint = OnDarkSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = expandedMoreMenu,
                        onDismissRequest = { expandedMoreMenu = false },
                        modifier = Modifier.background(DarkSurfaceVariant)
                    ) {
                        // Scan History Item
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Scan Session History", color = OnDarkSurface, fontWeight = FontWeight.SemiBold)
                                        Text("View and export CSV log", fontSize = 11.sp, color = OnDarkSurfaceVariant)
                                    }
                                }
                            },
                            onClick = {
                                expandedMoreMenu = false
                                onOpenHistory()
                            }
                        )

                        // Battery Saver Item
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.BatterySaver,
                                        contentDescription = null,
                                        tint = if (isBatterySaverEnabled) CyberLime else OnDarkSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = if (isBatterySaverEnabled) "Battery Saver: ON" else "Battery Saver: OFF",
                                            color = OnDarkSurface,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text("Throttle scanner to ~5.5 FPS", fontSize = 11.sp, color = OnDarkSurfaceVariant)
                                    }
                                }
                            },
                            onClick = {
                                expandedMoreMenu = false
                                onToggleBatterySaver()
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Science,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("AR Code Simulator", color = OnDarkSurface, fontWeight = FontWeight.SemiBold)
                                        Text("Inject virtual QR/barcodes", fontSize = 11.sp, color = OnDarkSurfaceVariant)
                                    }
                                }
                            },
                            onClick = {
                                expandedMoreMenu = false
                                onOpenSimulation()
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = null,
                                        tint = ElectricIndigo,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Barcode Formats", color = OnDarkSurface, fontWeight = FontWeight.SemiBold)
                                        Text("Configure 2D, 3D and 1D scanner", fontSize = 11.sp, color = OnDarkSurfaceVariant)
                                    }
                                }
                            },
                            onClick = {
                                expandedMoreMenu = false
                                onOpenBarcodeOptions()
                            }
                        )

                        HorizontalDivider(color = DarkCardBorder)

                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("All Settings", color = OnDarkSurface, fontWeight = FontWeight.Medium)
                                }
                            },
                            onClick = {
                                expandedMoreMenu = false
                                onOpenSettings()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPermissionPlaceholder(
    onRequestPermission: () -> Unit,
    onContinueInEmulator: () -> Unit = {},
    isEmulator: Boolean = false
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
            text = "QR Live Calculator continuously analyzes QR codes and barcodes in the physical world to compute your formulas and project AR results directly over each code.",
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
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(48.dp)
                .testTag("grant_camera_permission_button")
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Grant Camera Permission", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bypass / Emulator Simulation Mode Button
        OutlinedButton(
            onClick = onContinueInEmulator,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFF60A5FA)
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x6660A5FA)),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(48.dp)
                .testTag("continue_in_emulator_button")
        ) {
            Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isEmulator) "Continue in Emulator Mode" else "Continue (Bypass Permission)",
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
