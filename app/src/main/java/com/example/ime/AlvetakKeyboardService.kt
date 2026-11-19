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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.R
import com.example.bluetooth.AirMouseController
import com.example.bluetooth.BluetoothHidManager
import com.example.bluetooth.HidConnectionStatus
import com.example.camera.QrCodeAnalyzer
import com.example.camera.RawDetectedCode
import com.example.data.ProfileRepository
import com.example.debug.AppDebugLogger
import com.example.engine.QrParser
import com.example.model.KeyboardEndingKey
import com.example.model.KeyboardSendTarget
import com.example.model.QrProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Compact 3-Mode Input Keyboard ("ALVETAK QR Klavye & Mouse"):
 * - Top-left toggle: [ Barkod | Klavye | Mouse ]
 * - Top-right toggle: [ Yerel | BT ]
 * - Inset-aware root dynamically reserves bottom navigation / Android keyboard-chooser bar
 *   insets so system `⌨` and `v` buttons never overlap keys or camera controls.
 */
class AlvetakKeyboardService : InputMethodService(), LifecycleOwner {

    private enum class ScanInputMode {
        BARCODE,
        CLASSIC_KEYBOARD,
        MOUSE
    }

    private enum class ClassicPage {
        LETTERS,
        SYMBOLS,
        FUNCTION_NAV
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
    private val btHidQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trLocale = Locale("tr", "TR")

    private lateinit var profileRepository: ProfileRepository
    private lateinit var hidManager: BluetoothHidManager
    private lateinit var airMouseController: AirMouseController
    private lateinit var cameraExecutor: ExecutorService
    private val qrTracker = com.example.camera.QrTracker()

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var previewView: PreviewView? = null
    private var barcodeAnalyzer: QrCodeAnalyzer? = null

    private var scanMode: ScanInputMode = ScanInputMode.BARCODE
    private var outputTarget: OutputTarget = OutputTarget.LOCAL
    private var classicPage: ClassicPage = ClassicPage.LETTERS
    private var shiftState: ShiftState = ShiftState.OFF
    private var lastShiftTapMs: Long = 0L

    // Sticky PC Modifiers for Full Keyboard support
    private var isCtrlSticky = false
    private var isAltSticky = false
    private var isWinSticky = false

    // Mouse / Air Mouse states
    private var isAirMouseContinuous = false
    private var isAirMouseHoldAiming = false
    private var isDragLocked = false

    private var useFrontCamera = false
    private var isTorchOn = false
    private var currentSendTargetOverride: KeyboardSendTarget? = null

    // Batch & consecutive duplicate guard inside keyboard
    private val activeInFrameRaw = mutableSetOf<String>()
    private val sentCounts = mutableMapOf<String, Int>()
    private val lastSeenMs = mutableMapOf<String, Long>()
    private val dismissedUntilGone = mutableSetOf<String>()
    private var lastCommittedRaw: String = ""
    private var lastCommittedAtMs: Long = 0L
    private var pendingConfirmRaw: String? = null
    private var pendingConfirmPayload: String? = null

    private var rootLayout: InsetAwareKeyboardRootLayout? = null

    // Top toggle views
    private var barcodeTabContainer: LinearLayout? = null
    private var barcodeTabIcon: ImageView? = null
    private var barcodeTabLabel: TextView? = null
    private var classicTabContainer: LinearLayout? = null
    private var classicTabIcon: ImageView? = null
    private var classicTabLabel: TextView? = null
    private var mouseTabContainer: LinearLayout? = null
    private var mouseTabIcon: ImageView? = null
    private var mouseTabLabel: TextView? = null

    private var localTargetBtn: TextView? = null
    private var bluetoothTargetBtn: TextView? = null

    // Panels
    private var barcodePanel: FrameLayout? = null
    private var classicKeyboardPanel: LinearLayout? = null
    private var mousePanel: LinearLayout? = null
    private var lettersGrid: LinearLayout? = null
    private var symbolsGrid: LinearLayout? = null
    private var functionNavGrid: LinearLayout? = null
    private val letterKeyViews = mutableListOf<Pair<TextView, String>>()
    private var shiftKeyButton: TextView? = null
    private var ctrlKeyBtn: TextView? = null
    private var altKeyBtn: TextView? = null
    private var winKeyBtn: TextView? = null

    // Mouse panel views
    private var airToggleBtn: TextView? = null
    private var airHoldBtn: TextView? = null
    private var dragLockBtn: TextView? = null
    private var touchpadHintView: TextView? = null

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
        AppDebugLogger.installGlobalExceptionHandler(applicationContext)
        AppDebugLogger.i("IME", "AlvetakKeyboardService onCreate")
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        profileRepository = ProfileRepository(applicationContext)
        hidManager = BluetoothHidManager.getInstance(applicationContext)
        airMouseController = AirMouseController(applicationContext) { dx, dy ->
            if (hidManager.isHidHostConnected()) {
                hidManager.sendMouseMove(dx, dy)
            }
        }
        cameraExecutor = Executors.newSingleThreadExecutor()

