package com.example

import com.example.ui.remote.ModifierLatchState
import com.example.ui.remote.ThreeFingerSwipeDirection
import com.example.ui.remote.TouchpadAction
import com.example.ui.remote.TouchpadConfig
import com.example.ui.remote.TouchpadGestureEngine
import com.example.ui.remote.TouchpadPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadGestureEngineTest {

    private fun newEngine(naturalScroll: Boolean = false): TouchpadGestureEngine {
        return TouchpadGestureEngine(
            TouchpadConfig(
                density = 2f,
                speedMultiplier = 1.0f,
                naturalScroll = naturalScroll,
                tapSlopPx = 20f,
                scrollStepPx = 20f,
                pinchStepPx = 30f,
                threeFingerSwipeThresholdPx = 80f,
                tapMaxDurationMs = 230L,
                doubleTapWindowMs = 270L,
                tapDragHoldConfirmMs = 135L
            )
        )
    }

    @Test
    fun singleTap_firesLeftClickAfterDoubleTapWindowExpires() {
        val engine = newEngine()
        assertTrue(engine.onGestureDown(TouchpadPoint(100f, 100f), 1000L).isEmpty())
        assertTrue(engine.onAllPointersUp(1080L).isEmpty())
        assertTrue(engine.hasPendingSingleTap())

        // Still within double-tap window -> should not flush yet
        assertNull(engine.flushPendingSingleTapIfExpired(1200L))

        // Past double-tap window -> emits single LeftClick
        assertEquals(TouchpadAction.LeftClick, engine.flushPendingSingleTapIfExpired(1360L))
        assertFalse(engine.hasPendingSingleTap())
    }

    @Test
    fun doubleTap_emitsExactDoubleClickWithoutSpuriousExtraClick() {
        val engine = newEngine()
        // Tap 1
        engine.onGestureDown(TouchpadPoint(100f, 100f), 1000L)
        assertTrue(engine.onAllPointersUp(1070L).isEmpty())

        // Tap 2 within window
        assertTrue(engine.onGestureDown(TouchpadPoint(102f, 101f), 1180L).isEmpty())
        val upActions = engine.onAllPointersUp(1240L)
        assertEquals(listOf(TouchpadAction.DoubleClick), upActions)
        assertFalse(engine.hasPendingSingleTap())
        assertNull(engine.flushPendingSingleTapIfExpired(2000L))
    }

    @Test
    fun doubleTapAndHoldDrag_locksLeftButtonAndMovesUntilLift() {
        val engine = newEngine()
        // Tap 1
        engine.onGestureDown(TouchpadPoint(100f, 100f), 1000L)
        assertTrue(engine.onAllPointersUp(1060L).isEmpty())

        // Tap 2 down within double-tap window, then slide past slop -> TapDragStart + Move!
        assertTrue(engine.onGestureDown(TouchpadPoint(100f, 100f), 1150L).isEmpty())
        val dragActions = engine.onMove(listOf(TouchpadPoint(145f, 110f)), 1190L)
        assertTrue(dragActions.any { it == TouchpadAction.TapDragStart })
        assertTrue(dragActions.any { it is TouchpadAction.Move })
        assertTrue(engine.isTapDragging)

        // Subsequent moves do NOT re-emit TapDragStart
        val moveActions = engine.onMove(listOf(TouchpadPoint(180f, 120f)), 1230L)
        assertFalse(moveActions.any { it == TouchpadAction.TapDragStart })
        assertTrue(moveActions.any { it is TouchpadAction.Move })

        // Lifting finger emits TapDragEnd
        val endActions = engine.onAllPointersUp(1300L)
        assertEquals(listOf(TouchpadAction.TapDragEnd), endActions)
        assertFalse(engine.isTapDragging)
    }

    @Test
    fun doubleTapHoldStill_confirmsTapDragViaHoldTick() {
        val engine = newEngine()
        engine.onGestureDown(TouchpadPoint(100f, 100f), 1000L)
        engine.onAllPointersUp(1060L)

        engine.onGestureDown(TouchpadPoint(100f, 100f), 1150L)
        assertTrue(engine.onHoldTick(1200L).isEmpty())
        val confirmed = engine.onHoldTick(1300L)
        assertEquals(listOf(TouchpadAction.TapDragStart), confirmed)
        assertTrue(engine.isTapDragging)

        assertEquals(listOf(TouchpadAction.TapDragEnd), engine.onAllPointersUp(1500L))
    }

    @Test
    fun twoFingerTap_andThreeFingerTap_emitRightAndMiddleClicks() {
        val engine = newEngine()
        // 2-finger tap -> RightClick
        engine.onGestureDown(TouchpadPoint(100f, 100f), 1000L)
        engine.onPointerAdded(listOf(TouchpadPoint(100f, 100f), TouchpadPoint(160f, 100f)))
        assertEquals(listOf(TouchpadAction.RightClick), engine.onAllPointersUp(1110L))

        // 3-finger tap -> MiddleClick
        engine.onGestureDown(TouchpadPoint(100f, 100f), 2000L)
        engine.onPointerAdded(listOf(TouchpadPoint(100f, 100f), TouchpadPoint(150f, 100f), TouchpadPoint(200f, 100f)))
        assertEquals(listOf(TouchpadAction.MiddleClick), engine.onAllPointersUp(2120L))
    }

    @Test
    fun twoFingerScroll_respectsNaturalVsClassicDirection() {
        val classic = newEngine(naturalScroll = false)
        classic.onGestureDown(TouchpadPoint(100f, 100f), 1000L)
        classic.onPointerAdded(listOf(TouchpadPoint(100f, 100f), TouchpadPoint(160f, 100f)))
        val classicActions = classic.onMove(listOf(TouchpadPoint(100f, 140f), TouchpadPoint(160f, 140f)), 1030L)
        val classicScroll = classicActions.filterIsInstance<TouchpadAction.Scroll>().firstOrNull()
        assertNotNull(classicScroll)
        assertTrue(classicScroll!!.wheelSteps < 0)

        val natural = newEngine(naturalScroll = true)
        natural.onGestureDown(TouchpadPoint(100f, 100f), 1000L)
        natural.onPointerAdded(listOf(TouchpadPoint(100f, 100f), TouchpadPoint(160f, 100f)))
        val naturalActions = natural.onMove(listOf(TouchpadPoint(100f, 140f), TouchpadPoint(160f, 140f)), 1030L)
        val naturalScroll = naturalActions.filterIsInstance<TouchpadAction.Scroll>().firstOrNull()
        assertNotNull(naturalScroll)
        assertTrue(naturalScroll!!.wheelSteps > 0)
    }

    @Test
    fun twoFingerPinch_emitsZoomWheelActions() {
        val engine = newEngine()
        engine.onGestureDown(TouchpadPoint(100f, 200f), 1000L)
        engine.onPointerAdded(listOf(TouchpadPoint(100f, 200f), TouchpadPoint(200f, 200f)))
        // Spread fingers outward by +80px -> Zoom in (> 0)
        val zoomOut = engine.onMove(listOf(TouchpadPoint(60f, 200f), TouchpadPoint(240f, 200f)), 1050L)
        val zoom = zoomOut.filterIsInstance<TouchpadAction.Zoom>().firstOrNull()
        assertNotNull(zoom)
        assertTrue(zoom!!.wheelSteps > 0)
    }

    @Test
    fun threeFingerSwipe_detectsAllFourDirections() {
        val engine = newEngine()
        engine.onGestureDown(TouchpadPoint(200f, 200f), 1000L)
        engine.onPointerAdded(listOf(TouchpadPoint(150f, 200f), TouchpadPoint(200f, 200f), TouchpadPoint(250f, 200f)))
        val swipeUp = engine.onMove(listOf(TouchpadPoint(150f, 90f), TouchpadPoint(200f, 90f), TouchpadPoint(250f, 90f)), 1050L)
        assertEquals(listOf(TouchpadAction.ThreeFingerSwipe(ThreeFingerSwipeDirection.UP)), swipeUp)
        engine.onAllPointersUp(1100L)

        engine.onGestureDown(TouchpadPoint(200f, 200f), 2000L)
        engine.onPointerAdded(listOf(TouchpadPoint(150f, 200f), TouchpadPoint(200f, 200f), TouchpadPoint(250f, 200f)))
        val swipeLeft = engine.onMove(listOf(TouchpadPoint(50f, 205f), TouchpadPoint(100f, 205f), TouchpadPoint(150f, 205f)), 2050L)
        assertEquals(listOf(TouchpadAction.ThreeFingerSwipe(ThreeFingerSwipeDirection.LEFT)), swipeLeft)
    }

    @Test
    fun edgeAutoPan_returnsDirectionNearBordersAndZeroInCenter() {
        assertEquals(0 to 0, TouchpadGestureEngine.computeEdgeAutoPanDelta(250f, 250f, 500f, 500f, 50f))
        val (rightDx, _) = TouchpadGestureEngine.computeEdgeAutoPanDelta(490f, 250f, 500f, 500f, 50f)
        assertTrue(rightDx > 0)
        val (leftDx, topDy) = TouchpadGestureEngine.computeEdgeAutoPanDelta(5f, 10f, 500f, 500f, 50f)
        assertTrue(leftDx < 0 && topDy < 0)
    }

    @Test
    fun modifierLatchState_transitionsCleanlyBetweenOffOneShotAndLocked() {
        var state = ModifierLatchState.OFF
        state = state.nextTap()
        assertEquals(ModifierLatchState.ONE_SHOT, state)
        assertEquals(ModifierLatchState.OFF, state.consumeIfOneShot())

        state = state.nextTap()
        assertEquals(ModifierLatchState.LOCKED, state)
        // Locked stays locked after keystroke!
        assertEquals(ModifierLatchState.LOCKED, state.consumeIfOneShot())
        assertEquals(ModifierLatchState.OFF, state.nextTap())
    }
}
