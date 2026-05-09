package com.catguard.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * CatDetector wraps TFLite Task Vision ObjectDetector.
 *
 * MODEL: EfficientDet-Lite0 (COCO) — detects 80 classes including "cat".
 * Download from: https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1
 * Place the downloaded file in: app/src/main/assets/efficientdet_lite0.tflite
 *
 * Since the model is trained on generic COCO "cat" class, it will detect any cat.
 * The confidence threshold can be tuned — white fluffy cats may need lower threshold
 * in bright environments. Set SCORE_THRESHOLD between 0.3–0.6.
 */
class CatDetector(private val context: Context) {

    companion object {
        private const val TAG = "CatDetector"
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val SCORE_THRESHOLD = 0.40f   // Adjust: lower = more sensitive
        private const val MAX_RESULTS = 5
        private const val TARGET_LABEL = "cat"       // COCO class label
    }

    private var detector: ObjectDetector? = null

    /**
     * Call once before starting detection. Safe to call from background thread.
     */
    fun initialize() {
        try {
            val baseOptions = BaseOptions.builder()
                .setNumThreads(2)
                // Uncomment to use GPU delegate (faster, but not all devices support it):
                // .useGpu()
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(SCORE_THRESHOLD)
                .build()

            detector = ObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options)
            Log.i(TAG, "TFLite ObjectDetector initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector: ${e.message}", e)
            detector = null
        }
    }

    /**
     * Returns true if a cat is detected in the given bitmap.
     * Safe to call from background/inference thread (NOT main thread).
     */
    fun detectCat(bitmap: Bitmap): DetectionResult {
        val det = detector ?: return DetectionResult(false, 0f, "Detector not initialized")

        return try {
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val results: List<Detection> = det.detect(tensorImage)

            val catDetections = results.filter { detection ->
                detection.categories.any { category ->
                    category.label.lowercase().contains(TARGET_LABEL) &&
                            category.score >= SCORE_THRESHOLD
                }
            }

            if (catDetections.isNotEmpty()) {
                val bestScore = catDetections
                    .flatMap { it.categories }
                    .filter { it.label.lowercase().contains(TARGET_LABEL) }
                    .maxOf { it.score }
                DetectionResult(true, bestScore, "Cat detected (conf: ${"%.0f".format(bestScore * 100)}%)")
            } else {
                DetectionResult(false, 0f, "No cat in frame")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}", e)
            DetectionResult(false, 0f, "Detection error: ${e.message}")
        }
    }

    fun close() {
        detector?.close()
        detector = null
    }

    data class DetectionResult(
        val catDetected: Boolean,
        val confidence: Float,
        val message: String
    )
}
