package com.example.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.StateSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import com.example.bluetooth.HidConnectionStatus
import com.example.camera.QrCodeAnalyzer
import com.example.camera.RawDetectedCode
import com.example.data.ProfileRepository
import com.example.engine.QrParser
import com.example.model.KeyboardEndingKey
import com.example.model.KeyboardSendTarget
import com.example.model.QrProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Compact Dual-Mode Input Keyboard ("ALVETAK QR Klavye"):
 * - Top-left toggle: [ Barkod | Klavye ] (Live Camera Barcode Scanner vs Built-in Turkish-Q Classic Keyboard).
 * - Top-right toggle: [ Yerel | BT ] (Commit to current phone app vs transmit over shared Bluetooth HID to PC).
 * - Occupies bottom ~37% of the screen so Chrome / Edge / Excel forms stay visible above.
 * - Uses TextureView (PreviewView.ImplementationMode.COMPATIBLE) so camera video renders reliably inside IME windows.
 */
class AlvetakKeyboardService : InputMethodService(), LifecycleOwner {

    private enum class ScanInputMode {
        BARCODE,
        CLASSIC_KEYBOARD
    }

    private enum class OutputTarget {
        LOCAL,
        BLUETOOTH
    }

    private enum class ShiftState {
        OFF,
        ONCE,
        CAPS_LOCK
    }

    private lateinit var lifecycleRegistry: LifecycleRegistry
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trLocale = Locale("tr", "TR")

    private lateinit var profileRepository: ProfileRepository
    private lateinit var hidManager: BluetoothHidManager
    private lateinit var cameraExecutor: ExecutorService
    private val qrTracker = com.example.camera.QrTracker()

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var previewView: PreviewView? = null
    private var barcodeAnalyzer: QrCodeAnalyzer? = null

    private var scanMode: ScanInputMode = ScanInputMode.BARCODE
    private var outputTarget: OutputTarget = OutputTarget.LOCAL
    private var isSymbolsPage: Boolean = false
    private var shiftState: ShiftState = ShiftState.OFF
    private var lastShiftTapMs: Long = 0L

    private var useFrontCamera = false
    private var isTorchOn = false
    private var currentSendTargetOverride: KeyboardSendTarget? = null

    // Batch duplicate guard inside local keyboard
    private val activeInFrameRaw = mutableSetOf<String>()
    private val sentCounts = mutableMapOf<String, Int>()
    private val lastSeenMs = mutableMapOf<String, Long>()
    private val dismissedUntilGone = mutableSetOf<String>()
    private var pendingConfirmRaw: String? = null
    private var pendingConfirmPayload: String? = null

    // Top toggle views
    private var barcodeTabContainer: LinearLayout? = null
    private var barcodeTabIcon: ImageView? = null
    private var barcodeTabLabel: TextView? = null
    private var classicTabContainer: LinearLayout? = null
    private var classicTabIcon: ImageView? = null
    private var classicTabLabel: TextView? = null

    private var localTargetBtn: TextView? = null
    private var bluetoothTargetBtn: TextView? = null

    // Panels
    private var barcodePanel: FrameLayout? = null
    private var classicKeyboardPanel: LinearLayout? = null
    private var lettersGrid: LinearLayout? = null
    private var symbolsGrid: LinearLayout? = null
    private val letterKeyViews = mutableListOf<Pair<TextView, String>>()
    private var shiftKeyButton: TextView? = null

    // Barcode controls & overlays
    private var statusToastView: TextView? = null
    private var duplicateBanner: LinearLayout? = null
    private var duplicateTextView: TextView? = null
    private var permissionContainer: LinearLayout? = null
    private var torchButton: ImageButton? = null
    private var modeMicroChip: TextView? = null

    private var statusClearRunnable: Runnable? = null
    private var backspaceRepeatRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        profileRepository = ProfileRepository(applicationContext)
        hidManager = BluetoothHidManager.getInstance(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()

        serviceScope.launch {
            hidManager.connectionStatus.collectLatest {
                refreshTargetStyles()
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val screenH = resources.displayMetrics.heightPixels
        val targetKeyboardHeight = (screenH * 0.37f).toInt().coerceIn(dp(265), dp(310))

        letterKeyViews.clear()
        shiftKeyButton = null

        val root = FixedHeightFrameLayout(this, targetKeyboardHeight).apply {
            setBackgroundColor(Color.parseColor("#0B111E"))
        }

        // ============================================================
        // 1. BARCODE CAMERA PANEL
        // ============================================================
        val camPanel = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        barcodePanel = camPanel

        // TextureView Preview (never black inside IME)
        val camPreview = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewView = camPreview
        camPanel.addView(camPreview)

        // Contrast vignette over camera feed
        val vignette = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#77000000"),
                    Color.parseColor("#18000000"),
                    Color.parseColor("#88000000")
                )
            )
        }
        camPanel.addView(vignette)

