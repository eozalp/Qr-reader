package com.example.ui

import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Solved 2D coordinates for an overlay callout box and its bonded leader line.
 */
data class SolvedCalloutPosition(
    val tracked: TrackedQr,
    val cardCenterX: Float,
    val cardCenterY: Float,
    val cardWidth: Float,
    val cardHeight: Float,
    val barcodeAnchorX: Float,
    val barcodeAnchorY: Float,
    val cardAnchorX: Float,
    val cardAnchorY: Float,
    val hasLeaderLine: Boolean
)

object CalloutLayoutSolver {

    /**
     * Coerces [value] into [minVal]..[maxVal], safely centering when card exceeds viewport bounds.
     */
    private fun safeCoerce(value: Float, minVal: Float, maxVal: Float): Float {
        return if (minVal <= maxVal) value.coerceIn(minVal, maxVal) else (minVal + maxVal) * 0.5f
    }

    /**
     * Solves non-overlapping layout positions for all active tracked QR codes on screen.
     */
    fun solve(
        items: List<TrackedQr>,
        config: OverlayAppearance,
        screenWidthPx: Float,
        screenHeightPx: Float,
        density: Density
    ): List<SolvedCalloutPosition> {
        if (items.isEmpty() || screenWidthPx <= 0f || screenHeightPx <= 0f) return emptyList()

        val scale = config.scale.coerceIn(0.5f, 3.0f)
        val isTableMode = config.displayMode == OverlayDisplayMode.EXCEL_TABLE || config.displayMode == OverlayDisplayMode.HYBRID
        val rawW = if (config.autoWidth) (if (isTableMode) 250f else 220f) else config.customWidth
        val rawH = if (config.autoHeight) (if (isTableMode) 140f else 85f) else config.customHeight

        val cardW = with(density) { (rawW * scale).dp.toPx() }
        val cardH = with(density) { (rawH * scale).dp.toPx() }

        val margin = with(density) { 16.dp.toPx() }
        val topSafe = with(density) { 72.dp.toPx() }
        val bottomSafe = with(density) { 86.dp.toPx() }

        val offsetXPx = with(density) { config.offsetX.dp.toPx() }
        val offsetYPx = with(density) { config.offsetY.dp.toPx() }

        when (config.calloutMode) {
            CalloutLayoutMode.DIRECT_FOLLOW -> {
                return items.map { item ->
                    val (baseX, baseY) = computeAnchorCenter(item.currentBox, config.anchorPosition)
                    var targetX = baseX + offsetXPx
                    var targetY = baseY + offsetYPx

                    if (config.autoAvoidEdges) {
                        targetX = safeCoerce(targetX, margin + cardW / 2f, screenWidthPx - margin - cardW / 2f)
                        targetY = safeCoerce(targetY, topSafe + cardH / 2f, screenHeightPx - bottomSafe - cardH / 2f)
                    }

                    val (barcX, barcY) = findClosestPointOnRect(item.currentBox, targetX, targetY)
                    val cardRect = RectF(targetX - cardW / 2f, targetY - cardH / 2f, targetX + cardW / 2f, targetY + cardH / 2f)
                    val (cardAncX, cardAncY) = findClosestPointOnRect(cardRect, item.currentBox.centerX(), item.currentBox.centerY())

                    SolvedCalloutPosition(
                        tracked = item,
                        cardCenterX = targetX,
                        cardCenterY = targetY,
                        cardWidth = cardW,
                        cardHeight = cardH,
                        barcodeAnchorX = barcX,
                        barcodeAnchorY = barcY,
                        cardAnchorX = cardAncX,
                        cardAnchorY = cardAncY,
                        hasLeaderLine = false
                    )
                }
            }

            CalloutLayoutMode.DOCKED_SIDEBAR -> {
                // Sort items by vertical position in camera feed
                val sorted = items.sortedBy { it.currentBox.centerY() }

                // Check whether QRs are predominantly on the left or right
                val leftQrs = sorted.count { it.currentBox.centerX() < screenWidthPx / 2f }
                val rightQrs = sorted.size - leftQrs
                val dockRight = leftQrs >= rightQrs

                val rawTargetX = if (dockRight) {
                    screenWidthPx - margin - cardW / 2f
                } else {
                    margin + cardW / 2f
                }
                val targetX = safeCoerce(rawTargetX, margin + cardW / 2f, screenWidthPx - margin - cardW / 2f)

                val availableHeight = (screenHeightPx - bottomSafe) - topSafe
                val totalCardsH = sorted.size * cardH
                val spacing = if (sorted.size > 1) {
                    safeCoerce((availableHeight - totalCardsH) / (sorted.size - 1), 8f, with(density) { 32.dp.toPx() })
                } else 0f

                val startY = topSafe + cardH / 2f + ((availableHeight - (sorted.size * cardH + (sorted.size - 1) * spacing)) / 2f).coerceAtLeast(0f)

                return sorted.mapIndexed { index, item ->
                    val targetY = safeCoerce(startY + index * (cardH + spacing), topSafe + cardH / 2f, screenHeightPx - bottomSafe - cardH / 2f)

                    val (barcX, barcY) = findClosestPointOnRect(item.currentBox, targetX, targetY)
                    val cardRect = RectF(targetX - cardW / 2f, targetY - cardH / 2f, targetX + cardW / 2f, targetY + cardH / 2f)
                    val (cardAncX, cardAncY) = findClosestPointOnRect(cardRect, item.currentBox.centerX(), item.currentBox.centerY())

                    SolvedCalloutPosition(
                        tracked = item,
                        cardCenterX = targetX,
                        cardCenterY = targetY,
                        cardWidth = cardW,
                        cardHeight = cardH,
                        barcodeAnchorX = barcX,
                        barcodeAnchorY = barcY,
                        cardAnchorX = cardAncX,
                        cardAnchorY = cardAncY,
                        hasLeaderLine = true
                    )
                }
            }

            CalloutLayoutMode.SMART_ANTI_COLLISION -> {
                // Initialize target centers around their respective QR codes
                val centers = items.map { item ->
                    val (baseX, baseY) = computeAnchorCenter(item.currentBox, config.anchorPosition)
                    val naturalX = safeCoerce(baseX + offsetXPx, margin + cardW / 2f, screenWidthPx - margin - cardW / 2f)

                    // If placed above or below, place with enough clearance
                    val rawNaturalY = if (item.currentBox.centerY() > screenHeightPx * 0.55f) {
                        // QR is in lower screen half -> place box above barcode
                        (item.currentBox.top - cardH / 2f - with(density) { 36.dp.toPx() })
                    } else {
                        // QR is in upper screen half -> place box below barcode
                        (item.currentBox.bottom + cardH / 2f + with(density) { 36.dp.toPx() })
                    }
                    val naturalY = safeCoerce(rawNaturalY, topSafe + cardH / 2f, screenHeightPx - bottomSafe - cardH / 2f)

                    PointF(naturalX, naturalY)
                }.toMutableList()

                // Repulsion relaxation algorithm (8 iterations to push overlapping boxes apart)
                val minGapX = cardW + with(density) { 14.dp.toPx() }
                val minGapY = cardH + with(density) { 14.dp.toPx() }

                if (items.size > 1) {
                    repeat(8) {
                        for (i in 0 until centers.size) {
                            for (j in i + 1 until centers.size) {
                                val dx = centers[j].x - centers[i].x
                                val dy = centers[j].y - centers[i].y

                                val absDx = abs(dx)
                                val absDy = abs(dy)

                                if (absDx < minGapX && absDy < minGapY) {
                                    val overlapX = minGapX - absDx
                                    val overlapY = minGapY - absDy

                                    // Push along the axis that requires less displacement, prioritizing vertical
                                    if (overlapY <= overlapX * 1.25f) {
                                        val pushY = overlapY * 0.52f
                                        if (dy >= 0) {
                                            centers[i].y -= pushY
                                            centers[j].y += pushY
                                        } else {
                                            centers[i].y += pushY
                                            centers[j].y -= pushY
                                        }
                                        // Slight horizontal nudging for aesthetic diagonal stagger
                                        if (absDx < 24f) {
                                            centers[i].x -= 12f
                                            centers[j].x += 12f
                                        }
                                    } else {
                                        val pushX = overlapX * 0.52f
                                        if (dx >= 0) {
                                            centers[i].x -= pushX
                                            centers[j].x += pushX
                                        } else {
                                            centers[i].x += pushX
                                            centers[j].x -= pushX
                                        }
                                    }
                                }
                            }
                        }

                        // Re-clamp all centers to screen boundaries after each iteration
                        for (pt in centers) {
                            pt.x = safeCoerce(pt.x, margin + cardW / 2f, screenWidthPx - margin - cardW / 2f)
                            pt.y = safeCoerce(pt.y, topSafe + cardH / 2f, screenHeightPx - bottomSafe - cardH / 2f)
                        }
                    }
                }

                return items.mapIndexed { index, item ->
                    val pt = centers[index]
                    val cardRect = RectF(pt.x - cardW / 2f, pt.y - cardH / 2f, pt.x + cardW / 2f, pt.y + cardH / 2f)

                    val (barcX, barcY) = findClosestPointOnRect(item.currentBox, pt.x, pt.y)
                    val (cardAncX, cardAncY) = findClosestPointOnRect(cardRect, item.currentBox.centerX(), item.currentBox.centerY())

                    val dist = hypot(pt.x - item.currentBox.centerX(), pt.y - item.currentBox.centerY())
                    val hasLine = dist > with(density) { 40.dp.toPx() }

                    SolvedCalloutPosition(
                        tracked = item,
                        cardCenterX = pt.x,
                        cardCenterY = pt.y,
                        cardWidth = cardW,
                        cardHeight = cardH,
                        barcodeAnchorX = barcX,
                        barcodeAnchorY = barcY,
                        cardAnchorX = cardAncX,
                        cardAnchorY = cardAncY,
                        hasLeaderLine = hasLine
                    )
                }
            }
        }
    }

