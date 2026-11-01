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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.data.ProfileRepository
import com.example.model.QrProfile
import com.example.model.TrackedQr
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    profileRepository: ProfileRepository,
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

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }

    val analyzer = remember {
        QrCodeAnalyzer { rawList ->
            if (!isPaused) {
                val combined = rawList + simulatedCodes
                val updated = qrTracker.update(combined, activeProfile)
                trackedQrs = updated
            }
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
                trackedQrs = qrTracker.update(moved, activeProfile)
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

    // Periodic ticker to smoothly fade out lost QRs
    LaunchedEffect(activeProfile) {
        while (true) {
            kotlinx.coroutines.delay(50)
            if (trackedQrs.isNotEmpty()) {
                val updated = qrTracker.update(emptyList(), activeProfile)
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

            // AR Overlay Layer
            QrOverlaysCanvas(
                trackedItems = trackedQrs,
                overlayConfig = activeProfile.overlay,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                modifier = Modifier.fillMaxSize()
            )

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
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

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

            // Bottom action buttons: Simulation Wand & Settings FAB
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 24.dp, end = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { showSimulationSheet = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xDD090D16),
                        contentColor = Color(0xFF00F5D4)
                    ),
                    modifier = Modifier.testTag("test_ar_simulation_btn")
                ) {
                    Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AR Test Codes", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                        RawDetectedCode("10-20-30-40", RectF(centerX - 100f, centerY - 100f, centerX + 100f, centerY + 100f))
                                    )
                                    showSimulationSheet = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Sum: 10-20-30-40", fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    val w = screenWidthPx; val h = screenHeightPx
                                    simulatedCodes = listOf(
                                        RawDetectedCode("10-20-30-40", RectF(w * 0.15f, h * 0.25f, w * 0.15f + 160f, h * 0.25f + 160f)),
                                        RawDetectedCode("5-10-15-20", RectF(w * 0.55f, h * 0.45f, w * 0.55f + 160f, h * 0.45f + 160f)),
                                        RawDetectedCode("100-200-300-400", RectF(w * 0.25f, h * 0.65f, w * 0.25f + 160f, h * 0.65f + 160f))
                                    )
                                    showSimulationSheet = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Multi-QR (3 Codes)", fontSize = 11.sp)
                            }
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
                onClick = onOpenSimulation,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Science,
                    contentDescription = "Test Simulated Codes",
                    tint = Color(0xFF00F5D4),
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
