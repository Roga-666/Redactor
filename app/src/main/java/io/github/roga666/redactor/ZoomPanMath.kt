package io.github.roga666.redactor

/**
 * Coordinates in view pixels. Two-finger gestures adjust the preview only:
 * no image pixels or stored redaction rectangles are modified.
 */
object ZoomPanMath {
    const val MAX_ZOOM = 6f

    fun nextZoom(current: Float, pinchFactor: Float): Float =
        (current * pinchFactor).coerceIn(1f, MAX_ZOOM)

    /** Keep the image anchored under the moving fingers during a pinch. */
    fun anchoredPan(
        previousPan: Float, gestureCentroid: Float, viewSize: Float,
        motion: Float, zoomRatio: Float
    ): Float = previousPan + motion +
        (gestureCentroid - viewSize / 2f - previousPan) * (1f - zoomRatio)

    /** Prevent panning off-screen; center a dimension that fits the view. */
    fun limitPan(proposed: Float, viewSize: Float, fittedImageSize: Float, zoom: Float): Float {
        val overflow = ((fittedImageSize * zoom - viewSize) / 2f).coerceAtLeast(0f)
        return proposed.coerceIn(-overflow, overflow)
    }
}
