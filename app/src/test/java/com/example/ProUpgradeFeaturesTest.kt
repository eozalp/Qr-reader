package com.example

import android.graphics.RectF
import com.example.bluetooth.OneEuroFilter
import com.example.camera.QrTracker
import com.example.camera.RawDetectedCode
import com.example.camera.TargetReticleFilter
import com.example.engine.ScanCartAggregator
import com.example.engine.SmartTagWizardAnalyzer
import com.example.engine.WizardFormulaPreset
import com.example.model.CalculationStatus
import com.example.model.ScanHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProUpgradeFeaturesTest {

    @Test
    fun oneEuroFilter_dampensLowSpeedJitterAndTracksFastMotion() {
        val filter = OneEuroFilter(minCutoffHz = 2.0f, beta = 0.08f, dCutoffHz = 1.0f)

        // Feed alternating +-0.08 rad/s hand tremor at 100Hz
        filter.filter(0f, 0.01f)
        var maxJitterOutput = 0f
        for (i in 1..20) {
            val jitter = if (i % 2 == 0) 0.08f else -0.08f
            val out = filter.filter(jitter, 0.01f)
            maxJitterOutput = maxOf(maxJitterOutput, abs(out))
        }
        assertTrue("1€ filter should strongly dampen micro-jitter ($maxJitterOutput)", maxJitterOutput < 0.04f)

        // Now reset and feed a fast intentional 4.0 rad/s wrist flick
        filter.reset()
        filter.filter(0f, 0.01f)
        var fastOut = 0f
        repeat(8) {
            fastOut = filter.filter(4.0f, 0.01f)
        }
        assertTrue("1€ filter should track fast flicks with minimal lag ($fastOut)", fastOut > 3.2f)
    }

    private fun rectF(l: Float, t: Float, r: Float, b: Float): RectF = RectF().apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    @Test
    fun targetReticleFilter_selectsCenterTargetAndIgnoresPeripheralShelfTags() {
        val w = 1000f
        val h = 2000f
        val reticle = TargetReticleFilter.computeReticleBounds(w, h)
        val cx = reticle.centerX
        val cy = reticle.centerY

        val farTopLeft = RawDetectedCode("TAG-FAR-1", rectF(10f, 10f, 90f, 90f))
        val nearCenter = RawDetectedCode("TAG-CENTER", rectF(cx - 25f, cy - 25f, cx + 25f, cy + 25f))
        val edgeInside = RawDetectedCode("TAG-EDGE", rectF(reticle.left + 5f, cy - 20f, reticle.left + 65f, cy + 20f))
        val farBottom = RawDetectedCode("TAG-FAR-2", rectF(850f, 1800f, 950f, 1900f))

        val selected = TargetReticleFilter.selectSingleTarget(
            detectedCodes = listOf(farTopLeft, edgeInside, nearCenter, farBottom),
            viewWidthPx = w,
            viewHeightPx = h
        )
        assertEquals(1, selected.size)
        assertEquals("TAG-CENTER", selected.first().rawValue)

        // When only peripheral tags are visible, returns empty list
        val noneSelected = TargetReticleFilter.selectSingleTarget(
            detectedCodes = listOf(farTopLeft, farBottom),
            viewWidthPx = w,
            viewHeightPx = h
        )
        assertTrue(noneSelected.isEmpty())
    }

    @Test
    fun scanCartAggregator_groupsQuantitiesCalculatesTotalAndBuildsExcelColumns() {
        val items = listOf(
            ScanHistoryItem(rawText = "A-100", formatName = "QR_CODE", profileName = "Standart", resultText = "₺100,00", numericValue = 100.0, timestamp = 1000L),
            ScanHistoryItem(rawText = "B-250", formatName = "QR_CODE", profileName = "Standart", resultText = "₺250,00", numericValue = 250.0, timestamp = 2000L),
            ScanHistoryItem(rawText = "A-100", formatName = "QR_CODE", profileName = "Standart", resultText = "₺100,00", numericValue = 100.0, timestamp = 3000L)
        )

        val cart = ScanCartAggregator.aggregate(items)
        assertEquals(2, cart.size)
        assertEquals("A-100", cart[0].rawText) // most recent first
        assertEquals(2, cart[0].quantity)
        assertEquals(200.0, cart[0].lineTotal!!, 0.001)

        assertEquals(3, ScanCartAggregator.totalUnits(cart))
        assertEquals(450.0, ScanCartAggregator.grandTotal(cart), 0.001)

        val cols = ScanCartAggregator.buildExcelColumns(cart[0])
        assertEquals(listOf("A-100", "2", "200.00"), cols)

        // Removal and quantity override
        val adjusted = ScanCartAggregator.aggregate(
            historyItems = items,
            quantityOverrides = mapOf("A-100" to 5),
            removedKeys = setOf("B-250")
        )
        assertEquals(1, adjusted.size)
        assertEquals(500.0, ScanCartAggregator.grandTotal(adjusted), 0.001)
    }

    @Test
    fun smartTagWizardAnalyzer_detectsDelimiterAndBuildsEvaluableProfile() {
        assertEquals("/", SmartTagWizardAnalyzer.detectDelimiter("TESBIH/450/2.5"))
        assertEquals("-", SmartTagWizardAnalyzer.detectDelimiter("120-2.5-15"))
        assertEquals(";", SmartTagWizardAnalyzer.detectDelimiter("10;20;30"))

        val sample = "TESBIH/450/2"
        val delim = SmartTagWizardAnalyzer.detectDelimiter(sample)
        val tokens = SmartTagWizardAnalyzer.splitSampleTokens(sample, delim)

        val profile = SmartTagWizardAnalyzer.buildProfileFromWizard(
            profileName = "Tesbih Satış",
            delimiter = delim,
            tokenLabels = listOf("Ürün Kodu", "Birim Fiyat", "Adet"),
            sampleTokens = tokens,
            formulaPreset = WizardFormulaPreset.MULTIPLY_FIRST_TWO,
            currencyPrefix = "₺"
        )

        val tracker = QrTracker()
        val parsed = tracker.processQr("TESBIH/450/2", profile)
        assertTrue("Wizard profile should evaluate cleanly", parsed.status is CalculationStatus.Success)
        val success = parsed.status as CalculationStatus.Success
        assertEquals(900.0, success.resultNumber, 0.001)
        assertTrue(success.formattedText.startsWith("₺"))
    }
}