    private fun computeAnchorCenter(box: RectF, anchor: OverlayAnchorPosition): Pair<Float, Float> {
        return when (anchor) {
            OverlayAnchorPosition.CENTER -> Pair(box.centerX(), box.centerY())
            OverlayAnchorPosition.ABOVE -> Pair(box.centerX(), box.top - 60f)
            OverlayAnchorPosition.BELOW -> Pair(box.centerX(), box.bottom + 60f)
            OverlayAnchorPosition.TOP_LEFT -> Pair(box.left, box.top)
            OverlayAnchorPosition.TOP_RIGHT -> Pair(box.right, box.top)
            OverlayAnchorPosition.BOTTOM_LEFT -> Pair(box.left, box.bottom)
            OverlayAnchorPosition.BOTTOM_RIGHT -> Pair(box.right, box.bottom)
        }
    }

    /**
     * Finds the closest point on a rectangle's boundary to an external point (tx, ty).
     */
    fun findClosestPointOnRect(rect: RectF, tx: Float, ty: Float): Pair<Float, Float> {
        if (rect.width() <= 0f || rect.height() <= 0f || rect.left > rect.right || rect.top > rect.bottom) {
            return Pair(rect.left, rect.top)
        }

        // Clamp to rectangle interior
        val clampedX = safeCoerce(tx, rect.left, rect.right)
        val clampedY = safeCoerce(ty, rect.top, rect.bottom)

        // If inside the rectangle, snap to the nearest boundary edge
        if (clampedX > rect.left && clampedX < rect.right && clampedY > rect.top && clampedY < rect.bottom) {
            val dLeft = clampedX - rect.left
            val dRight = rect.right - clampedX
            val dTop = clampedY - rect.top
            val dBottom = rect.bottom - clampedY

            val minD = min(min(dLeft, dRight), min(dTop, dBottom))
            return when (minD) {
                dLeft -> Pair(rect.left, clampedY)
                dRight -> Pair(rect.right, clampedY)
                dTop -> Pair(clampedX, rect.top)
                else -> Pair(clampedX, rect.bottom)
            }
        }

        return Pair(clampedX, clampedY)
    }

