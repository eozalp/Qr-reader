package com.example

import android.graphics.RectF
import com.example.camera.QrTracker
import com.example.camera.RawDetectedCode
import com.example.data.ProfileRepository
import com.example.engine.*
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrEngineComprehensiveTest {

    // --- QR Parsing Tests ---

    @Test
    fun testDefaultDelimiterParsing() {
        val rules = QrParsingRules(delimiter = "-")
        val outcome = QrParser.parse("10-20-30-40", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(10.0, vars["a"]!!, 1e-6)
        assertEquals(20.0, vars["b"]!!, 1e-6)
        assertEquals(30.0, vars["c"]!!, 1e-6)
        assertEquals(40.0, vars["d"]!!, 1e-6)
    }

    @Test
    fun testCustomCommaDelimiterAndAliases() {
        val rules = QrParsingRules(
            delimiter = ",",
            variables = listOf(
                VariableDefinition(position = 0, name = "price", alias = "p"),
                VariableDefinition(position = 1, name = "qty", alias = "q")
            )
        )
        val outcome = QrParser.parse("49.95,3", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(49.95, vars["price"]!!, 1e-6)
        assertEquals(49.95, vars["p"]!!, 1e-6)
        assertEquals(3.0, vars["qty"]!!, 1e-6)
        assertEquals(3.0, vars["q"]!!, 1e-6)
    }

    @Test
    fun testOptionalFieldWithDefault() {
        val rules = QrParsingRules(
            delimiter = "-",
            variables = listOf(
                VariableDefinition(position = 0, name = "a"),
                VariableDefinition(position = 1, name = "b", isOptional = true, defaultValue = "99")
            )
        )
        val outcome = QrParser.parse("42", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(42.0, vars["a"]!!, 1e-6)
        assertEquals(99.0, vars["b"]!!, 1e-6)
    }

    @Test
    fun testInvalidInputHandledSafely() {
        val rules = QrParsingRules(delimiter = "-")
        val emptyOutcome = QrParser.parse("", rules)
        assertTrue(emptyOutcome is QrParseOutcome.Error)

        val nonNumericOutcome = QrParser.parse("10-abc-30-40", rules)
        assertTrue(nonNumericOutcome is QrParseOutcome.Error)
    }

    @Test
    fun testMinMaxBoundsValidation() {
        val rules = QrParsingRules(
            delimiter = "-",
            variables = listOf(
                VariableDefinition(position = 0, name = "val", minValue = 10.0, maxValue = 50.0)
            )
        )
        assertTrue(QrParser.parse("5", rules) is QrParseOutcome.Error)
        assertTrue(QrParser.parse("60", rules) is QrParseOutcome.Error)
        assertTrue(QrParser.parse("25", rules) is QrParseOutcome.Success)
    }

    // --- Math Engine Tests ---

    private val evaluator = MathExpressionEvaluator()

    @Test
    fun testBasicArithmeticAndPrecedence() {
        val vars = mapOf("a" to 10.0, "b" to 20.0, "c" to 30.0, "d" to 40.0)
        val res = evaluator.evaluate("a + b * c", vars)
        assertTrue(res is MathResult.Success)
        // 10 + (20 * 30) = 610
        assertEquals(610.0, (res as MathResult.Success).value, 1e-6)
    }

    @Test
    fun testParenthesesAndDivision() {
        val vars = mapOf("a" to 10.0, "b" to 20.0, "c" to 30.0, "d" to 40.0)
        val res = evaluator.evaluate("(a + b) / (d - c)", vars)
        assertTrue(res is MathResult.Success)
        // (10 + 20) / (40 - 30) = 30 / 10 = 3
        assertEquals(3.0, (res as MathResult.Success).value, 1e-6)
    }

    @Test
    fun testPowerAndModulo() {
        val vars = mapOf("x" to 3.0, "y" to 4.0)
        val resPower = evaluator.evaluate("x ^ 2 + y ^ 2", vars)
        assertTrue(resPower is MathResult.Success)
        // 9 + 16 = 25
        assertEquals(25.0, (resPower as MathResult.Success).value, 1e-6)

        val resMod = evaluator.evaluate("10 % 3", emptyMap())
        assertTrue(resMod is MathResult.Success)
        assertEquals(1.0, (resMod as MathResult.Success).value, 1e-6)
    }

    @Test
    fun testPercentageCalculation() {
        val vars = mapOf("price" to 200.0)
        val res = evaluator.evaluate("price * 15%", vars)
        assertTrue(res is MathResult.Success)
        assertEquals(30.0, (res as MathResult.Success).value, 1e-6)
    }

    @Test
    fun testMathematicalFunctions() {
        val vars = mapOf("a" to 10.0, "b" to 30.0, "c" to 20.0)
        val resMax = evaluator.evaluate("max(a, b, c)", vars)
        assertTrue(resMax is MathResult.Success)
        assertEquals(30.0, (resMax as MathResult.Success).value, 1e-6)

        val resMin = evaluator.evaluate("min(a, b, c)", vars)
        assertTrue(resMin is MathResult.Success)
        assertEquals(10.0, (resMin as MathResult.Success).value, 1e-6)

        val resAvg = evaluator.evaluate("avg(a, b, c)", vars)
        assertTrue(resAvg is MathResult.Success)
        assertEquals(20.0, (resAvg as MathResult.Success).value, 1e-6)

        val resSqrt = evaluator.evaluate("sqrt(144)", emptyMap())
        assertTrue(resSqrt is MathResult.Success)
        assertEquals(12.0, (resSqrt as MathResult.Success).value, 1e-6)

        val resAbs = evaluator.evaluate("abs(-45.5)", emptyMap())
        assertTrue(resAbs is MathResult.Success)
        assertEquals(45.5, (resAbs as MathResult.Success).value, 1e-6)
    }

    @Test
    fun testDivisionByZeroHandledGracefully() {
        val res = evaluator.evaluate("10 / 0", emptyMap())
        assertTrue(res is MathResult.Error)
        assertEquals("Division by zero", (res as MathResult.Error).message)
    }

    // --- Result Formatting Tests ---

    @Test
    fun testResultFormattingCurrency() {
        val formatting = ResultFormatting(
            decimalPrecision = 2,
            prefix = "₺",
            postfix = " TL",
            roundingMode = AppRoundingMode.ROUND_HALF_UP
        )
        val result = ResultFormatter.format(100.0, formatting)
        assertEquals("₺100.00 TL", result)
    }

    @Test
    fun testThousandsSeparatorAndRounding() {
        val formatting = ResultFormatting(
            decimalPrecision = 1,
            prefix = "$",
            thousandsSeparator = ",",
            roundingMode = AppRoundingMode.CEILING
        )
        val result = ResultFormatter.format(12345.21, formatting)
        assertEquals("$12,345.3", result)
    }

    @Test
    fun testNegativeFormatting() {
        val formatting = ResultFormatting(
            decimalPrecision = 2,
            prefix = "$",
            negativeFormat = "(x)"
        )
        val result = ResultFormatter.format(-50.0, formatting)
        assertEquals("($50.00)", result)
    }

    // --- Multi-QR Processing & Profile Serialization ---

    @Test
    fun testMultiQrTrackingEngine() {
        val tracker = QrTracker()
        val profile = ProfileRepository.createDefaultPresets().first()

        val codes = listOf(
            RawDetectedCode("10-20-30-40", RectF(0f, 0f, 100f, 100f)),
            RawDetectedCode("5-10-15-20", RectF(200f, 200f, 300f, 300f)),
            RawDetectedCode("100-200-300-400", RectF(400f, 400f, 500f, 500f))
        )

        val tracked = tracker.update(codes, profile, currentTimeMs = 1000L)
        assertEquals(3, tracked.size)

        // Verify independent calculations
        val results = tracked.map { (it.result.status as CalculationStatus.Success).resultNumber }
        assertTrue(results.contains(100.0))  // 10+20+30+40
        assertTrue(results.contains(50.0))   // 5+10+15+20
        assertTrue(results.contains(1000.0)) // 100+200+300+400
    }

    @Test
    fun testProfileJsonRoundTrip() {
        val preset = ProfileRepository.createDefaultPresets().first()
        val json = preset.toJson()
        val reconstructed = QrProfile.fromJson(json)

        assertEquals(preset.id, reconstructed.id)
        assertEquals(preset.name, reconstructed.name)
        assertEquals(preset.formula.expression, reconstructed.formula.expression)
        assertEquals(preset.parsingRules.delimiter, reconstructed.parsingRules.delimiter)
        assertEquals(preset.formatting.prefix, reconstructed.formatting.prefix)
        assertEquals(preset.formatting.postfix, reconstructed.formatting.postfix)
    }
}
