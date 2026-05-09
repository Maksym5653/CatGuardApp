package com.catguard.app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of camera preview.
 * Shows a pulsing green border when cat is detected.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var catDetected = false
    private var alpha = 255

    private val detectedPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.GREEN
        isAntiAlias = true
    }

    private val cornerRadius = 24f

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (catDetected) {
                alpha = if (alpha > 50) alpha - 30 else 255
                detectedPaint.alpha = alpha
                invalidate()
                postDelayed(this, 60)
            }
        }
    }

    fun showDetection(detected: Boolean) {
        catDetected = detected
        if (detected) {
            removeCallbacks(pulseRunnable)
            post(pulseRunnable)
        } else {
            removeCallbacks(pulseRunnable)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (catDetected) {
            val margin = 20f
            canvas.drawRoundRect(
                margin, margin,
                width - margin, height - margin,
                cornerRadius, cornerRadius,
                detectedPaint
            )
        }
    }
}