    /**
     * Renders leader lines, barcode dashed outlines, and anchor pins on the canvas.
     */
    fun drawCalloutAtmosphere(
        scope: DrawScope,
        solvedItems: List<SolvedCalloutPosition>,
        config: OverlayAppearance
    ) {
        val baseColor = if (config.leaderLineColorHex.isNotBlank()) {
            parseHexColor(config.leaderLineColorHex)
        } else {
            parseHexColor(config.borderColorHex)
        }

        with(scope) {
            for (item in solvedItems) {
                val alpha = item.tracked.alpha
                if (alpha <= 0.05f) continue

                val box = item.tracked.currentBox
                val strokeColor = baseColor.copy(alpha = 0.85f * alpha)
                val glowColor = baseColor.copy(alpha = 0.25f * alpha)

                // 1. Barcode Dashed Perimeter Outline
                if (config.showBarcodeDashedOutline && box.width() > 0 && box.height() > 0) {
                    val cornerRadius = 8.dp.toPx()
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)

                    // Subtle background fill over barcode to highlight it
                    drawRoundRect(
                        color = baseColor.copy(alpha = 0.06f * alpha),
                        topLeft = Offset(box.left, box.top),
                        size = Size(box.width(), box.height()),
                        cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                    )

                    // Dashed outline
                    drawRoundRect(
                        color = strokeColor,
                        topLeft = Offset(box.left, box.top),
                        size = Size(box.width(), box.height()),
                        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                        style = Stroke(width = 2.dp.toPx(), pathEffect = dashEffect)
                    )

                    // Sharp Corner reticle brackets
                    drawReticleCorners(box, strokeColor)
                }

                // 2. Bonded Leader Line connecting barcode to data box
                if (item.hasLeaderLine) {
                    val startOffset = Offset(item.barcodeAnchorX, item.barcodeAnchorY)
                    val endOffset = Offset(item.cardAnchorX, item.cardAnchorY)
                    val lineWidth = config.leaderLineWidthDp.dp.toPx()

                    when (config.leaderLineStyle) {
                        LeaderLineStyle.THIN_SOLID -> {
                            // Soft glow underlay
                            drawLine(
                                color = glowColor,
                                start = startOffset,
                                end = endOffset,
                                strokeWidth = lineWidth * 2.5f
                            )
                            // Crisp solid line
                            drawLine(
                                color = strokeColor,
                                start = startOffset,
                                end = endOffset,
                                strokeWidth = lineWidth
                            )
                        }

                        LeaderLineStyle.DASHED -> {
                            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                            drawLine(
                                color = strokeColor,
                                start = startOffset,
                                end = endOffset,
                                strokeWidth = lineWidth,
                                pathEffect = dashEffect
                            )
                        }

                        LeaderLineStyle.ELBOW_JOINT -> {
                            val elbowPath = Path().apply {
                                moveTo(startOffset.x, startOffset.y)
                                // Orthogonal 90-degree bend: horizontal then vertical
                                lineTo(endOffset.x, startOffset.y)
                                lineTo(endOffset.x, endOffset.y)
                            }
                            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                            drawPath(
                                path = elbowPath,
                                color = strokeColor,
                                style = Stroke(width = lineWidth, pathEffect = dashEffect)
                            )
                        }
                    }

                    // 3. Terminal Dot on Card edge
                    drawCircle(
                        color = strokeColor,
                        radius = 2.5.dp.toPx(),
                        center = endOffset
                    )
                }

                // 4. Anchor Pin on Barcode
                if (config.showAnchorDot) {
                    val anchorCenter = Offset(item.barcodeAnchorX, item.barcodeAnchorY)

                    // Outer pulse ring
                    drawCircle(
                        color = glowColor,
                        radius = 6.dp.toPx(),
                        center = anchorCenter
                    )
                    // Inner solid pin dot
                    drawCircle(
                        color = strokeColor,
                        radius = 3.dp.toPx(),
                        center = anchorCenter
                    )
                }
            }
        }
    }
}
