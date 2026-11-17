package com.example.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.R
import com.example.bluetooth.BluetoothHidManager
import com.example.camera.QrCodeAnalyzer
import com.example.camera.QrTracker
import com.example.camera.RawDetectedCode
import com.example.data.ProfileRepository
import com.example.engine.QrParser
import com.example.model.KeyboardEndingKey
import com.example.model.KeyboardSendTarget
import com.example.model.QrProfile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Compact Camera Input Keyboard ("ALVETAK QR Klavye") modeled after clean camera-strip IMEs:
 * - Occupies only the bottom ~34% of the screen so Chrome / Edge / Excel forms stay visible above.
 * - Uses TextureView (PreviewView.ImplementationMode.COMPATIBLE) so camera video renders reliably inside IME windows.
 * - Top centered segmented toggle: [ Barkod | Metin ] (Barcode scanning vs live OCR Text capture).
 * - Center frosted horizontal scan strip for aiming barcodes or reading text.
 * - Bottom-left Camera Flip, bottom-right Flash/Torch, plus subtle micro action pills (Mode / Tab / Enter / Backspace).
 */
class AlvetakKeyboardService : InputMethodService(), LifecycleOwner {

    private enum class ScanInputMode {
        BARCODE,
        TEXT_OCR
    }

    private lateinit var lifecycleRegistry: LifecycleRegistry
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var profileRepository: ProfileRepository
    private val qrTracker = QrTracker()
    private val hidPayloadHelper = BluetoothHidManager(null)
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textRecognizer: TextRecognizer

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var previewView: PreviewView? = null
    private var barcodeAnalyzer: QrCodeAnalyzer? = null

    private var scanMode: ScanInputMode = ScanInputMode.BARCODE
    private var useFrontCamera = false
    private var isTorchOn = false
    private var currentSendTargetOverride: KeyboardSendTarget? = null
    private var lastOcrScanTimeMs = 0L
    private var latestDetectedOcrText: String = ""

    // Batch duplicate guard inside local keyboard
    private val activeInFrameRaw = mutableSetOf<String>()
    private val sentCounts = mutableMapOf<String, Int>()
    private val lastSeenMs = mutableMapOf<String, Long>()
    private val dismissedUntilGone = mutableSetOf<String>()
    private var pendingConfirmRaw: String? = null
    private var pendingConfirmPayload: String? = null

    // UI References
    private var barcodeTabContainer: LinearLayout? = null
    private var barcodeTabIcon: ImageView? = null
    private var barcodeTabLabel: TextView? = null
    private var textTabContainer: LinearLayout? = null
    private var textTabIcon: ImageView? = null
    private var textTabLabel: TextView? = null

    private var scanStripBox: LinearLayout? = null
    private var ocrPreviewTextView: TextView? = null
    private var ocrCommitChip: TextView? = null
    private var statusToastView: TextView? = null
    private var duplicateBanner: LinearLayout? = null
    private var duplicateTextView: TextView? = null
    private var permissionContainer: LinearLayout? = null
    private var torchButton: ImageButton? = null
    private var modeMicroChip: TextView? = null

