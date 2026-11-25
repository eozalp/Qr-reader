package com.example.ui.remote

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure-Kotlin laptop-grade touchpad gesture state machine.
 *
 * Supports:
 * - 1-finger move with configurable speed & dynamic acceleration
 * - 1-finger tap -> Left Click (delayed briefly only while awaiting double-tap / tap-drag disambiguation)
 * - 1-finger double tap -> Clean Double Click (never fires triple click)
 * - 1-finger tap, then tap & hold/move -> Tap-and-Hold Drag (Left Button held until lift!)
 * - 2-finger tap -> Right Click
 * - 2-finger vertical drag -> Smooth Scroll (with Natural / Classic direction toggle)
 * - 2-finger pinch in/out -> Zoom (Ctrl + Wheel)
 * - 3-finger tap -> Middle Click
 * - 3-finger swipe (Left / Right / Up / Down) -> Back / Forward / Task View / Show Desktop
 * - Edge auto-panning vector when dragging near touchpad borders
 */
enum class ThreeFingerSwipeDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN
}

sealed class TouchpadAction {
    data class Move(val dx: Int, val dy: Int) : TouchpadAction()
    data class Scroll(val wheelSteps: Int) : TouchpadAction()
    data class Zoom(val wheelSteps: Int) : TouchpadAction()
    object LeftClick : TouchpadAction()
    object DoubleClick : TouchpadAction()
    object RightClick : TouchpadAction()
    object MiddleClick : TouchpadAction()
    object TapDragStart : TouchpadAction()
    object TapDragEnd : TouchpadAction()
    data class ThreeFingerSwipe(val direction: ThreeFingerSwipeDirection) : TouchpadAction()
}

data class TouchpadPoint(val x: Float, val y: Float)

data class TouchpadConfig(
    val density: Float = 2.5f,
    val speedMultiplier: Float = 1.15f,
    val naturalScroll: Boolean = false,
    val tapSlopPx: Float = 26f,
    val scrollStepPx: Float = 32f,
    val pinchStepPx: Float = 44f,
    val threeFingerSwipeThresholdPx: Float = 96f,
    val tapMaxDurationMs: Long = 230L,
    val doubleTapWindowMs: Long = 270L,
    val tapDragHoldConfirmMs: Long = 135L
)