        serviceScope.launch(AppDebugLogger.coroutineHandler("IME")) {
            hidManager.connectionStatus.collectLatest {
                refreshTargetStyles()
                updateTouchpadStatusHint()
            }
        }
        serviceScope.launch(AppDebugLogger.coroutineHandler("IME-BT")) {
            for (task in btHidQueue) {
                runCatching { task() }
                    .onFailure { AppDebugLogger.e("IME-BT", "IME HID görev hatası", it) }
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val screenH = resources.displayMetrics.heightPixels
        val targetKeyboardHeight = (screenH * 0.39f).toInt().coerceIn(dp(275), dp(325))

        letterKeyViews.clear()
        shiftKeyButton = null
        ctrlKeyBtn = null
        altKeyBtn = null
        winKeyBtn = null

        val root = InsetAwareKeyboardRootLayout(this, targetKeyboardHeight).apply {
            setBackgroundColor(Color.parseColor("#0B111E"))
        }
        rootLayout = root

        // Reserve bottom space for Android IME navigation / keyboard-chooser bar (`⌨` & `v`)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            syncImeBottomInset(windowInsets)
            windowInsets
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attachDecorInsetsListener()
                syncImeBottomInset(null)
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })

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
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dp(14)
                bottomMargin = dp(18)
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
                bottomMargin = dp(20)
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
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(14)
                bottomMargin = dp(18)
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
            text = "Barkod klavyesini kullanmak için kamera izni verin veya Klavye / Mouse sekmesine geçin."
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
        // 2. CLASSIC TURKISH-Q & FULL PC KEYBOARD PANEL
        // ============================================================
        val kbPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B111E"))
            setPadding(dp(4), dp(52), dp(4), dp(8))
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

        val fnContainer = buildFunctionNavGrid().apply { visibility = View.GONE }
        functionNavGrid = fnContainer
        kbPanel.addView(fnContainer)

        root.addView(kbPanel)

        // ============================================================
        // 3. MOUSE & AIR MOUSE PANEL
        // ============================================================
        val mPanel = buildMousePanel()
        mPanel.visibility = View.GONE
        mousePanel = mPanel
        root.addView(mPanel)

        // ============================================================
        // 4. TOP FLOATING HEADER ROW: [ Barkod | Klavye | Mouse ] & [ Yerel | BT ]
        // ============================================================
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                topMargin = dp(6)
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }

        // Top-Left: [ Barkod | Klavye | Mouse ] wrapped in HorizontalScrollView to prevent collision on narrow screens
        val modeScrollHost = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = dp(6)
            }
        }

        val modePillHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#C4090E1A"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
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

        val (mTab, mIcon, mLabel) = buildSegmentTab(R.drawable.ic_kb_mouse, "Mouse") {
            setScanMode(ScanInputMode.MOUSE)
        }
        mouseTabContainer = mTab
        mouseTabIcon = mIcon
        mouseTabLabel = mLabel

        modePillHost.addView(bTab)
        modePillHost.addView(kTab)
        modePillHost.addView(mTab)
        modeScrollHost.addView(modePillHost)
        topHeader.addView(modeScrollHost)

        // Top-Right: [ Yerel | BT ]
        val targetPillHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#C4090E1A"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
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
        // 5. SHARED STATUS TOAST BANNER (works across all 3 modes)
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
                topMargin = dp(48)
            }
        }
        statusToastView = statusToast
        root.addView(statusToast)

        refreshModePanelsAndTabs()
        refreshTargetStyles()
        refreshModifierUi()
        updateTouchpadStatusHint()
        updateModeChipLabel(profileRepository.activeProfile.value)
        return root
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildMousePanel(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B111E"))
            setPadding(dp(8), dp(48), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            // Middle: Touchpad Surface + Right Scroll Strip
            val padRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { bottomMargin = dp(6) }
            }

            // Touchpad Area Container
            val touchpadContainer = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#111A2C"))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.parseColor("#2A3A57"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).apply { marginEnd = dp(6) }
            }

            // Interactive Touchpad Gesture Surface
            var lastX = 0f
            var lastY = 0f
            var accumDx = 0f
            var accumDy = 0f
            var accumWheel = 0f
            var downTime = 0L
            var downX = 0f
            var downY = 0f
            var maxPointers = 0

            val gestureSurface = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastX = event.x
                            lastY = event.y
                            downX = event.x
                            downY = event.y
                            downTime = System.currentTimeMillis()
                            maxPointers = 1
                            accumDx = 0f
                            accumDy = 0f
                            accumWheel = 0f
                            true
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            maxPointers = maxOf(maxPointers, event.pointerCount)
                            if (event.pointerCount >= 2) {
                                lastY = event.getY(0)
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (event.pointerCount >= 2) {
                                // Two-finger vertical scroll
                                val curY = event.getY(0)
                                val dy = curY - lastY
                                lastY = curY
                                accumWheel -= dy / dp(14)
                                val stepW = accumWheel.toInt()
                                if (stepW != 0) {
                                    accumWheel -= stepW
                                    hidManager.sendMouseMove(0, 0, wheel = stepW.coerceIn(-12, 12))
                                }
                            } else {
                                val dx = event.x - lastX
                                val dy = event.y - lastY
                                lastX = event.x
                                lastY = event.y
                                val speed = kotlin.math.hypot(dx, dy)
                                val accel = (1.15f + (speed / 28f).coerceIn(0f, 1.4f)) / (density * 0.65f)
                                accumDx += dx * accel
                                accumDy += dy * accel
                                val sendX = accumDx.toInt()
                                val sendY = accumDy.toInt()
                                if (sendX != 0 || sendY != 0) {
                                    accumDx -= sendX
                                    accumDy -= sendY
                                    hidManager.sendMouseMove(sendX, sendY)
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val dt = System.currentTimeMillis() - downTime
                            val moved = kotlin.math.hypot(event.x - downX, event.y - downY)
                            if (dt < 230L && moved < dp(12)) {
                                if (!hidManager.isHidHostConnected()) {
                                    showStatusToast("PC Bluetooth bağlı değil")
                                } else if (maxPointers >= 2) {
                                    hidManager.sendMouseClick(BluetoothHidManager.MOUSE_BTN_RIGHT)
                                } else {
                                    hidManager.sendMouseClick(BluetoothHidManager.MOUSE_BTN_LEFT)
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            touchpadContainer.addView(gestureSurface)

            // Center Hint Text inside Touchpad
            val hintTxt = TextView(context).apply {
                text = "Dokunmatik Alan · Tek Tık = Sol · 2 Parmak = Sağ / Kaydır"
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(4), dp(12), dp(4))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dp(6) }
            }
            touchpadHintView = hintTxt
            touchpadContainer.addView(hintTxt)

            // Top Controls inside Touchpad: [Air Mouse Toggle] & [Air Tut & Oynat / Clutch]
            val airBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = dp(8) }
            }

            val toggleAir = TextView(context).apply {
                text = "✈ Air Mouse: Kapalı"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    if (!airMouseController.isGyroSupported) {
                        showStatusToast("Bu cihazda jiroskop sensörü bulunamadı")
                        return@setOnClickListener
                    }
                    isAirMouseContinuous = !isAirMouseContinuous
                    syncAirMouseState()
                }
            }
            airToggleBtn = toggleAir
            airBar.addView(toggleAir)

            val holdAim = TextView(context).apply {
                text = "🎯 Tut & Oynat"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnTouchListener { _, ev ->
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            if (!airMouseController.isGyroSupported) {
                                showStatusToast("Bu cihazda jiroskop sensörü bulunamadı")
                                return@setOnTouchListener true
                            }
                            isAirMouseHoldAiming = true
                            syncAirMouseState()
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            isAirMouseHoldAiming = false
                            syncAirMouseState()
                            true
                        }
                        else -> false
                    }
                }
            }
            airHoldBtn = holdAim
            airBar.addView(holdAim)
            touchpadContainer.addView(airBar)
            updateAirMouseButtonsUi()

            padRow.addView(touchpadContainer)

            // Right Edge Dedicated 1-Finger Scroll Wheel Strip
            var scrollLastY = 0f
            var scrollAccum = 0f
            val scrollStrip = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#162136"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor("#2A3A57"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    dp(38),
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setOnTouchListener { _, ev ->
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            scrollLastY = ev.y
                            scrollAccum = 0f
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dy = ev.y - scrollLastY
                            scrollLastY = ev.y
                            scrollAccum -= dy / dp(12)
                            val steps = scrollAccum.toInt()
                            if (steps != 0) {
                                scrollAccum -= steps
                                hidManager.sendMouseMove(0, 0, wheel = steps.coerceIn(-12, 12))
                            }
                            true
                        }
                        else -> false
                    }
                }
                addView(TextView(context).apply {
                    text = "▲\n\n↕\n\n▼"
                    setTextColor(Color.parseColor("#00F5D4"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                })
            }
            padRow.addView(scrollStrip)
            addView(padRow)

            // Bottom Mouse Click Buttons Row: [Sol Tık] [Sürükle] [Orta] [Sağ Tık]
            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(46)
                )
            }

            val leftClickBtn = createMouseHardwareButton("Sol Tık", 2.0f, "#1E293B", "#F8FAFC") { isDown ->
                if (!hidManager.isHidHostConnected()) {
                    if (isDown) showStatusToast("PC Bluetooth bağlı değil")
                    return@createMouseHardwareButton
                }
                if (isDragLocked && isDown) {
                    isDragLocked = false
                    updateAirMouseButtonsUi()
                }
                hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, isDown)
            }
            btnRow.addView(leftClickBtn)

            val dragBtn = createKeyBaseView("Sürükle", 1.15f, "#131C2D", "#CBD5E1", 11.5f) {
                if (!hidManager.isHidHostConnected()) {
                    showStatusToast("PC Bluetooth bağlı değil")
                    return@createKeyBaseView
                }
                isDragLocked = !isDragLocked
                hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, isDragLocked)
                updateAirMouseButtonsUi()
            }
            dragLockBtn = dragBtn
            btnRow.addView(dragBtn)

            val middleClickBtn = createMouseHardwareButton("Orta", 0.9f, "#131C2D", "#CBD5E1") { isDown ->
                if (!hidManager.isHidHostConnected()) {
                    if (isDown) showStatusToast("PC Bluetooth bağlı değil")
                    return@createMouseHardwareButton
                }
                hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_MIDDLE, isDown)
            }
            btnRow.addView(middleClickBtn)

            val rightClickBtn = createMouseHardwareButton("Sağ Tık", 1.5f, "#1E293B", "#00F5D4") { isDown ->
                if (!hidManager.isHidHostConnected()) {
                    if (isDown) showStatusToast("PC Bluetooth bağlı değil")
                    return@createMouseHardwareButton
                }
                hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_RIGHT, isDown)
            }
            btnRow.addView(rightClickBtn)

            addView(btnRow)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMouseHardwareButton(
        label: String,
        weight: Float,
        bgHex: String,
        fgHex: String,
        onPressChange: (Boolean) -> Unit
    ): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(fgHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            background = buildKeySelectorDrawable(bgHex, "#334155", dp(8).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                weight
            ).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        onPressChange(true)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        onPressChange(false)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun syncAirMouseState() {
        val shouldRun = scanMode == ScanInputMode.MOUSE && (isAirMouseContinuous || isAirMouseHoldAiming)
        if (shouldRun) {
            if (isAirMouseContinuous && isAirMouseHoldAiming) {
                // Holding clutch while continuous mode is active temporarily pauses cursor for wrist reposition
                airMouseController.isClutchPaused = true
            } else {
                airMouseController.isClutchPaused = false
            }
            if (!airMouseController.isRunning) {
                airMouseController.start()
            }
        } else {
            airMouseController.isClutchPaused = false
            airMouseController.stop()
        }
        updateAirMouseButtonsUi()
    }

    private fun updateAirMouseButtonsUi() {
        val density = resources.displayMetrics.density
        val r = 14f * density

        airToggleBtn?.apply {
            val active = isAirMouseContinuous
            text = if (active) "✈ Air Mouse: AÇIK" else "✈ Air Mouse: Kapalı"
            setTextColor(if (active) Color.parseColor("#042F2E") else Color.parseColor("#E2E8F0"))
            background = GradientDrawable().apply {
                setColor(if (active) Color.parseColor("#00F5D4") else Color.parseColor("#1E293B"))
                cornerRadius = r
            }
        }

        airHoldBtn?.apply {
            text = when {
                isAirMouseContinuous && isAirMouseHoldAiming -> "⏸ Duraklatıldı"
                isAirMouseContinuous -> "⏸ Tut = Duraklat"
                isAirMouseHoldAiming -> "🎯 Aktif (Oynatın)"
                else -> "🎯 Tut & Oynat"
            }
            val highlighted = isAirMouseHoldAiming && !isAirMouseContinuous
            setTextColor(if (highlighted) Color.parseColor("#042F2E") else Color.parseColor("#CBD5E1"))
            background = GradientDrawable().apply {
                setColor(if (highlighted) Color.parseColor("#F59E0B") else Color.parseColor("#131C2D"))
                cornerRadius = r
                setStroke(density.toInt().coerceAtLeast(1), Color.parseColor("#334155"))
            }
        }

        dragLockBtn?.apply {
            setTextColor(if (isDragLocked) Color.parseColor("#042F2E") else Color.parseColor("#CBD5E1"))
            background = buildKeySelectorDrawable(
                if (isDragLocked) "#F59E0B" else "#131C2D",
                "#334155",
                7f * density
            )
        }
    }

    private fun updateTouchpadStatusHint() {
        val isConnected = hidManager.connectionStatus.value == HidConnectionStatus.CONNECTED
        touchpadHintView?.text = if (isConnected) {
            "Tek tık: Sol · 2 parmak: Sağ/Kaydır · Sağ kenar: Tekerlek"
        } else {
            "PC bağlı değil — Uygulamadan Bluetooth ile bilgisayara bağlanın"
        }
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

            // Row 4: [?123] [FN] [⇥] [,] [Boşluk] [.] [↵]
            addView(buildBottomClassicRow(ClassicPage.LETTERS))
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

            // Row 4
            addView(buildBottomClassicRow(ClassicPage.SYMBOLS))
        }
    }

    private fun buildFunctionNavGrid(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )

            // Row 1: Esc F1..F8 Del
            val row1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            row1.addView(createSpecialHidKey("Esc", 1.1f, BluetoothHidManager.KEY_ESCAPE, KeyEvent.KEYCODE_ESCAPE))
            row1.addView(createSpecialHidKey("F1", 1f, BluetoothHidManager.KEY_F1, KeyEvent.KEYCODE_F1))
            row1.addView(createSpecialHidKey("F2", 1f, BluetoothHidManager.KEY_F2, KeyEvent.KEYCODE_F2))
            row1.addView(createSpecialHidKey("F3", 1f, BluetoothHidManager.KEY_F3, KeyEvent.KEYCODE_F3))
            row1.addView(createSpecialHidKey("F4", 1f, BluetoothHidManager.KEY_F4, KeyEvent.KEYCODE_F4))
            row1.addView(createSpecialHidKey("F5", 1f, BluetoothHidManager.KEY_F5, KeyEvent.KEYCODE_F5))
            row1.addView(createSpecialHidKey("F11", 1f, BluetoothHidManager.KEY_F11, KeyEvent.KEYCODE_F11))
            row1.addView(createSpecialHidKey("F12", 1f, BluetoothHidManager.KEY_F12, KeyEvent.KEYCODE_F12))
            row1.addView(createSpecialHidKey("Del", 1.1f, BluetoothHidManager.KEY_DELETE, KeyEvent.KEYCODE_FORWARD_DEL))
            addView(row1)

            // Row 2: Sticky Modifiers + Navigation [Ctrl] [Alt] [Win] [Home] [End] [PgUp] [PgDn] [↑]
            val row2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val ctrl = createActionKeyButton("Ctrl", 1.15f) {
                isCtrlSticky = !isCtrlSticky
                refreshModifierUi()
            }
            ctrlKeyBtn = ctrl
            row2.addView(ctrl)

            val alt = createActionKeyButton("Alt", 1.0f) {
                isAltSticky = !isAltSticky
                refreshModifierUi()
            }
            altKeyBtn = alt
            row2.addView(alt)

            val win = createActionKeyButton("⊞", 1.0f) {
                if (outputTarget == OutputTarget.BLUETOOTH && !isCtrlSticky && !isAltSticky && !isWinSticky) {
                    isWinSticky = true
                    refreshModifierUi()
                } else {
                    isWinSticky = !isWinSticky
                    refreshModifierUi()
                }
            }
            winKeyBtn = win
            row2.addView(win)

            row2.addView(createSpecialHidKey("Home", 1.15f, BluetoothHidManager.KEY_HOME, KeyEvent.KEYCODE_MOVE_HOME))
            row2.addView(createSpecialHidKey("End", 1.0f, BluetoothHidManager.KEY_END, KeyEvent.KEYCODE_MOVE_END))
            row2.addView(createSpecialHidKey("PgUp", 1.15f, BluetoothHidManager.KEY_PAGE_UP, KeyEvent.KEYCODE_PAGE_UP))
            row2.addView(createSpecialHidKey("PgDn", 1.15f, BluetoothHidManager.KEY_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_DOWN))
            row2.addView(createSpecialHidKey("↑", 1.1f, BluetoothHidManager.KEY_UP, KeyEvent.KEYCODE_DPAD_UP))
            addView(row2)

            // Row 3: Quick PC Shortcuts + Arrows: [Ctrl+C] [Ctrl+V] [Ctrl+Z] [Alt+Tab] [←] [↓] [→] [⌫]
            val row3 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            row3.addView(createShortcutKey("Kopyala", 1.25f, BluetoothHidManager.KEY_C, BluetoothHidManager.MODIFIER_LEFT_CTRL))
            row3.addView(createShortcutKey("Yapıştır", 1.3f, BluetoothHidManager.KEY_V, BluetoothHidManager.MODIFIER_LEFT_CTRL))
            row3.addView(createShortcutKey("Geri Al", 1.2f, BluetoothHidManager.KEY_Z, BluetoothHidManager.MODIFIER_LEFT_CTRL))
            row3.addView(createShortcutKey("Alt+Tab", 1.25f, BluetoothHidManager.KEY_TAB, BluetoothHidManager.MODIFIER_LEFT_ALT))
            row3.addView(createSpecialHidKey("←", 1.0f, BluetoothHidManager.KEY_LEFT, KeyEvent.KEYCODE_DPAD_LEFT))
            row3.addView(createSpecialHidKey("↓", 1.0f, BluetoothHidManager.KEY_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            row3.addView(createSpecialHidKey("→", 1.0f, BluetoothHidManager.KEY_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT))
            row3.addView(createBackspaceKeyButton(1.1f))
            addView(row3)

            // Row 4
            addView(buildBottomClassicRow(ClassicPage.FUNCTION_NAV))
        }
    }

    private fun buildBottomClassicRow(page: ClassicPage): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )

            addView(
                createActionKeyButton(if (page == ClassicPage.SYMBOLS) "ABC" else "?123", 1.15f) {
                    setClassicPage(if (page == ClassicPage.SYMBOLS) ClassicPage.LETTERS else ClassicPage.SYMBOLS)
                }
            )
            addView(
                createActionKeyButton(if (page == ClassicPage.FUNCTION_NAV) "ABC" else "FN", 0.95f) {
                    setClassicPage(if (page == ClassicPage.FUNCTION_NAV) ClassicPage.LETTERS else ClassicPage.FUNCTION_NAV)
                }
            )
            addView(createActionKeyButton("⇥", 0.85f) { sendTabKey() })
            addView(createSymbolKeyButton(",", 0.75f))
            addView(
                createKeyBaseView("Boşluk", 3.0f, "#1E293B", "#94A3B8", 12.5f) {
                    sendClassicCharacter(' ')
                }
            )
            addView(createSymbolKeyButton(".", 0.75f))
            addView(
                createKeyBaseView("↵", 1.35f, "#00F5D4", "#042F2E", 16f, isAccent = true) {
                    sendEnterOrAction()
                }
            )
        }
    }

    private fun setClassicPage(page: ClassicPage) {
        classicPage = page
        lettersGrid?.visibility = if (page == ClassicPage.LETTERS) View.VISIBLE else View.GONE
        symbolsGrid?.visibility = if (page == ClassicPage.SYMBOLS) View.VISIBLE else View.GONE
        functionNavGrid?.visibility = if (page == ClassicPage.FUNCTION_NAV) View.VISIBLE else View.GONE
    }

    private fun currentStickyModifiersByte(): Byte {
        var mod = 0
        if (isCtrlSticky) mod = mod or BluetoothHidManager.MODIFIER_LEFT_CTRL.toInt()
        if (isAltSticky) mod = mod or BluetoothHidManager.MODIFIER_LEFT_ALT.toInt()
        if (isWinSticky) mod = mod or BluetoothHidManager.MODIFIER_LEFT_GUI.toInt()
        return mod.toByte()
    }

    private fun consumeStickyModifiersIfNeeded() {
        if (isCtrlSticky || isAltSticky || isWinSticky) {
            isCtrlSticky = false
            isAltSticky = false
            isWinSticky = false
            refreshModifierUi()
        }
    }

    private fun refreshModifierUi() {
        val density = resources.displayMetrics.density
        val radius = 7f * density

        fun styleMod(btn: TextView?, active: Boolean) {
            btn ?: return
            btn.setTextColor(if (active) Color.parseColor("#042F2E") else Color.parseColor("#CBD5E1"))
            btn.background = buildKeySelectorDrawable(
                if (active) "#00F5D4" else "#131C2D",
                "#334155",
                radius
            )
        }

        styleMod(ctrlKeyBtn, isCtrlSticky)
        styleMod(altKeyBtn, isAltSticky)
        styleMod(winKeyBtn, isWinSticky)
    }

    private fun createSpecialHidKey(
        label: String,
        weight: Float,
        hidKeyCode: Byte,
        androidKeyCode: Int
    ): TextView {
        return createKeyBaseView(label, weight, "#162033", "#E2E8F0", 11.5f) {
            val extraMods = currentStickyModifiersByte()
            if (outputTarget == OutputTarget.BLUETOOTH) {
                if (!hidManager.isHidHostConnected()) {
                    showStatusToast("PC Bluetooth bağlı değil")
                    return@createKeyBaseView
                }
                val btCfg = profileRepository.activeProfile.value.bluetoothKeyboard
                enqueueBtTask {
                    hidManager.sendHidKeyCombo(hidKeyCode, extraMods, btCfg.typingDelayMs)
                }
                consumeStickyModifiersIfNeeded()
            } else {
                sendDownUpKeyEvents(androidKeyCode)
                consumeStickyModifiersIfNeeded()
            }
        }
    }

    private fun createShortcutKey(
        label: String,
        weight: Float,
        hidKeyCode: Byte,
        modifiers: Byte
    ): TextView {
        return createKeyBaseView(label, weight, "#132A2E", "#00F5D4", 10.5f) {
            if (outputTarget == OutputTarget.BLUETOOTH) {
                if (!hidManager.isHidHostConnected()) {
                    showStatusToast("PC Bluetooth bağlı değil")
                    return@createKeyBaseView
                }
                val btCfg = profileRepository.activeProfile.value.bluetoothKeyboard
                enqueueBtTask {
                    hidManager.sendHidKeyCombo(hidKeyCode, modifiers, btCfg.typingDelayMs)
                }
            } else {
                val ic = currentInputConnection ?: return@createKeyBaseView
                when (hidKeyCode) {
                    BluetoothHidManager.KEY_C -> ic.performContextMenuAction(android.R.id.copy)
                    BluetoothHidManager.KEY_V -> ic.performContextMenuAction(android.R.id.paste)
                    BluetoothHidManager.KEY_Z -> ic.performContextMenuAction(android.R.id.undo)
                    else -> showStatusToast("Bu kısayol BT (PC) modunda çalışır")
                }
            }
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
        return createKeyBaseView(label, weight, "#131C2D", "#CBD5E1", 12.5f, onClick = onClick)
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

    private fun buildSegmentTab(
        iconRes: Int,
        title: String,
        onClick: () -> Unit
    ): Triple<LinearLayout, ImageView, TextView> {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val iconView = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(3) }
        }
        val labelView = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(4), dp(7), dp(4))
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(8), dp(4), dp(8), dp(4))
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
        if (mode == ScanInputMode.MOUSE && outputTarget != OutputTarget.BLUETOOTH) {
            outputTarget = OutputTarget.BLUETOOTH
            hidManager.refreshState()
            refreshTargetStyles()
            if (!hidManager.isHidHostConnected()) {
                showStatusToast("Mouse için PC Bluetooth bağlayın")
            }
        }
        refreshModePanelsAndTabs()
        if (mode == ScanInputMode.BARCODE) {
            previewView?.post { startCameraIfPermitted() } ?: startCameraIfPermitted()
        } else {
            duplicateBanner?.visibility = View.GONE
            stopCamera()
        }
        if (mode != ScanInputMode.MOUSE) {
            isAirMouseHoldAiming = false
            airMouseController.stop()
            if (isDragLocked) {
                isDragLocked = false
                hidManager.releaseAllMouseButtons()
            }
        }
        syncAirMouseState()
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
        val radius = 16f * density
        val isBarcode = scanMode == ScanInputMode.BARCODE
        val isClassic = scanMode == ScanInputMode.CLASSIC_KEYBOARD
        val isMouse = scanMode == ScanInputMode.MOUSE

        barcodePanel?.visibility = if (isBarcode) View.VISIBLE else View.GONE
        classicKeyboardPanel?.visibility = if (isClassic) View.VISIBLE else View.GONE
        mousePanel?.visibility = if (isMouse) View.VISIBLE else View.GONE

        fun styleTab(container: LinearLayout?, icon: ImageView?, label: TextView?, active: Boolean) {
            container?.background = if (active) {
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius }
            } else null
            icon?.setColorFilter(if (active) Color.parseColor("#0B111E") else Color.WHITE)
            label?.setTextColor(if (active) Color.parseColor("#0B111E") else Color.WHITE)
        }

        styleTab(barcodeTabContainer, barcodeTabIcon, barcodeTabLabel, isBarcode)
        styleTab(classicTabContainer, classicTabIcon, classicTabLabel, isClassic)
        styleTab(mouseTabContainer, mouseTabIcon, mouseTabLabel, isMouse)
    }

    private fun refreshTargetStyles() {
        val density = resources.displayMetrics.density
        val radius = 16f * density
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
        updateTouchpadStatusHint()
        rootLayout?.let { ViewCompat.requestApplyInsets(it) }
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
        @Suppress("DEPRECATION")
        window?.window?.let { win ->
            win.navigationBarColor = Color.parseColor("#0B111E")
            WindowCompat.setDecorFitsSystemWindows(win, true)
        }
        attachDecorInsetsListener()
        syncImeBottomInset(null)
        rootLayout?.post { syncImeBottomInset(null) }
        hidManager.refreshState()
        refreshTargetStyles()
        updateTouchpadStatusHint()
        if (scanMode == ScanInputMode.BARCODE) {
            previewView?.post { startCameraIfPermitted() } ?: startCameraIfPermitted()
        } else if (scanMode == ScanInputMode.MOUSE) {
            syncAirMouseState()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopBackspaceRepeat()
        isAirMouseHoldAiming = false
        airMouseController.stop()
        if (isDragLocked) {
            isDragLocked = false
            hidManager.releaseAllMouseButtons()
            updateAirMouseButtonsUi()
        }
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
        airMouseController.stop()
        hidManager.releaseAllMouseButtons()
        stopCamera()
        btHidQueue.close()
        serviceScope.cancel()
        cameraExecutor.shutdown()
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        super.onDestroy()
    }

    private fun enqueueBtTask(block: suspend () -> Unit) {
        btHidQueue.trySend(block)
    }

    private fun attachDecorInsetsListener() {
        val decor = window?.window?.decorView ?: return
        ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
            syncImeBottomInset(insets)
            insets
        }
        ViewCompat.requestApplyInsets(decor)
    }

    private fun syncImeBottomInset(passedInsets: WindowInsetsCompat?) {
        val root = rootLayout ?: return
        val decor = window?.window?.decorView
        val decorInsets = decor?.let { ViewCompat.getRootWindowInsets(it) }
        val rootInsets = ViewCompat.getRootWindowInsets(root) ?: passedInsets

        fun extractNavBottom(wi: WindowInsetsCompat?): Int {
            wi ?: return 0
            val visibleMask = WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.tappableElement() or
                WindowInsetsCompat.Type.mandatorySystemGestures()
            return maxOf(
                wi.getInsets(visibleMask).bottom,
                wi.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom
            )
        }

        val reportedBottom = maxOf(extractNavBottom(decorInsets), extractNavBottom(rootInsets))
        var ancestorPadBottom = 0
        var p = root.parent
        while (p is View) {
            ancestorPadBottom += p.paddingBottom
            if (p === decor) break
            p = p.parent
        }
        val extraNeeded = (reportedBottom - ancestorPadBottom).coerceAtLeast(0)
        root.updateBottomInset(extraNeeded)
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
                barcodeAnalyzer?.close()

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

                val preferredSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                val selector = when {
                    provider.hasCamera(preferredSelector) -> preferredSelector
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        val fallbackLens = provider.availableCameraInfos.firstOrNull()?.lensFacing
                        if (fallbackLens != null) {
                            CameraSelector.Builder().requireLensFacing(fallbackLens).build()
                        } else {
                            preferredSelector
                        }
                    }
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
                AppDebugLogger.e("IME-CAM", "IME kamera başlatılamadı", e)
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
            barcodeAnalyzer?.close()
            barcodeAnalyzer = null
        } catch (e: Exception) {
            AppDebugLogger.w("IME-CAM", "IME kamera durdurma hatası", e)
        }
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
        val isConsecutiveRepeat = BluetoothHidManager.isAccidentalConsecutiveDuplicate(
            rawBarcode = raw,
            lastSentRawBarcode = lastCommittedRaw,
            lastSentTimestampMs = lastCommittedAtMs,
            nowMs = now
        )
        if (prevCount > 0 && effectiveConfig.requireDuplicateConfirmation && isConsecutiveRepeat) {
            pendingConfirmRaw = raw
            pendingConfirmPayload = payload
            duplicateTextView?.text = "Art arda (${prevCount}x): $payload"
            duplicateBanner?.visibility = View.VISIBLE
            showStatusToast("Onay bekleniyor")
        } else {
            if (pendingConfirmRaw != raw) {
                pendingConfirmRaw = null
                pendingConfirmPayload = null
                duplicateBanner?.visibility = View.GONE
            }
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
            enqueueBtTask {
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
                    lastCommittedRaw = raw
                    lastCommittedAtMs = System.currentTimeMillis()
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
        lastCommittedRaw = raw
        lastCommittedAtMs = System.currentTimeMillis()

        val displayPreview = payload.replace("\t", " ⇥ ")
        showStatusToast("✓ Yazıldı (#$newCount): $displayPreview")
    }

    private fun sendClassicCharacter(ch: Char) {
        if (outputTarget == OutputTarget.BLUETOOTH) {
            if (!hidManager.isHidHostConnected()) {
                showStatusToast("PC Bluetooth bağlı değil")
                return
            }
            val extraMods = currentStickyModifiersByte()
            val btCfg = profileRepository.activeProfile.value.bluetoothKeyboard
            enqueueBtTask {
                hidManager.sendSingleCharacter(
                    ch = ch,
                    layout = btCfg.keyboardLayout,
                    typingDelayMs = btCfg.typingDelayMs,
                    extraModifiers = extraMods
                )
            }
            consumeStickyModifiersIfNeeded()
            return
        }
        currentInputConnection?.commitText(ch.toString(), 1)
        consumeStickyModifiersIfNeeded()
    }

    private fun sendTabKey() {
        if (outputTarget == OutputTarget.BLUETOOTH) {
            if (!hidManager.isHidHostConnected()) {
                showStatusToast("PC Bluetooth bağlı değil")
                return
            }
            val extraMods = currentStickyModifiersByte()
            val btCfg = profileRepository.activeProfile.value.bluetoothKeyboard
            enqueueBtTask {
                hidManager.sendSingleHidKey(
                    keyCode = BluetoothHidManager.KEY_TAB,
                    modifier = extraMods,
                    typingDelayMs = btCfg.typingDelayMs
                )
            }
            consumeStickyModifiersIfNeeded()
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
            enqueueBtTask {
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
            val extraMods = currentStickyModifiersByte()
            val btCfg = profileRepository.activeProfile.value.bluetoothKeyboard
            enqueueBtTask {
                hidManager.sendSingleHidKey(
                    keyCode = BluetoothHidManager.KEY_ENTER,
                    modifier = extraMods,
                    typingDelayMs = btCfg.typingDelayMs
                )
            }
            consumeStickyModifiersIfNeeded()
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

    private class InsetAwareKeyboardRootLayout(
        context: Context,
        private val contentHeightPx: Int
    ) : FrameLayout(context) {
        private var bottomInsetPx: Int = 0

        fun updateBottomInset(newBottomPx: Int) {
            val clamped = newBottomPx.coerceAtLeast(0)
            if (clamped != bottomInsetPx) {
                bottomInsetPx = clamped
                setPadding(0, 0, 0, clamped)
                requestLayout()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val totalHeight = contentHeightPx + bottomInsetPx
            val exactHeightSpec = MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, exactHeightSpec)
        }
    }
}