    private var statusClearRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        profileRepository = ProfileRepository(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val screenH = resources.displayMetrics.heightPixels
        val targetKeyboardHeight = (screenH * 0.34f).toInt().coerceIn(dp(235), dp(290))

        // Fixed-height root guarantees the IME never stretches to fill the screen
        val root = FixedHeightFrameLayout(this, targetKeyboardHeight).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. Full-bleed Live Camera Preview (COMPATIBLE = TextureView so it NEVER shows black in IME!)
        val camPreview = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewView = camPreview
        root.addView(camPreview)

        // Subtle dark vignette overlay for high contrast of floating white pills/icons
        val vignette = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#66000000"),
                    Color.parseColor("#18000000"),
                    Color.parseColor("#77000000")
                )
            )
        }
        root.addView(vignette)

        // 2. Top Centered Segmented Toggle: [ Barcode | Text ]
        val pillHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#880B0F19"))
                cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(42),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(10)
            }
        }

        val (bTab, bIcon, bLabel) = buildSegmentTab(R.drawable.ic_kb_barcode, "Barkod") {
            setScanMode(ScanInputMode.BARCODE)
        }
        barcodeTabContainer = bTab
        barcodeTabIcon = bIcon
        barcodeTabLabel = bLabel

        val (tTab, tIcon, tLabel) = buildSegmentTab(R.drawable.ic_kb_text_scan, "Metin") {
            setScanMode(ScanInputMode.TEXT_OCR)
        }
        textTabContainer = tTab
        textTabIcon = tIcon
        textTabLabel = tLabel

        pillHost.addView(bTab)
        pillHost.addView(tTab)
        root.addView(pillHost)

        // 3. Center Frosted Horizontal Scan Strip (Aiming Reticle + OCR Capture Strip)
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#54E5E7EB"))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(46),
                Gravity.CENTER
            ).apply {
                marginStart = dp(22)
                marginEnd = dp(22)
            }
            setOnClickListener {
                if (scanMode == ScanInputMode.TEXT_OCR && latestDetectedOcrText.isNotBlank()) {
                    commitTextOcrToEditor(latestDetectedOcrText)
                }
            }
        }
        val ocrText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#111827"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ocrPreviewTextView = ocrText
        strip.addView(ocrText)

        val commitChip = TextView(this).apply {
            text = "Yaz ↵"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111827"))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
            setOnClickListener {
                if (latestDetectedOcrText.isNotBlank()) {
                    commitTextOcrToEditor(latestDetectedOcrText)
                }
            }
        }
        ocrCommitChip = commitChip
        strip.addView(commitChip)
        scanStripBox = strip
        root.addView(strip)

        // 4. Compact Feedback Toast / Batch Duplicate Banner (just below center strip)
        val statusToast = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#00F5D4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC090D16"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#5500F5D4"))
            }
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                topMargin = dp(46)
            }
        }
        statusToastView = statusToast
        root.addView(statusToast)

        val dupBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61C1204"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            setPadding(dp(10), dp(5), dp(8), dp(5))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                topMargin = dp(48)
                marginStart = dp(16)
                marginEnd = dp(16)
            }
        }
        val dupLabel = TextView(this).apply {
            text = "Tekrar okundu"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        duplicateTextView = dupLabel
        dupBanner.addView(dupLabel)

        val confirmBtn = createMiniPill("Tekrar (+1)", "#00F5D4", "#042F2E") {
            val raw = pendingConfirmRaw
            val payload = pendingConfirmPayload
            if (raw != null && payload != null) {
                commitPayloadToEditor(raw, payload, profileRepository.activeProfile.value)
            }
            pendingConfirmRaw = null
            pendingConfirmPayload = null
            duplicateBanner?.visibility = View.GONE
        }
        dupBanner.addView(confirmBtn)

        val skipBtn = createMiniPill("Atla", "#334155", "#FFFFFF") {
            pendingConfirmRaw?.let { dismissedUntilGone.add(it) }
            pendingConfirmRaw = null
            pendingConfirmPayload = null
            duplicateBanner?.visibility = View.GONE
        }
        dupBanner.addView(skipBtn)
        duplicateBanner = dupBanner
        root.addView(dupBanner)

        // 5. Bottom-Left: Camera Flip Icon Button
        val flipBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_kb_camera_flip)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Kamera Değiştir"
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#55000000"))
                shape = GradientDrawable.OVAL
            }
            setPadding(dp(11), dp(11), dp(11), dp(11))
            layoutParams = FrameLayout.LayoutParams(dp(46), dp(46), Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dp(16)
                bottomMargin = dp(12)
            }
            setOnClickListener {
                useFrontCamera = !useFrontCamera
                if (useFrontCamera) isTorchOn = false
                updateTorchIconTint()
                startCameraIfPermitted()
            }
        }
        root.addView(flipBtn)

        // 6. Bottom-Center: Micro Utility Capsule (Send Mode + Tab + Enter + Backspace)
        val bottomCenterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#880B0F19"))
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(6), dp(3), dp(6), dp(3))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(36),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(16)
            }
        }

        val modeView = TextView(this).apply {
            text = "Ham"
            setTextColor(Color.parseColor("#00F5D4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                val active = profileRepository.activeProfile.value
                val current = currentSendTargetOverride ?: active.bluetoothKeyboard.sendTarget
                currentSendTargetOverride = when (current) {
                    KeyboardSendTarget.WHOLE_RAW -> KeyboardSendTarget.MULTIPLE_VARIABLES
                    KeyboardSendTarget.MULTIPLE_VARIABLES -> KeyboardSendTarget.FORMULA_RESULT
                    KeyboardSendTarget.FORMULA_RESULT -> KeyboardSendTarget.SINGLE_VARIABLE
                    else -> KeyboardSendTarget.WHOLE_RAW
                }
                updateModeChipLabel(active)
            }
        }
        modeMicroChip = modeView
        bottomCenterBar.addView(modeView)

        bottomCenterBar.addView(createMicroKey("⇥") {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
        })
        bottomCenterBar.addView(createMicroKey("↵") {
            sendEnterOrAction()
        })
        bottomCenterBar.addView(createMicroKey("⌫") {
            currentInputConnection?.deleteSurroundingText(1, 0)
        })
        root.addView(bottomCenterBar)

        // 7. Bottom-Right: Flash / Torch Bolt Button
        val flashBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_kb_flash)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Flaş"
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#55000000"))
                shape = GradientDrawable.OVAL
            }
            setPadding(dp(11), dp(11), dp(11), dp(11))
            layoutParams = FrameLayout.LayoutParams(dp(46), dp(46), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(16)
                bottomMargin = dp(12)
            }
            setOnClickListener {
                if (!useFrontCamera) {
                    isTorchOn = !isTorchOn
                    boundCamera?.cameraControl?.enableTorch(isTorchOn)
                    updateTorchIconTint()
                }
            }
        }
        torchButton = flashBtn
        updateTorchIconTint()
        root.addView(flashBtn)

        // 8. Camera Permission Overlay (only visible if user hasn't granted CAMERA permission yet)
        val permOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EA090D16"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val permText = TextView(this).apply {
            text = "Kamera klavyesini kullanmak için uygulama içi kamera izni verin."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        val openAppBtn = Button(this).apply {
            text = "İzin Ver / Uygulamayı Aç"
            isAllCaps = false
            setTextColor(Color.parseColor("#042F2E"))
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00F5D4"))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                startActivity(Intent(this@AlvetakKeyboardService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
        permOverlay.addView(permText)
        permOverlay.addView(openAppBtn)
        permissionContainer = permOverlay
        root.addView(permOverlay)

        refreshTabStyles()
        updateModeChipLabel(profileRepository.activeProfile.value)
        return root
    }

    private fun buildSegmentTab(
        iconRes: Int,
        title: String,
        onClick: () -> Unit
    ): Triple<LinearLayout, ImageView, TextView> {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val iconView = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) }
        }
        val labelView = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(iconView)
            addView(labelView)
            setOnClickListener { onClick() }
        }
        return Triple(container, iconView, labelView)
    }

    private fun createMiniPill(label: String, bgHex: String, fgHex: String, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(fgHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    private fun createMicroKey(symbol: String, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        return TextView(this).apply {
            text = symbol
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(26)
            ).apply { marginStart = dp(4) }
            setOnClickListener { onClick() }
        }
    }

    private fun setScanMode(mode: ScanInputMode) {
        if (scanMode == mode) return
        scanMode = mode
        latestDetectedOcrText = ""
        refreshTabStyles()
        if (mode == ScanInputMode.BARCODE) {
            ocrPreviewTextView?.visibility = View.GONE
            ocrCommitChip?.visibility = View.GONE
        } else {
            duplicateBanner?.visibility = View.GONE
            ocrPreviewTextView?.text = "Metni orta şeride hizalayın..."
            ocrPreviewTextView?.visibility = View.VISIBLE
            ocrCommitChip?.visibility = View.GONE
        }
    }

    private fun refreshTabStyles() {
        val density = resources.displayMetrics.density
        val radius = 20f * density
        val isBarcode = scanMode == ScanInputMode.BARCODE

        barcodeTabContainer?.background = if (isBarcode) {
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius }
        } else null
        barcodeTabIcon?.setColorFilter(if (isBarcode) Color.parseColor("#111827") else Color.WHITE)
        barcodeTabLabel?.setTextColor(if (isBarcode) Color.parseColor("#111827") else Color.WHITE)

        textTabContainer?.background = if (!isBarcode) {
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius }
        } else null
        textTabIcon?.setColorFilter(if (!isBarcode) Color.parseColor("#111827") else Color.WHITE)
        textTabLabel?.setTextColor(if (!isBarcode) Color.parseColor("#111827") else Color.WHITE)

        modeMicroChip?.visibility = if (isBarcode) View.VISIBLE else View.GONE
    }

    private fun updateTorchIconTint() {
        torchButton?.alpha = if (useFrontCamera) 0.35f else 1f
        torchButton?.setColorFilter(
            if (isTorchOn && !useFrontCamera) Color.parseColor("#FBBF24") else Color.WHITE
        )
    }

    private fun updateModeChipLabel(profile: QrProfile) {
        val target = currentSendTargetOverride ?: profile.bluetoothKeyboard.sendTarget
        modeMicroChip?.text = when (target) {
            KeyboardSendTarget.WHOLE_RAW -> "Ham"
            KeyboardSendTarget.FORMULA_RESULT -> "Sonuç"
            KeyboardSendTarget.SINGLE_VARIABLE -> "Tek"
            KeyboardSendTarget.MULTIPLE_VARIABLES -> "Çoklu"
            KeyboardSendTarget.CUSTOM_TEMPLATE -> "Şablon"
        }
    }

    private fun showStatusToast(message: String) {
        statusToastView?.text = message
        statusToastView?.visibility = View.VISIBLE
        statusClearRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { statusToastView?.visibility = View.GONE }
        statusClearRunnable = r
        mainHandler.postDelayed(r, 2200L)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        serviceScope.launch {
            profileRepository.reloadFromDisk()
            updateModeChipLabel(profileRepository.activeProfile.value)
        }
        previewView?.post {
            if (boundCamera == null) {
                startCameraIfPermitted()
            }
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        previewView?.post { startCameraIfPermitted() } ?: startCameraIfPermitted()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopCamera()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    override fun onDestroy() {
        stopCamera()
        serviceScope.cancel()
        runCatching { textRecognizer.close() }
        cameraExecutor.shutdown()
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        super.onDestroy()
    }

    private fun startCameraIfPermitted() {
        val hasPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPerm) {
            permissionContainer?.visibility = View.VISIBLE
            return
        }
        permissionContainer?.visibility = View.GONE

        val pv = previewView ?: return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val activeProfile = profileRepository.activeProfile.value
                val newQrAnalyzer = QrCodeAnalyzer { detectedList ->
                    mainHandler.post {
                        handleDetectedCodes(detectedList)
                    }
                }.apply {
                    isFrontCamera = useFrontCamera
                    updateBarcodeFormats(
                        preset = activeProfile.tracking.scanPreset,
                        customFormats = activeProfile.tracking.enabledFormats,
                        enableInverted = activeProfile.tracking.enableInvertedDetection
                    )
                }
                barcodeAnalyzer = newQrAnalyzer

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imgAnalysis ->
                        imgAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            if (scanMode == ScanInputMode.BARCODE) {
                                newQrAnalyzer.analyze(imageProxy)
                            } else {
                                analyzeOcrFrame(imageProxy)
                            }
                        }
                    }

                val selector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                boundCamera = provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    analysis
                )
                if (!useFrontCamera && isTorchOn) {
                    boundCamera?.cameraControl?.enableTorch(true)
                }
            } catch (e: Exception) {
                showStatusToast("Kamera açılamadı: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeOcrFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastOcrScanTimeMs < 250L) {
            imageProxy.close()
            return
        }
        lastOcrScanTimeMs = now

        val rotation = imageProxy.imageInfo.rotationDegrees
        val isRotated = rotation == 90 || rotation == 270
        val frameH = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                // Prioritize text lines inside the middle horizontal strip (30%..70% height)
                val centerMinY = frameH * 0.25f
                val centerMaxY = frameH * 0.75f
                val centerLines = visionText.textBlocks
                    .flatMap { it.lines }
                    .filter { line ->
                        val box = line.boundingBox
                        box == null || (box.centerY().toFloat() in centerMinY..centerMaxY)
                    }
                    .map { it.text.trim() }
                    .filter { it.isNotEmpty() }

                val candidate = centerLines.firstOrNull() ?: visionText.text.trim().lineSequence().firstOrNull().orEmpty()
                mainHandler.post {
                    if (scanMode == ScanInputMode.TEXT_OCR) {
                        latestDetectedOcrText = candidate
                        if (candidate.isNotBlank()) {
                            ocrPreviewTextView?.text = candidate
                            ocrCommitChip?.visibility = View.VISIBLE
                        } else {
                            ocrPreviewTextView?.text = "Metni orta şeride hizalayın..."
                            ocrCommitChip?.visibility = View.GONE
                        }
                    }
                }
            }
            .addOnFailureListener {
                mainHandler.post {
                    if (scanMode == ScanInputMode.TEXT_OCR && latestDetectedOcrText.isBlank()) {
                        ocrPreviewTextView?.text = "Metin algılama hazırlanıyor..."
                        ocrCommitChip?.visibility = View.GONE
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun stopCamera() {
        try {
            isTorchOn = false
            updateTorchIconTint()
            cameraProvider?.unbindAll()
            boundCamera = null
        } catch (_: Exception) {}
    }

    private fun handleDetectedCodes(detected: List<RawDetectedCode>) {
        if (scanMode != ScanInputMode.BARCODE) return
        val now = System.currentTimeMillis()

        val visibleRaws = detected.map { it.rawValue }.toSet()
        for (raw in visibleRaws) {
            lastSeenMs[raw] = now
        }

        // Release barcodes that left camera view for > 1.2s
        val gone = activeInFrameRaw.filter { it !in visibleRaws && (now - (lastSeenMs[it] ?: 0L)) > 1200L }
        activeInFrameRaw.removeAll(gone.toSet())
        dismissedUntilGone.removeAll(gone.toSet())

        val firstCode = detected.firstOrNull() ?: return
        val raw = firstCode.rawValue
        if (raw in activeInFrameRaw || raw in dismissedUntilGone) return

        // Auto-switch profile if signal tag matched
        val profiles = profileRepository.profiles.value
        val matched = QrParser.findMatchingProfileBySignal(raw, profiles)
        if (matched != null && matched.id != profileRepository.activeProfile.value.id) {
            profileRepository.selectProfile(matched.id)
        }
        val activeProfile = profileRepository.activeProfile.value
        updateModeChipLabel(activeProfile)

        val parsed = qrTracker.processQr(raw, activeProfile, firstCode.format, firstCode.formatName)
        val effectiveConfig = activeProfile.bluetoothKeyboard.copy(
            sendTarget = currentSendTargetOverride ?: activeProfile.bluetoothKeyboard.sendTarget
        )
        val payload = hidPayloadHelper.resolvePayload(parsed, effectiveConfig)

        activeInFrameRaw.add(raw)

        val prevCount = sentCounts[raw] ?: 0
        if (prevCount > 0 && effectiveConfig.requireDuplicateConfirmation) {
            pendingConfirmRaw = raw
            pendingConfirmPayload = payload
            duplicateTextView?.text = "Tekrar (${prevCount}x): $payload"
            duplicateBanner?.visibility = View.VISIBLE
            showStatusToast("Onay bekleniyor")
        } else {
            commitPayloadToEditor(raw, payload, activeProfile)
        }
    }

    private fun commitTextOcrToEditor(text: String) {
        val ic = currentInputConnection ?: return
        val profile = profileRepository.activeProfile.value
        ic.commitText(text, 1)
        when (profile.bluetoothKeyboard.endingKey) {
            KeyboardEndingKey.ENTER -> sendEnterOrAction()
            KeyboardEndingKey.TAB -> sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
            KeyboardEndingKey.TAB_THEN_ENTER -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
                sendEnterOrAction()
            }
            KeyboardEndingKey.SPACE -> ic.commitText(" ", 1)
            KeyboardEndingKey.NONE -> {}
        }
        showStatusToast("✓ Yazıldı: $text")
    }

    private fun commitPayloadToEditor(raw: String, payload: String, profile: QrProfile) {
        val ic = currentInputConnection ?: return

        val parts = payload.split("\t")
        parts.forEachIndexed { idx, part ->
            if (part.isNotEmpty()) {
                ic.commitText(part, 1)
            }
            if (idx < parts.lastIndex) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
            }
        }

        when (profile.bluetoothKeyboard.endingKey) {
            KeyboardEndingKey.ENTER -> sendEnterOrAction()
            KeyboardEndingKey.TAB -> sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
            KeyboardEndingKey.TAB_THEN_ENTER -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
                sendEnterOrAction()
            }
            KeyboardEndingKey.SPACE -> ic.commitText(" ", 1)
            KeyboardEndingKey.NONE -> {}
        }

        val newCount = (sentCounts[raw] ?: 0) + 1
        sentCounts[raw] = newCount

        val displayPreview = payload.replace("\t", " ⇥ ")
        showStatusToast("✓ Yazıldı (#$newCount): $displayPreview")
    }

    private fun sendEnterOrAction() {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo
        val actionId = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(actionId)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private class FixedHeightFrameLayout(
        context: Context,
        private val fixedHeightPx: Int
    ) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val exactHeightSpec = MeasureSpec.makeMeasureSpec(fixedHeightPx, MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, exactHeightSpec)
        }
    }
}
