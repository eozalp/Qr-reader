package com.example.camera

import android.graphics.RectF
import kotlin.math.hypot

/**
 * Pure-Kotlin geometry bounds for the aiming reticle (framework-independent for fast math & tests).
 */
data class ReticleBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun containsPoint(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }

    fun intersectsBox(bLeft: Float, bTop: Float, bRight: Float, bBottom: Float): Boolean {
        return left < bRight && bLeft < right && top < bBottom && bTop < bottom
    }

    fun toRectF(): RectF = RectF().apply {
        left = this@ReticleBounds.left
        top = this@ReticleBounds.top
        right = this@ReticleBounds.right
        bottom = this@ReticleBounds.bottom
    }
}

/**
 * Single-target reticle (Nişangâh) geometry & selection helper.
 *
 * When enabled in dense retail/shelf environments (multiple price tags in frame),
 * filters detections down to the single code closest to the center aiming reticle
 * so only the intentionally aimed product is calculated or transmitted via Bluetooth HID.
 */
object TargetReticleFilter {

    /**
     * Computes a responsive centered aiming reticle box in screen coordinates.
     * Supports both portrait and landscape/tablet aspect ratios.
     */
    fun computeReticleBounds(viewWidthPx: Float, viewHeightPx: Float): ReticleBounds {
        if (viewWidthPx <= 0f || viewHeightPx <= 0f) {
            return ReticleBounds(0f, 0f, 0f, 0f)
        }
        val minDim = minOf(viewWidthPx, viewHeightPx)
        val reticleW = (viewWidthPx * 0.64f).coerceIn(minDim * 0.42f, minDim * 0.80f)
        val reticleH = (viewHeightPx * 0.24f).coerceIn(minDim * 0.26f, minDim * 0.44f)
        val cx = viewWidthPx / 2f
        val cy = viewHeightPx * 0.46f
        return ReticleBounds(
            left = cx - reticleW / 2f,
            top = cy - reticleH / 2f,
            right = cx + reticleW / 2f,
            bottom = cy + reticleH / 2f
        )
    }

    fun computeReticleRect(viewWidthPx: Float, viewHeightPx: Float): RectF {
        return computeReticleBounds(viewWidthPx, viewHeightPx).toRectF()
    }

    /**
     * Returns at most one [RawDetectedCode] whose center falls within (or whose bounding box
     * overlaps) the aiming reticle, prioritizing the one closest to the reticle center.
     */
    fun selectSingleTarget(
        detectedCodes: List<RawDetectedCode>,
        viewWidthPx: Float,
        viewHeightPx: Float
    ): List<RawDetectedCode> {
        if (detectedCodes.isEmpty()) return emptyList()
        if (viewWidthPx <= 0f || viewHeightPx <= 0f) return listOf(detectedCodes.first())

        val reticle = computeReticleBounds(viewWidthPx, viewHeightPx)
        val cx = reticle.centerX
        val cy = reticle.centerY

        val candidates = detectedCodes.filter { code ->
            val box = code.uprightBoundingBox
            val bx = (box.left + box.right) * 0.5f
            val by = (box.top + box.bottom) * 0.5f
            reticle.containsPoint(bx, by) ||
                reticle.intersectsBox(box.left, box.top, box.right, box.bottom)
        }

        val best = candidates.minByOrNull { code ->
            val box = code.uprightBoundingBox
            val bx = (box.left + box.right) * 0.5f
            val by = (box.top + box.bottom) * 0.5f
            hypot((bx - cx).toDouble(), (by - cy).toDouble())
        }

        return if (best != null) listOf(best) else emptyList()
    }
}
