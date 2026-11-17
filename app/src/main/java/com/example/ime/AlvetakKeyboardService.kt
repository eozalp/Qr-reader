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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
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
import com.example.bluetooth.BluetoothHidManager
import com.example.camera.QrCodeAnalyzer
import com.example.camera.QrTracker
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
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local Android InputMethodService (IME) allowing the user to select "ALVETAK QR Klavye"
 * from Android's keyboard switcher button inside Chrome, Excel Mobile, Notes, or any web form.
 * Runs a compact live camera barcode scanner in the bottom keyboard pane and types parsed
 * barcodes / variables / Tab / Enter directly into the active input field above.
 */
class AlvetakKeyboardService : InputMethodService(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var profileRepository: ProfileRepository
    private val qrTracker = QrTracker()
    private val hidPayloadHelper = BluetoothHidManager(null)
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var previewView: PreviewView? = null
    private var analyzer: QrCodeAnalyzer? = null

    private var isPaused = false
    private var isTorchOn = false
    private var currentSendTargetOverride: KeyboardSendTarget? = null

    // Batch duplicate guard inside local keyboard
    private val activeInFrameRaw = mutableSetOf<String>()
    private val sentCounts = mutableMapOf<String, Int>()
    private val lastSeenMs = mutableMapOf<String, Long>()
    private val dismissedUntilGone = mutableSetOf<String>()
    private var pendingConfirmRaw: String? = null
    private var pendingConfirmPayload: String? = null
    private var lastSubmittedPayload: String? = null
    private var lastSubmittedRaw: String? = null

    // UI References
    private var statusTextView: TextView? = null
    private var profileTextView: TextView? = null
    private var modeChipBtn: Button? = null
    private var pauseBtn: Button? = null
    private var torchBtn: Button? = null
    private var permissionContainer: LinearLayout? = null
    private var duplicateBanner: LinearLayout? = null
    private var duplicateTextView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        profileRepository = ProfileRepository(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090D16"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(290)
            )
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        // --- 1. Top Control Strip (Profile, Target Mode, Torch, Pause, Switch Keyboard) ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            )
        }

        val profilePill = TextView(this).apply {
            text = profileRepository.activeProfile.value.name
            setTextColor(Color.parseColor("#00F5D4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        profileTextView = profilePill
        topBar.addView(profilePill)

        val modeBtn = createChipButton("Mod: Tümü") {
            val active = profileRepository.activeProfile.value
            val current = currentSendTargetOverride ?: active.bluetoothKeyboard.sendTarget
            val next = when (current) {
                KeyboardSendTarget.WHOLE_RAW -> KeyboardSendTarget.MULTIPLE_VARIABLES
                KeyboardSendTarget.MULTIPLE_VARIABLES -> KeyboardSendTarget.FORMULA_RESULT
                KeyboardSendTarget.FORMULA_RESULT -> KeyboardSendTarget.SINGLE_VARIABLE
                else -> KeyboardSendTarget.WHOLE_RAW
            }
            currentSendTargetOverride = next
            updateModeLabel(active)
        }
        modeChipBtn = modeBtn
        topBar.addView(modeBtn)

        val flashBtn = createChipButton("Flaş") {
            isTorchOn = !isTorchOn
            boundCamera?.cameraControl?.enableTorch(isTorchOn)
            torchBtn?.setTextColor(if (isTorchOn) Color.parseColor("#F59E0B") else Color.WHITE)
        }
        torchBtn = flashBtn
        topBar.addView(flashBtn)

        val pauseToggle = createChipButton("Duraklat") {
            isPaused = !isPaused
            analyzer?.isPaused = isPaused
            pauseBtn?.text = if (isPaused) "Devam" else "Duraklat"
            statusTextView?.text = if (isPaused) "Tarama duraklatıldı" else "Barkod bekleniyor..."
        }
        pauseBtn = pauseToggle
        topBar.addView(pauseToggle)

        val switchKbBtn = createChipButton("🌐") {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }
        topBar.addView(switchKbBtn)

        root.addView(topBar)

        // --- 2. Duplicate Confirmation Strip (Hidden unless same barcode rescanned in batch) ---
        val dupStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A1B05"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        val dupLabel = TextView(this).apply {
            text = "Tekrar okundu"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        duplicateTextView = dupLabel
        dupStrip.addView(dupLabel)

        val confirmBtn = createActionKey("Tekrar Yaz (+1)", "#00F5D4", "#00382E") {
            val raw = pendingConfirmRaw
            val payload = pendingConfirmPayload
            if (raw != null && payload != null) {
                commitPayloadToEditor(raw, payload, profileRepository.activeProfile.value)
            }
            pendingConfirmRaw = null
            pendingConfirmPayload = null
            duplicateBanner?.visibility = View.GONE
        }
        dupStrip.addView(confirmBtn)

        val skipBtn = createActionKey("Atla", "#334155", "#FFFFFF") {
            pendingConfirmRaw?.let { dismissedUntilGone.add(it) }
            pendingConfirmRaw = null
            pendingConfirmPayload = null
            duplicateBanner?.visibility = View.GONE
        }
        dupStrip.addView(skipBtn)

        duplicateBanner = dupStrip
        root.addView(dupStrip)

        // --- 3. Main Area: Live Camera Scanner (Left) + Action Keypad Column (Right) ---
        val bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(6) }
        }

        // Camera Box
        val cameraContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111827"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            ).apply { marginEnd = dp(8) }
        }

        val camPreview = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewView = camPreview
        cameraContainer.addView(camPreview)

        // Aiming Reticle Overlay + Status Pill at Bottom of Camera
        val statusPill = TextView(this).apply {
            text = "Kamerayı barkoda tutun — okunan veri forma yazılır"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC090D16"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
        }
        statusTextView = statusPill
        cameraContainer.addView(statusPill)

        // Camera Permission Missing Overlay
        val permBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EE090D16"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val permLabel = TextView(this).apply {
            text = "Kamera taraması için uygulama içi kamera izni gerekli."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        val openAppBtn = createActionKey("İzin Ver / Uygulamayı Aç", "#00F5D4", "#00382E") {
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(launch)
        }
        permBox.addView(permLabel)
        permBox.addView(openAppBtn)
        permissionContainer = permBox
        cameraContainer.addView(permBox)

        bodyRow.addView(cameraContainer)

        // Right Keypad Column (Tab, Enter, Backspace, Resend Last)
        val keysCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                dp(92),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        keysCol.addView(createKeypadButton("Tab ⇥", "#1E293B", "#00F5D4") {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
        })
        keysCol.addView(createKeypadButton("Enter ↵", "#00F5D4", "#00382E") {
            sendEnterOrAction()
        })
        keysCol.addView(createKeypadButton("Sil ⌫", "#1E293B", "#F87171") {
            currentInputConnection?.deleteSurroundingText(1, 0)
        })
        keysCol.addView(createKeypadButton("Tekrar", "#1E293B", "#FBBF24") {
            val raw = lastSubmittedRaw
            val pay = lastSubmittedPayload
            if (raw != null && pay != null) {
                commitPayloadToEditor(raw, pay, profileRepository.activeProfile.value)
            }
        })

        bodyRow.addView(keysCol)
        root.addView(bodyRow)

        updateModeLabel(profileRepository.activeProfile.value)
        return root
    }

    private fun createChipButton(label: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        val padH = (8 * density).toInt()
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#162033"))
                cornerRadius = (10 * density)
                setStroke((1 * density).toInt(), Color.parseColor("#2A3B55"))
            }
            setPadding(padH, 0, padH, 0)
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (34 * density).toInt()
            ).apply { marginStart = (4 * density).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun createActionKey(label: String, bgHex: String, fgHex: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor(fgHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = (8 * density)
            }
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (34 * density).toInt()
            ).apply { marginStart = (6 * density).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun createKeypadButton(label: String, bgHex: String, fgHex: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor(fgHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = (10 * density)
                setStroke((1 * density).toInt(), Color.parseColor("#2A3B55"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { bottomMargin = (5 * density).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun updateModeLabel(profile: QrProfile) {
        val target = currentSendTargetOverride ?: profile.bluetoothKeyboard.sendTarget
        val shortLabel = when (target) {
            KeyboardSendTarget.WHOLE_RAW -> "Ham"
            KeyboardSendTarget.FORMULA_RESULT -> "Sonuç"
            KeyboardSendTarget.SINGLE_VARIABLE -> "Tek"
            KeyboardSendTarget.MULTIPLE_VARIABLES -> "Çoklu"
            KeyboardSendTarget.CUSTOM_TEMPLATE -> "Şablon"
        }
        profileTextView?.text = profile.name
        modeChipBtn?.text = "Mod: $shortLabel"
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        serviceScope.launch {
            profileRepository.reloadFromDisk()
            updateModeLabel(profileRepository.activeProfile.value)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        startCameraIfPermitted()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopCamera()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        stopCamera()
        serviceScope.cancel()
        cameraExecutor.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
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
                val newAnalyzer = QrCodeAnalyzer { detectedList ->
                    mainHandler.post {
                        handleDetectedCodes(detectedList)
                    }
                }.apply {
                    isPaused = this@AlvetakKeyboardService.isPaused
                    updateBarcodeFormats(
                        preset = activeProfile.tracking.scanPreset,
                        customFormats = activeProfile.tracking.enabledFormats,
                        enableInverted = activeProfile.tracking.enableInvertedDetection
                    )
                }
                analyzer = newAnalyzer

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, newAnalyzer)
                    }

                boundCamera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                statusTextView?.text = "Kamera açılamadı: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            isTorchOn = false
            cameraProvider?.unbindAll()
            boundCamera = null
        } catch (_: Exception) {}
    }

    private fun handleDetectedCodes(detected: List<RawDetectedCode>) {
        if (isPaused) return
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
        updateModeLabel(activeProfile)

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
            statusTextView?.text = "Onay bekleniyor: $payload"
        } else {
            commitPayloadToEditor(raw, payload, activeProfile)
        }
    }

    private fun commitPayloadToEditor(raw: String, payload: String, profile: QrProfile) {
        val ic = currentInputConnection ?: return

        // If payload contains tabs (e.g. SKU \t Fiyat), split and send real KEYCODE_TAB between fields
        // so Chrome/mobile web forms and spreadsheets jump cleanly from one box to the next!
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
        lastSubmittedRaw = raw
        lastSubmittedPayload = payload

        val displayPreview = payload.replace("\t", " ⇥ ")
        statusTextView?.text = "✓ Yazıldı (#$newCount): $displayPreview"
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
}
