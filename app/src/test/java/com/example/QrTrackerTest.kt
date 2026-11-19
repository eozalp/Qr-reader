package com.example

import android.graphics.RectF
import com.example.camera.QrTracker
import com.example.camera.RawDetectedCode
import com.example.model.QrProfile
import com.example.model.TrackingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class QrTrackerTest {

    private fun testProfile(timeoutMs: Long = 1000L): QrProfile {
        return QrProfile(
            tracking = TrackingSettings(
                disappearanceTimeoutMs = timeoutMs,
                smoothingFactor = 0.5f,
                maxTrackedCodes = 10
            )
        )
    }

    @Test
    fun tickFade_returnsNullWhenEmptyOrBeforeGraceWindow() {
        val tracker = QrTracker()
        val profile = testProfile(timeoutMs = 1000L)

        // Empty tracker should never emit state updates
        assertNull(tracker.tickFade(profile, currentTimeMs = 1000L))

        // Insert one tracked QR at t = 1000
        val initial = tracker.update(
            detectedScreenBoxes = listOf(RawDetectedCode("10-20-30-40", RectF(0f, 0f, 100f, 100f))),
            profile = profile,
            currentTimeMs = 1000L
        )
        assertEquals(1, initial.size)
        assertEquals(1.0f, initial.first().alpha, 0.001f)

        // Within 60ms grace window, tickFade should return null (no redundant recomposition)
        assertNull(tracker.tickFade(profile, currentTimeMs = 1050L))
    }

    @Test
    fun tickFade_fadesSmoothlyAndRemovesExpiredCodes() {
        val tracker = QrTracker()
        val profile = testProfile(timeoutMs = 1000L)

        tracker.update(
            detectedScreenBoxes = listOf(RawDetectedCode("10-20-30-40", RectF(10f, 10f, 110f, 110f))),
            profile = profile,
            currentTimeMs = 2000L
        )

        // Halfway through timeout (500ms elapsed) -> alpha ~ 0.5
        val faded = tracker.tickFade(profile, currentTimeMs = 2500L)
        assertNotNull(faded)
        assertEquals(1, faded!!.size)
        assertEquals(0.5f, faded.first().alpha, 0.05f)

        // Tiny 5ms step shouldn't trigger redundant emission (< 0.02 alpha delta)
        assertNull(tracker.tickFade(profile, currentTimeMs = 2505L))

        // Past disappearanceTimeoutMs (1050ms elapsed) -> removed and returns empty list once
        val expired = tracker.tickFade(profile, currentTimeMs = 3050L)
        assertNotNull(expired)
        assertTrue(expired!!.isEmpty())

        // Subsequent tick on empty tracker returns null
        assertNull(tracker.tickFade(profile, currentTimeMs = 3200L))
    }

    @Test
    fun concurrentUpdateAndTickFade_isThreadSafe() {
        val tracker = QrTracker()
        val profile = testProfile(timeoutMs = 500L)
        val executor = Executors.newFixedThreadPool(4)
        val errorRef = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(4)

        try {
            // Two writer threads calling update()
            repeat(2) { workerIdx ->
                executor.submit {
                    try {
                        var now = 10_000L
                        for (i in 0 until 200) {
                            now += 15L
                            val items = if (i % 3 == 0) {
                                emptyList()
                            } else {
                                listOf(
                                    RawDetectedCode(
                                        rawValue = "${10 + workerIdx}-${20 + (i % 5)}-30-40",
                                        uprightBoundingBox = RectF(0f, 0f, 80f, 80f)
                                    )
                                )
                            }
                            tracker.update(items, profile, currentTimeMs = now)
                        }
                    } catch (t: Throwable) {
                        errorRef.compareAndSet(null, t)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            // Two reader/ticker threads calling tickFade() and clear()
            repeat(2) { workerIdx ->
                executor.submit {
                    try {
                        var now = 10_000L
                        for (i in 0 until 250) {
                            now += 12L
                            tracker.tickFade(profile, currentTimeMs = now)
                            if (workerIdx == 1 && i % 50 == 0) {
                                tracker.clear()
                            }
                        }
                    } catch (t: Throwable) {
                        errorRef.compareAndSet(null, t)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue("Concurrent threads timed out", latch.await(15, TimeUnit.SECONDS))
            assertNull("Unexpected concurrency failure: ${errorRef.get()}", errorRef.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