        // Center aiming scan strip
        val strip = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#42E5E7EB"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#8000F5D4"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(46),
                Gravity.CENTER
            ).apply {
                topMargin = dp(8)
                marginStart = dp(24)
                marginEnd = dp(24)
            }
        }
        camPanel.addView(strip)

        // Batch duplicate confirmation banner
        val dupBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB1C1204"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            setPadding(dp(10), dp(5), dp(8), dp(5))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                topMargin = dp(52)
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
                commitPayloadToTarget(raw, payload, profileRepository.activeProfile.value)
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
        camPanel.addView(dupBanner)

        // Bottom-Left: Camera Flip
        val flipBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_kb_camera_flip)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Kamera Değiştir"
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66000000"))
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
        camPanel.addView(flipBtn)

        // Bottom-Center: Micro Utility Capsule (Payload Mode + Tab + Enter + Backspace)
        val bottomCenterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#990B0F19"))
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(38),
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

        bottomCenterBar.addView(createMicroKey("⇥") { sendTabKey() })
        bottomCenterBar.addView(createMicroKey("↵") { sendEnterOrAction() })
        bottomCenterBar.addView(createMicroKey("⌫") { sendBackspaceKey() })
        camPanel.addView(bottomCenterBar)

        // Bottom-Right: Torch Bolt Button
        val flashBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_kb_flash)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Flaş"
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66000000"))
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
        camPanel.addView(flashBtn)

        // Camera permission overlay
        val permOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EA090D16"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }
        val permText = TextView(this).apply {
            text = "Barkod klavyesini kullanmak için kamera izni verin veya Klasik Klavye sekmesine geçin."
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
        camPanel.addView(permOverlay)

        root.addView(camPanel)

        // ============================================================
        // 2. CLASSIC TURKISH-Q KEYBOARD PANEL
        // ============================================================
        val kbPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B111E"))
            setPadding(dp(4), dp(54), dp(4), dp(6))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        classicKeyboardPanel = kbPanel

        val lettersContainer = buildLettersKeyboardGrid()
        lettersGrid = lettersContainer
        kbPanel.addView(lettersContainer)

        val symbolsContainer = buildSymbolsKeyboardGrid().apply { visibility = View.GONE }
        symbolsGrid = symbolsContainer
        kbPanel.addView(symbolsContainer)

        root.addView(kbPanel)

        // ============================================================
        // 3. TOP FLOATING HEADER ROW: [ Barkod | Klavye ] & [ Yerel | BT ]
        // ============================================================
        val topHeader = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                topMargin = dp(8)
                marginStart = dp(10)
                marginEnd = dp(10)
            }
        }

        // Top-Left: [ Barkod | Klavye ]
        val modePillHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B0090E1A"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(38),
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        }

        val (bTab, bIcon, bLabel) = buildSegmentTab(R.drawable.ic_kb_barcode, "Barkod") {
            setScanMode(ScanInputMode.BARCODE)
        }
        barcodeTabContainer = bTab
        barcodeTabIcon = bIcon
        barcodeTabLabel = bLabel

        val (kTab, kIcon, kLabel) = buildSegmentTab(R.drawable.ic_kb_classic, "Klavye") {
            setScanMode(ScanInputMode.CLASSIC_KEYBOARD)
        }
        classicTabContainer = kTab
        classicTabIcon = kIcon
        classicTabLabel = kLabel

        modePillHost.addView(bTab)
        modePillHost.addView(kTab)
        topHeader.addView(modePillHost)

        // Top-Right: [ Yerel | BT ]
        val targetPillHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B0090E1A"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(38),
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        }

        val yerBtn = buildTargetTab("Yerel") {
            setOutputTarget(OutputTarget.LOCAL)
        }
        localTargetBtn = yerBtn

        val btBtn = buildTargetTab("BT") {
            setOutputTarget(OutputTarget.BLUETOOTH)
        }
        bluetoothTargetBtn = btBtn

        targetPillHost.addView(yerBtn)
        targetPillHost.addView(btBtn)
        topHeader.addView(targetPillHost)

        root.addView(topHeader)

        // ============================================================
        // 4. SHARED STATUS TOAST BANNER (works in both Barkod & Klavye modes)
        // ============================================================
        val statusToast = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#00F5D4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E0090D16"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#6600F5D4"))
            }
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(52)
            }
        }
        statusToastView = statusToast
        root.addView(statusToast)

        refreshModePanelsAndTabs()
        refreshTargetStyles()
        updateModeChipLabel(profileRepository.activeProfile.value)
        return root
    }

    private fun buildLettersKeyboardGrid(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )

            // Row 1: q w e r t y u ı o p ğ ü (12 keys)
            val row1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            listOf("q", "w", "e", "r", "t", "y", "u", "ı", "o", "p", "ğ", "ü").forEach { key ->
                val tv = createCharKeyButton(key, 1f)
                letterKeyViews.add(tv to key)
                row1.addView(tv)
            }
            addView(row1)

            // Row 2: a s d f g h j k l ş i (11 keys)
            val row2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            }
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ş", "i").forEach { key ->
                val tv = createCharKeyButton(key, 1f)
                letterKeyViews.add(tv to key)
                row2.addView(tv)
            }
            addView(row2)

            // Row 3: [⇧] z x c v b n m ö ç [⌫]
            val row3 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            val shiftBtn = createActionKeyButton("⇧", 1.35f) {
                toggleShift()
            }
            shiftKeyButton = shiftBtn
            row3.addView(shiftBtn)

            listOf("z", "x", "c", "v", "b", "n", "m", "ö", "ç").forEach { key ->
                val tv = createCharKeyButton(key, 1f)
                letterKeyViews.add(tv to key)
                row3.addView(tv)
            }

            row3.addView(createBackspaceKeyButton(1.35f))
            addView(row3)

            // Row 4: [?123] [⇥] [,] [Boşluk] [.] [↵]
            addView(buildBottomClassicRow(isSymbolPage = false))
        }
    }

    private fun buildSymbolsKeyboardGrid(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )

            // Row 1: 1 2 3 4 5 6 7 8 9 0
            val row1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").forEach { sym ->
                row1.addView(createSymbolKeyButton(sym, 1f))
            }
            addView(row1)

            // Row 2: - / : ; ( ) ₺ & @ "
            val row2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            listOf("-", "/", ":", ";", "(", ")", "₺", "&", "@", "\"").forEach { sym ->
                row2.addView(createSymbolKeyButton(sym, 1f))
            }
            addView(row2)

            // Row 3: _ + = * % ? ! ' [⌫]
            val row3 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            listOf("_", "+", "=", "*", "%", "?", "!", "'").forEach { sym ->
                row3.addView(createSymbolKeyButton(sym, 1f))
            }
            row3.addView(createBackspaceKeyButton(1.5f))
            addView(row3)

            // Row 4: [ABC] [⇥] [,] [Boşluk] [.] [↵]
            addView(buildBottomClassicRow(isSymbolPage = true))
        }
    }

    private fun buildBottomClassicRow(isSymbolPage: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )

            addView(
                createActionKeyButton(if (isSymbolPage) "ABC" else "?123", 1.35f) {
                    toggleSymbolPage()
                }
            )
            addView(createActionKeyButton("⇥", 1.0f) { sendTabKey() })
            addView(createSymbolKeyButton(",", 0.85f))
            addView(
                createKeyBaseView("Boşluk", 3.4f, "#1E293B", "#94A3B8", 12.5f) {
                    sendClassicCharacter(' ')
                }
            )
            addView(createSymbolKeyButton(".", 0.85f))
            addView(
                createKeyBaseView("↵", 1.55f, "#00F5D4", "#042F2E", 16f, isAccent = true) {
                    sendEnterOrAction()
                }
            )
        }
    }

    private fun createCharKeyButton(baseLower: String, weight: Float): TextView {
        return createKeyBaseView(baseLower, weight, "#1B2436", "#F8FAFC", 15.5f) {
            val charStr = if (shiftState == ShiftState.OFF) {
                baseLower
            } else {
                baseLower.uppercase(trLocale)
            }
            val ch = charStr.firstOrNull()
            if (ch != null) {
                sendClassicCharacter(ch)
            }
            if (shiftState == ShiftState.ONCE) {
                shiftState = ShiftState.OFF
                updateShiftKeyUi()
            }
        }
    }

    private fun createSymbolKeyButton(symbol: String, weight: Float): TextView {
        return createKeyBaseView(symbol, weight, "#1B2436", "#F8FAFC", 15f) {
            symbol.forEach { ch -> sendClassicCharacter(ch) }
        }
    }

    private fun createActionKeyButton(label: String, weight: Float, onClick: () -> Unit): TextView {
        return createKeyBaseView(label, weight, "#131C2D", "#CBD5E1", 13.5f, onClick = onClick)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBackspaceKeyButton(weight: Float): TextView {
        val btn = createKeyBaseView("⌫", weight, "#131C2D", "#CBD5E1", 15f) {
            // Handled via OnTouchListener for instant tap + hold repeat
        }
        btn.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    sendBackspaceKey()
                    startBackspaceRepeat()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    stopBackspaceRepeat()
                    true
                }
                else -> false
            }
        }
        return btn
    }

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        val repeatRunnable = object : Runnable {
            override fun run() {
                sendBackspaceKey()
                mainHandler.postDelayed(this, 65L)
            }
        }
        backspaceRepeatRunnable = repeatRunnable
        mainHandler.postDelayed(repeatRunnable, 380L)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeatRunnable?.let { mainHandler.removeCallbacks(it) }
        backspaceRepeatRunnable = null
    }

    private fun createKeyBaseView(
        label: String,
        weight: Float,
        normalBgHex: String,
        fgHex: String,
        textSizeSp: Float,
        isAccent: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val pressedHex = if (isAccent) "#2DD4BF" else "#334155"
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(fgHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            background = buildKeySelectorDrawable(normalBgHex, pressedHex, dp(7).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                weight
            ).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildKeySelectorDrawable(
        normalHex: String,
        pressedHex: String,
        cornerRadiusPx: Float
    ): StateListDrawable {
        val pressed = GradientDrawable().apply {
            setColor(Color.parseColor(pressedHex))
            cornerRadius = cornerRadiusPx
        }
        val normal = GradientDrawable().apply {
            setColor(Color.parseColor(normalHex))
            cornerRadius = cornerRadiusPx
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(StateSet.WILD_CARD, normal)
        }
    }

    private fun toggleShift() {
        val now = System.currentTimeMillis()
        shiftState = when (shiftState) {
            ShiftState.OFF -> ShiftState.ONCE
            ShiftState.ONCE -> if ((now - lastShiftTapMs) < 400L) ShiftState.CAPS_LOCK else ShiftState.OFF
            ShiftState.CAPS_LOCK -> ShiftState.OFF
        }
        lastShiftTapMs = now
        updateShiftKeyUi()
    }

    private fun updateShiftKeyUi() {
        val density = resources.displayMetrics.density
        val radius = 7f * density

        val isUpper = shiftState != ShiftState.OFF
        letterKeyViews.forEach { (tv, baseLower) ->
            tv.text = if (isUpper) baseLower.uppercase(trLocale) else baseLower
        }

        val sBtn = shiftKeyButton ?: return
        when (shiftState) {
            ShiftState.OFF -> {
                sBtn.text = "⇧"
                sBtn.setTextColor(Color.parseColor("#CBD5E1"))
                sBtn.background = buildKeySelectorDrawable("#131C2D", "#334155", radius)
            }
            ShiftState.ONCE -> {
                sBtn.text = "⇧"
                sBtn.setTextColor(Color.parseColor("#00F5D4"))
                sBtn.background = buildKeySelectorDrawable("#1E3A3A", "#334155", radius)
            }
            ShiftState.CAPS_LOCK -> {
                sBtn.text = "⇪"
                sBtn.setTextColor(Color.parseColor("#042F2E"))
                sBtn.background = buildKeySelectorDrawable("#00F5D4", "#2DD4BF", radius)
            }
        }
    }

    private fun toggleSymbolPage() {
        isSymbolsPage = !isSymbolsPage
        lettersGrid?.visibility = if (isSymbolsPage) View.GONE else View.VISIBLE
        symbolsGrid?.visibility = if (isSymbolsPage) View.VISIBLE else View.GONE
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
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(4) }
        }
        val labelView = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
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

    private fun buildTargetTab(title: String, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        return TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { onClick() }
        }
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
        refreshModePanelsAndTabs()
        if (mode == ScanInputMode.BARCODE) {
            previewView?.post { startCameraIfPermitted() } ?: startCameraIfPermitted()
        } else {
            duplicateBanner?.visibility = View.GONE
            stopCamera()
        }
    }

    private fun setOutputTarget(target: OutputTarget) {
        if (outputTarget == target) {
            if (target == OutputTarget.BLUETOOTH) {
                hidManager.refreshState()
                if (!hidManager.isHidHostConnected()) {
                    showStatusToast("PC Bluetooth bağlı değil — Uygulamadan bağlayın")
                }
            }
            return
        }
        outputTarget = target
        refreshTargetStyles()
        if (target == OutputTarget.BLUETOOTH) {
            hidManager.refreshState()
            if (hidManager.isHidHostConnected()) {
                val hostName = hidManager.connectedDeviceName.value ?: "PC"
                showStatusToast("Hedef: Bluetooth ($hostName)")
            } else {
                showStatusToast("Hedef: BT (Henüz bağlı PC yok)")
            }
        } else {
            showStatusToast("Hedef: Yerel Telefon Kutusu")
        }
    }

    private fun refreshModePanelsAndTabs() {
        val density = resources.displayMetrics.density
        val radius = 18f * density
        val isBarcode = scanMode == ScanInputMode.BARCODE

        barcodePanel?.visibility = if (isBarcode) View.VISIBLE else View.GONE
        classicKeyboardPanel?.visibility = if (isBarcode) View.GONE else View.VISIBLE

        barcodeTabContainer?.background = if (isBarcode) {
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius }
        } else null
        barcodeTabIcon?.setColorFilter(if (isBarcode) Color.parseColor("#0B111E") else Color.WHITE)
        barcodeTabLabel?.setTextColor(if (isBarcode) Color.parseColor("#0B111E") else Color.WHITE)

        classicTabContainer?.background = if (!isBarcode) {
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius }
        } else null
        classicTabIcon?.setColorFilter(if (!isBarcode) Color.parseColor("#0B111E") else Color.WHITE)
        classicTabLabel?.setTextColor(if (!isBarcode) Color.parseColor("#0B111E") else Color.WHITE)
    }

    private fun refreshTargetStyles() {
        val density = resources.displayMetrics.density
        val radius = 18f * density
        val isLocal = outputTarget == OutputTarget.LOCAL
        val isBtConnected = hidManager.connectionStatus.value == HidConnectionStatus.CONNECTED

        localTargetBtn?.apply {
            background = if (isLocal) {
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = radius
                }
            } else null
            setTextColor(if (isLocal) Color.parseColor("#0B111E") else Color.parseColor("#CBD5E1"))
        }

        bluetoothTargetBtn?.apply {
            text = if (isBtConnected) "BT ✓" else "BT"
            background = if (!isLocal) {
                GradientDrawable().apply {
                    setColor(
                        if (isBtConnected) Color.parseColor("#00F5D4")
                        else Color.parseColor("#F59E0B")
                    )
                    cornerRadius = radius
                }
            } else null
            setTextColor(
                when {
                    !isLocal -> Color.parseColor("#042F2E")
                    isBtConnected -> Color.parseColor("#00F5D4")
                    else -> Color.parseColor("#94A3B8")
                }
            )
        }
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
        hidManager.refreshState()
        refreshTargetStyles()
        serviceScope.launch {
            profileRepository.reloadFromDisk()
            updateModeChipLabel(profileRepository.activeProfile.value)
        }
        if (scanMode == ScanInputMode.BARCODE) {
            previewView?.post {
                if (boundCamera == null) {
                    startCameraIfPermitted()
                }
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
        hidManager.refreshState()
        refreshTargetStyles()
        if (scanMode == ScanInputMode.BARCODE) {
            previewView?.post { startCameraIfPermitted() } ?: startCameraIfPermitted()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopBackspaceRepeat()
        stopCamera()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    override fun onDestroy() {
        stopBackspaceRepeat()
        stopCamera()
        serviceScope.cancel()
        cameraExecutor.shutdown()
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        super.onDestroy()
    }

    private fun startCameraIfPermitted() {
        if (scanMode != ScanInputMode.BARCODE) return

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
            if (scanMode != ScanInputMode.BARCODE) return@addListener
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
                                imageProxy.close()
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
        val payload = hidManager.resolvePayload(parsed, effectiveConfig)

        activeInFrameRaw.add(raw)

        val prevCount = sentCounts[raw] ?: 0
        if (prevCount > 0 && effectiveConfig.requireDuplicateConfirmation) {
            pendingConfirmRaw = raw
            pendingConfirmPayload = payload
            duplicateTextView?.text = "Tekrar (${prevCount}x): $payload"
            duplicateBanner?.visibility = View.VISIBLE
            showStatusToast("Onay bekleniyor")
        } else {
            commitPayloadToTarget(raw, payload, activeProfile)
        }
    }

    private fun commitPayloadToTarget(raw: String, payload: String, profile: QrProfile) {
        if (outputTarget == OutputTarget.BLUETOOTH) {
            if (!hidManager.isHidHostConnected()) {
                showStatusToast("PC Bluetooth bağlı değil — Yerel seçin")
                return
            }
            val btCfg = profile.bluetoothKeyboard
            serviceScope.launch {
                val ok = hidManager.sendToComputer(
                    textToSend = payload,
                    rawBarcodeKey = raw,
                    endingKey = btCfg.endingKey,
                    typingDelayMs = btCfg.typingDelayMs,
                    convertTurkishChars = btCfg.convertTurkishChars,
                    keyboardLayout = btCfg.keyboardLayout
                )
                if (ok) {
                    val newCount = (sentCounts[raw] ?: 0) + 1
                    sentCounts[raw] = newCount
                    val preview = payload.replace("\t", " ⇥ ")
                    showStatusToast("✓ BT (#$newCount): $preview")
                } else {
                    showStatusToast("⚠ BT gönderim hatası")
                }
            }
            return
        }

        // Local Phone InputConnection
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

    private fun sendClassicCharacter(ch: Char) {
        if (outputTarget == OutputTarget.BLUETOOTH) {
            if (!hidManager.isHidHostConnected()) {
                showStatusToast("PC Bluetooth bağlı değil")
                return
            }
            val btCfg = profileRepository.activeProfile.value.bluetoothKeyboard
            serviceScope.launch {
                hidManager.sendSingleCharacter(
                    ch = ch,
                    layout = btCfg.keyboardLayout,
                    typingDelayMs = btCfg.typingDelayMs
                )
            }
            return
        }
        currentInputConnection?.commitText(ch.toString(), 1)
    }

    private fun sendTabKey() {
        if (outputTarget == OutputTarget.BLUETOOTH) {
            if (!hidManager.isHidHostConnected()) {
                showStatusToast("PC Bluetooth bağlı değil")
                return
            }
            val btCfg = profileRepository.activeProfile.value.bluetoothKeyboard
            serviceScope.launch {
                hidManager.sendSingleHidKey(
                    keyCode = BluetoothHidManager.KEY_TAB,
                    typingDelayMs = btCfg.typingDelayMs
                )
            }
            return
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
    }

    private fun sendBackspaceKey() {
        if (outputTarget == OutputTarget.BLUETOOTH) {
            if (!hidManager.isHidHostConnected()) {
                showStatusToast("PC Bluetooth bağlı değil")
                return
            }
            val btCfg = profileRepository.activeProfile.value.bluetoothKeyboard
            serviceScope.launch {
                hidManager.sendSingleHidKey(
                    keyCode = BluetoothHidManager.KEY_BACKSPACE,
                    typingDelayMs = btCfg.typingDelayMs
                )
            }
            return
        }
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun sendEnterOrAction() {
        if (outputTarget == OutputTarget.BLUETOOTH) {
            if (!hidManager.isHidHostConnected()) {
                showStatusToast("PC Bluetooth bağlı değil")
                return
            }
            val btCfg = profileRepository.activeProfile.value.bluetoothKeyboard
            serviceScope.launch {
                hidManager.sendSingleHidKey(
                    keyCode = BluetoothHidManager.KEY_ENTER,
                    typingDelayMs = btCfg.typingDelayMs
                )
            }
            return
        }
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
