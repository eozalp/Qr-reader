package com.example

import android.graphics.RectF
import androidx.compose.ui.unit.Density
import com.example.camera.QrTracker
import com.example.camera.RawDetectedCode
import com.example.data.BackupTransferManager
import com.example.engine.MathExpressionEvaluator
import com.example.engine.MathResult
import com.example.engine.QrParseOutcome
import com.example.engine.QrParser
import com.example.engine.ResultFormatter
import com.example.engine.SmartTagWizardAnalyzer
import com.example.engine.WizardFormulaPreset
import com.example.model.AppRoundingMode
import com.example.model.CalculationStatus
import com.example.model.CalloutLayoutMode
import com.example.model.FieldDataType
import com.example.model.GlobalVariable
import com.example.model.OverlayAppearance
import com.example.model.ParsedQrResult
import com.example.model.QrParsingMode
import com.example.model.QrParsingRules
import com.example.model.QrProfile
import com.example.model.ResultFormatting
import com.example.model.TrackedQr
import com.example.model.VariableDefinition
import com.example.ui.CalloutLayoutSolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPOutputStream

class BugHuntRegressionTest {

    private fun withTurkishLocale(block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun mathEvaluator_uppercaseFunctionsAndIdentifiersWorkUnderTurkishLocale() = withTurkishLocale {
        val evaluator = MathExpressionEvaluator()
        val result = evaluator.evaluate("IF(MIN(10, 5) == 5, PI, 0)", emptyMap())
        assertTrue(result is MathResult.Success)
        assertEquals(Math.PI, (result as MathResult.Success).value, 0.0001)

        val idxResult = evaluator.evaluate("INDEX_OF(\"ABC\", \"B\")", emptyMap())
        assertTrue(idxResult is MathResult.Success)
        assertEquals(1.0, (idxResult as MathResult.Success).value, 0.0001)
    }

    @Test
    fun mathEvaluator_coercesCommaDecimalStringsAndGuardsDateOverflow() {
        val evaluator = MathExpressionEvaluator()
        val product = evaluator.evaluate(
            expression = "price * 2",
            variables = emptyMap(),
            stringVariables = mapOf("price" to "12,50")
        )
        assertTrue(product is MathResult.Success)
        assertEquals(25.0, (product as MathResult.Success).value, 0.0001)

        val sum = evaluator.evaluate(
            expression = "num(price) + 2.5",
            variables = emptyMap(),
            stringVariables = mapOf("price" to "12,50")
        )
        assertTrue(sum is MathResult.Success)
        assertEquals(15.0, (sum as MathResult.Success).value, 0.0001)

        // Huge epoch day should return a clean MathResult.Error rather than crashing with DateTimeException
        val yearRes = evaluator.evaluate("year(9999999999999)", emptyMap())
        assertTrue(yearRes is MathResult.Error)
    }

    @Test
    fun qrParser_keyValueTurkishLocaleAndMultiCharSeparator() = withTurkishLocale {
        val rules = QrParsingRules(
            parsingMode = QrParsingMode.KEY_VALUE,
            delimiter = "&",
            keyValueSeparator = ":=",
            variables = listOf(
                VariableDefinition(name = "id", extractionKey = "ID", dataType = FieldDataType.DOUBLE),
                VariableDefinition(name = "active", extractionKey = "ACTIVE", dataType = FieldDataType.BOOLEAN),
                VariableDefinition(name = "kod", extractionKey = "KOD", dataType = FieldDataType.STRING)
            )
        )

        val outcome = QrParser.parse("ID:=42&ACTIVE:=YANLIS&KOD:=ABC-1", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val parsed = outcome as QrParseOutcome.Success
        assertEquals(42.0, parsed.numericVariables["id"]!!, 0.0001)
        assertEquals(0.0, parsed.numericVariables["active"]!!, 0.0001)
        assertEquals("ABC-1", parsed.stringVariables["kod"])
    }

    @Test
    fun qrParser_preservesBlankManualStringDefaultValue() {
        val rules = QrParsingRules(
            parsingMode = QrParsingMode.DELIMITER,
            delimiter = "-",
            variables = listOf(
                VariableDefinition(name = "a", position = 0, dataType = FieldDataType.DOUBLE),
                VariableDefinition(
                    name = "not",
                    dataType = FieldDataType.STRING,
                    isManual = true,
                    defaultValue = ""
                )
            )
        )

        val outcome = QrParser.parse("100", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val parsed = outcome as QrParseOutcome.Success
        assertEquals("", parsed.stringVariables["not"])
    }

    @Test
    fun qrParser_rejectsOversizedGzipProfileBomb() {
        val hugePayload = "A".repeat(700_000)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(hugePayload.toByteArray(Charsets.UTF_8)) }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(baos.toByteArray())
        val rawQr = "QRCALCPROFILE:GZ:$encoded"

        assertNull(QrParser.detectEmbeddedProfile(rawQr))
    }

    @Test
    fun resultFormatter_negativeCeilingFloorAndNegativeZero() {
        val ceilFmt = ResultFormatting(
            decimalPrecision = 0,
            roundingMode = AppRoundingMode.CEILING
        )
        val floorFmt = ResultFormatting(
            decimalPrecision = 0,
            roundingMode = AppRoundingMode.FLOOR
        )
        val zeroFmt = ResultFormatting(
            decimalPrecision = 2,
            decimalSeparator = "."
        )

        assertEquals("-1", ResultFormatter.format(-1.2, ceilFmt))
        assertEquals("-2", ResultFormatter.format(-1.2, floorFmt))
        assertEquals("0.00", ResultFormatter.format(-0.0001, zeroFmt))
    }

    @Test
    fun smartTagWizard_deduplicatesComputedSonucVariableName() {
        val profile = SmartTagWizardAnalyzer.buildProfileFromWizard(
            profileName = "Test",
            delimiter = "-",
            tokenLabels = listOf("Sonuç", "Miktar"),
            sampleTokens = listOf("10", "20"),
            formulaPreset = WizardFormulaPreset.SUM,
            currencyPrefix = ""
        )

        val names = profile.parsingRules.variables.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue(names.contains("sonuc"))
        assertTrue(names.contains("sonuc_2"))

        val tracker = QrTracker()
        val parsed = tracker.processQr("10-20", profile)
        assertTrue(parsed.status is CalculationStatus.Success)
        assertEquals(30.0, (parsed.status as CalculationStatus.Success).resultNumber, 0.0001)
    }

    @Test
    fun qrProfileFromJson_handlesBlankIdsAndNullOptionalFields() {
        val json = JSONObject(
            """
            {
              "id": "",
              "name": "Minimal",
              "tableConfig": null,
              "parsingRules": {
                "variables": [
                  {
                    "id": "",
                    "name": "x",
                    "minValue": null,
                    "maxValue": null,
                    "roundingMode": null
                  }
                ]
              },
              "formula": null,
              "formatting": null,
              "overlay": null,
              "tracking": null,
              "bluetoothKeyboard": null
            }
            """.trimIndent()
        )

        val profile = QrProfile.fromJson(json)
        assertTrue(profile.id.isNotBlank())
        assertEquals("Minimal", profile.name)
        assertEquals(1, profile.parsingRules.variables.size)
        val variable = profile.parsingRules.variables.first()
        assertTrue(variable.id.isNotBlank())
        assertNull(variable.minValue)
        assertNull(variable.maxValue)
        assertNull(variable.roundingMode)
    }

    @Test
    fun calloutLayoutSolver_doesNotCrashWhenCardExceedsViewportWidth() {
        val dummyItem = TrackedQr(
            id = "qr-1",
            rawContent = "10-20",
            currentBox = RectF(10f, 10f, 90f, 90f),
            targetBox = RectF(10f, 10f, 90f, 90f),
            result = ParsedQrResult(
                rawText = "10-20",
                status = CalculationStatus.Success(30.0, "30")
            ),
            lastSeenMs = 1000L,
            firstSeenMs = 1000L
        )
        val density = Density(density = 2f)

        CalloutLayoutMode.entries.forEach { mode ->
            val config = OverlayAppearance(
                scale = 3.0f,
                autoWidth = false,
                customWidth = 400f,
                autoHeight = false,
                customHeight = 500f,
                calloutMode = mode,
                autoAvoidEdges = true
            )
            val solved = CalloutLayoutSolver.solve(
                items = listOf(dummyItem),
                config = config,
                screenWidthPx = 720f,
                screenHeightPx = 900f,
                density = density
            )
            assertEquals(1, solved.size)
            assertFalse(solved.first().cardCenterX.isNaN())
            assertFalse(solved.first().cardCenterY.isNaN())
        }
    }

    @Test
    fun backupTransferManager_sanitizeFileNameBlocksTraversal() {
        val unixTraversal = BackupTransferManager.sanitizeFileName("../../etc/passwd")
        assertFalse(unixTraversal.contains("/"))
        assertFalse(unixTraversal.contains(".."))
        assertEquals("passwd", unixTraversal)

        val winTraversal = BackupTransferManager.sanitizeFileName("..\\..\\evil.jpg")
        assertFalse(winTraversal.contains("\\"))
        assertFalse(winTraversal.contains(".."))
        assertEquals("evil.jpg", winTraversal)

        val emptyFallback = BackupTransferManager.sanitizeFileName("///...///", fallbackPrefix = "img")
        assertTrue(emptyFallback.startsWith("img_"))
    }

    @Test
    fun qrTracker_recalculatesImmediatelyWhenGlobalVariableChanges() {
        val tracker = QrTracker()
        val profile = QrProfile(
            parsingRules = QrParsingRules(
                parsingMode = QrParsingMode.DELIMITER,
                delimiter = "-",
                variables = listOf(
                    VariableDefinition(name = "lt", position = 0, dataType = FieldDataType.DOUBLE),
                    VariableDefinition(
                        name = "tutar",
                        dataType = FieldDataType.DOUBLE,
                        isComputed = true,
                        computationFormula = "lt * @motorin",
                        isPrimaryOutput = true
                    )
                )
            )
        )

        val firstGlobals = listOf(GlobalVariable(name = "motorin", value = "40"))
        val first = tracker.update(
            detectedScreenBoxes = listOf(RawDetectedCode("10", RectF(0f, 0f, 100f, 100f))),
            profile = profile,
            globalVariables = firstGlobals,
            currentTimeMs = 1000L
        )
        val firstStatus = first.first().result.status
        assertTrue(firstStatus is CalculationStatus.Success)
        assertEquals(400.0, (firstStatus as CalculationStatus.Success).resultNumber, 0.0001)

        // Updating a global variable must immediately invalidate cached computation
        val updatedGlobals = listOf(GlobalVariable(name = "motorin", value = "50"))
        val second = tracker.update(
            detectedScreenBoxes = listOf(RawDetectedCode("10", RectF(0f, 0f, 100f, 100f))),
            profile = profile,
            globalVariables = updatedGlobals,
            currentTimeMs = 1100L
        )
        val secondStatus = second.first().result.status
        assertTrue(secondStatus is CalculationStatus.Success)
        assertEquals(500.0, (secondStatus as CalculationStatus.Success).resultNumber, 0.0001)
    }

    @Test
    fun touchpadTimerWakeChannel_closeDuringIdleReceiveCatchingDoesNotThrow() = runBlocking {
        val wakeChannel = Channel<Unit>(Channel.CONFLATED)
        var exitedCleanly = false
        var uncaught: Throwable? = null

        val job = launch {
            try {
                while (true) {
                    val result = wakeChannel.receiveCatching()
                    if (result.isClosed) {
                        exitedCleanly = true
                        break
                    }
                }
            } catch (t: Throwable) {
                uncaught = t
            }
        }

        // Let the coroutine suspend on receiveCatching(), then close just like DisposableEffect.onDispose
        yield()
        wakeChannel.close()
        job.join()

        assertNull("Closing timerWakeChannel must never throw ClosedReceiveChannelException", uncaught)
        assertTrue("Timer loop must exit cleanly when channel is closed", exitedCleanly)
    }
}