class TouchpadGestureEngine(
    var config: TouchpadConfig = TouchpadConfig()
) {
    private var gestureStartTimeMs: Long = 0L
    private var gestureStartPos: TouchpadPoint = TouchpadPoint(0f, 0f)
    private var lastSinglePos: TouchpadPoint = TouchpadPoint(0f, 0f)
    private var lastMultiCentroid: TouchpadPoint = TouchpadPoint(0f, 0f)
    private var threeFingerStartCentroid: TouchpadPoint? = null
    private var lastPinchDist: Float = 0f

    private var maxPointersInGesture: Int = 0
    private var movedPastSlop: Boolean = false
    private var didTwoFingerGesture: Boolean = false
    private var didPinchGesture: Boolean = false
    private var didThreeFingerSwipe: Boolean = false

    private var accumDx: Float = 0f
    private var accumDy: Float = 0f
    private var accumWheel: Float = 0f
    private var accumPinch: Float = 0f

    // Tap / Double-tap / Tap-and-Hold-Drag state
    private var pendingSingleTapAtMs: Long = 0L
    private var isSecondTapCandidate: Boolean = false
    var isTapDragging: Boolean = false
        private set

    fun hasPendingSingleTap(): Boolean = pendingSingleTapAtMs > 0L

    fun onGestureDown(point: TouchpadPoint, nowMs: Long): List<TouchpadAction> {
        val actions = mutableListOf<TouchpadAction>()
        gestureStartTimeMs = nowMs
        gestureStartPos = point
        lastSinglePos = point
        lastMultiCentroid = point
        threeFingerStartCentroid = null
        lastPinchDist = 0f

        maxPointersInGesture = 1
        movedPastSlop = false
        didTwoFingerGesture = false
        didPinchGesture = false
        didThreeFingerSwipe = false
        accumDx = 0f
        accumDy = 0f
        accumWheel = 0f
        accumPinch = 0f

        // Check if this touch starts right after a first tap -> candidate for Double-Click OR Tap-Hold-Drag!
        if (pendingSingleTapAtMs > 0L && (nowMs - pendingSingleTapAtMs) <= config.doubleTapWindowMs) {
            pendingSingleTapAtMs = 0L
            isSecondTapCandidate = true
        } else {
            if (pendingSingleTapAtMs > 0L) {
                // Previous single tap timed out before caller flushed it
                pendingSingleTapAtMs = 0L
                actions.add(TouchpadAction.LeftClick)
            }
            isSecondTapCandidate = false
        }
        return actions
    }

    /**
     * Called when an additional finger touches down during an active gesture.
     */
    fun onPointerAdded(activePoints: List<TouchpadPoint>): List<TouchpadAction> {
        val actions = mutableListOf<TouchpadAction>()
        maxPointersInGesture = maxOf(maxPointersInGesture, activePoints.size)

        // Adding extra fingers cancels second-tap drag candidate
        if (activePoints.size >= 2) {
            if (isTapDragging) {
                isTapDragging = false
                actions.add(TouchpadAction.TapDragEnd)
            }
            isSecondTapCandidate = false
            val centroid = centroidOf(activePoints)
            lastMultiCentroid = centroid
            if (activePoints.size == 2) {
                lastPinchDist = distanceBetween(activePoints[0], activePoints[1])
            } else if (activePoints.size >= 3) {
                threeFingerStartCentroid = centroid
            }
        }
        return actions
    }

    /**
     * Called when one finger lifts while others remain pressed (re-anchors pointer cleanly without jump).
     */
    fun onPointerRemoved(remainingPoints: List<TouchpadPoint>) {
        if (remainingPoints.size == 1) {
            lastSinglePos = remainingPoints[0]
            lastMultiCentroid = remainingPoints[0]
            lastPinchDist = 0f
        } else if (remainingPoints.size == 2) {
            lastMultiCentroid = centroidOf(remainingPoints)
            lastPinchDist = distanceBetween(remainingPoints[0], remainingPoints[1])
        }
    }

    /**
     * Called periodically while holding a second tap so holding still (> tapDragHoldConfirmMs)
     * immediately locks into Tap-and-Hold Drag with tactile feedback even before moving!
     */
    fun onHoldTick(nowMs: Long): List<TouchpadAction> {
        if (isSecondTapCandidate && !isTapDragging && maxPointersInGesture == 1) {
            if ((nowMs - gestureStartTimeMs) >= config.tapDragHoldConfirmMs) {
                isTapDragging = true
                isSecondTapCandidate = false
                return listOf(TouchpadAction.TapDragStart)
            }
        }
        return emptyList()
    }

    /**
     * Processes pointer motion for 1, 2, or 3+ active fingers.
     */
    fun onMove(activePoints: List<TouchpadPoint>, nowMs: Long): List<TouchpadAction> {
        if (activePoints.isEmpty()) return emptyList()
        val actions = mutableListOf<TouchpadAction>()
        maxPointersInGesture = maxOf(maxPointersInGesture, activePoints.size)

        // 3+ fingers -> swipe detection (Back / Forward / Task View / Show Desktop)
        if (activePoints.size >= 3) {
            val centroid = centroidOf(activePoints)
            val start = threeFingerStartCentroid ?: centroid.also { threeFingerStartCentroid = it }
            if (!didThreeFingerSwipe) {
                val dx = centroid.x - start.x
                val dy = centroid.y - start.y
                val dist = hypot(dx, dy)
                if (dist >= config.threeFingerSwipeThresholdPx) {
                    movedPastSlop = true
                    didThreeFingerSwipe = true
                    val dir = if (abs(dx) >= abs(dy)) {
                        if (dx > 0f) ThreeFingerSwipeDirection.RIGHT else ThreeFingerSwipeDirection.LEFT
                    } else {
                        if (dy > 0f) ThreeFingerSwipeDirection.DOWN else ThreeFingerSwipeDirection.UP
                    }
                    actions.add(TouchpadAction.ThreeFingerSwipe(dir))
                }
            }
            lastMultiCentroid = centroid
            return actions
        }

        // 2 fingers -> Pinch-to-Zoom OR 2-finger Vertical Scroll
        if (activePoints.size == 2) {
            if (didThreeFingerSwipe) return actions
            val centroid = centroidOf(activePoints)
            val curDist = distanceBetween(activePoints[0], activePoints[1])
            val prevDist = if (lastPinchDist > 0f) lastPinchDist else curDist
            val pinchDelta = curDist - prevDist
            val dy = centroid.y - lastMultiCentroid.y

            lastPinchDist = curDist
            lastMultiCentroid = centroid

            // Distinguish pinch from parallel scroll:
            // Pinch has significant span change relative to vertical centroid motion
            if (didPinchGesture || (abs(pinchDelta) > 4.5f && abs(pinchDelta) > abs(dy) * 1.15f)) {
                movedPastSlop = true
                didTwoFingerGesture = true
                didPinchGesture = true
                accumPinch += pinchDelta / config.pinchStepPx
                val zoomSteps = accumPinch.toInt()
                if (zoomSteps != 0) {
                    accumPinch -= zoomSteps
                    actions.add(TouchpadAction.Zoom(zoomSteps.coerceIn(-4, 4)))
                }
            } else {
                if (abs(dy) > 1.5f) {
                    movedPastSlop = true
                    didTwoFingerGesture = true
                }
                val scrollSign = if (config.naturalScroll) 1f else -1f
                accumWheel += (dy / config.scrollStepPx) * scrollSign
                val steps = accumWheel.toInt()
                if (steps != 0) {
                    accumWheel -= steps
                    actions.add(TouchpadAction.Scroll(steps.coerceIn(-12, 12)))
                }
            }
            return actions
        }

        // 1 finger -> Cursor move (or Tap-and-Hold Drag!)
        if (!didTwoFingerGesture && !didThreeFingerSwipe) {
            val cur = activePoints[0]
            val distFromStart = hypot(cur.x - gestureStartPos.x, cur.y - gestureStartPos.y)
            if (distFromStart > config.tapSlopPx) {
                movedPastSlop = true
                // If user tapped once and immediately tapped+dragged, lock LEFT button down NOW!
                if (isSecondTapCandidate && !isTapDragging) {
                    isTapDragging = true
                    isSecondTapCandidate = false
                    actions.add(TouchpadAction.TapDragStart)
                }
            }

            // Also promote to tap-drag if holding long enough while nudging
            if (isSecondTapCandidate && !isTapDragging && (nowMs - gestureStartTimeMs) >= config.tapDragHoldConfirmMs) {
                isTapDragging = true
                isSecondTapCandidate = false
                actions.add(TouchpadAction.TapDragStart)
            }

            val dx = cur.x - lastSinglePos.x
            val dy = cur.y - lastSinglePos.y
            lastSinglePos = cur
            lastMultiCentroid = cur

            val speed = hypot(dx, dy)
            val safeDensity = config.density.coerceAtLeast(1f)
            val baseAccel = (1.10f + (speed / 26f).coerceIn(0f, 1.55f)) / (safeDensity * 0.62f)
            val effectiveAccel = baseAccel * config.speedMultiplier.coerceIn(0.35f, 3.0f)

            accumDx += dx * effectiveAccel
            accumDy += dy * effectiveAccel
            val sendX = accumDx.toInt()
            val sendY = accumDy.toInt()
            if (sendX != 0 || sendY != 0) {
                accumDx -= sendX
                accumDy -= sendY
                actions.add(TouchpadAction.Move(sendX, sendY))
            }
        }
        return actions
    }

    /**
     * Called when the last finger lifts off the touchpad surface.
     */
    fun onAllPointersUp(nowMs: Long): List<TouchpadAction> {
        val actions = mutableListOf<TouchpadAction>()
        val dt = nowMs - gestureStartTimeMs

        if (isTapDragging) {
            isTapDragging = false
            isSecondTapCandidate = false
            actions.add(TouchpadAction.TapDragEnd)
            return actions
        }

        if (!movedPastSlop && !didTwoFingerGesture && !didThreeFingerSwipe && dt <= config.tapMaxDurationMs) {
            when {
                maxPointersInGesture >= 3 -> {
                    isSecondTapCandidate = false
                    pendingSingleTapAtMs = 0L
                    actions.add(TouchpadAction.MiddleClick)
                }
                maxPointersInGesture == 2 -> {
                    isSecondTapCandidate = false
                    pendingSingleTapAtMs = 0L
                    actions.add(TouchpadAction.RightClick)
                }
                isSecondTapCandidate -> {
                    // Clean second quick tap -> exact Double Click!
                    isSecondTapCandidate = false
                    pendingSingleTapAtMs = 0L
                    actions.add(TouchpadAction.DoubleClick)
                }
                else -> {
                    // Defer LeftClick briefly so a quick second tap can become DoubleClick or Tap-and-Hold Drag
                    pendingSingleTapAtMs = nowMs
                }
            }
        } else {
            isSecondTapCandidate = false
        }
        return actions
    }

    /**
     * Flushes a pending single tap once [doubleTapWindowMs] has elapsed without a second touch.
     */
    fun flushPendingSingleTapIfExpired(nowMs: Long): TouchpadAction? {
        if (pendingSingleTapAtMs > 0L && (nowMs - pendingSingleTapAtMs) >= config.doubleTapWindowMs) {
            pendingSingleTapAtMs = 0L
            return TouchpadAction.LeftClick
        }
        return null
    }

    /**
     * Forces cleanup when gesture is cancelled or composable leaves composition.
     */
    fun reset(): List<TouchpadAction> {
        val actions = mutableListOf<TouchpadAction>()
        if (isTapDragging) {
            isTapDragging = false
            actions.add(TouchpadAction.TapDragEnd)
        }
        pendingSingleTapAtMs = 0L
        isSecondTapCandidate = false
        return actions
    }

    companion object {
        /**
         * Computes continuous edge-pan delta when dragging a window/selection near touchpad borders,
         * allowing effortless cross-monitor dragging on a phone screen.
         */
        fun computeEdgeAutoPanDelta(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            edgeZonePx: Float,
            maxStepPx: Int = 9
        ): Pair<Int, Int> {
            if (width <= edgeZonePx * 2f || height <= edgeZonePx * 2f || edgeZonePx <= 0f) return 0 to 0
            val dx = when {
                x < edgeZonePx -> -(((edgeZonePx - x) / edgeZonePx).coerceIn(0f, 1f) * maxStepPx).toInt()
                x > width - edgeZonePx -> (((x - (width - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1f) * maxStepPx).toInt()
                else -> 0
            }
            val dy = when {
                y < edgeZonePx -> -(((edgeZonePx - y) / edgeZonePx).coerceIn(0f, 1f) * maxStepPx).toInt()
                y > height - edgeZonePx -> (((y - (height - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1f) * maxStepPx).toInt()
                else -> 0
            }
            return dx to dy
        }

        private fun centroidOf(points: List<TouchpadPoint>): TouchpadPoint {
            var sx = 0f
            var sy = 0f
            for (p in points) {
                sx += p.x
                sy += p.y
            }
            val n = points.size.coerceAtLeast(1)
            return TouchpadPoint(sx / n, sy / n)
        }

        private fun distanceBetween(a: TouchpadPoint, b: TouchpadPoint): Float {
            return hypot(a.x - b.x, a.y - b.y)
        }
    }
}
