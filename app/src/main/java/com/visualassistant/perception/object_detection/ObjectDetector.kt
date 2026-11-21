package com.visualassistant.perception.object_detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.visualassistant.utils.Constants
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ObjectDetector wraps a TFLite EfficientDet-Lite0 / SSD model.
 *
 * Input:  RGB Bitmap (any aspect ratio)
 * Output: List<DetectedObject> with bounding boxes in original image space
 *
 * Key improvements over naive implementation:
 *  1. Letterbox preprocessing — preserves aspect ratio before scaling to 320×320,
 *     padding with neutral grey (127,127,127). Eliminates distortion that was the
 *     single biggest cause of poor detection accuracy.
 *  2. Box coordinate unmapping — after letterboxing, model boxes are in [0,1]
 *     letterboxed space; we unmap them back to [0,1] original-image space so that
 *     all downstream position/size logic is correct.
 *  3. Non-Maximum Suppression (NMS) — removes redundant overlapping detections for
 *     the same class, returning only the most confident one per cluster.
 *  4. "???" label filtering — COCO label file has placeholder entries for unused
 *     class indices; these are silently skipped.
 *  5. Thread safety — inference is serialized under interpreterLock.
 *
 * Model must be placed at: app/src/main/assets/efficientdet_lite0.tflite
 * Labels file:             app/src/main/assets/coco_labels.txt
 *
 * If the model file is missing, returns an empty list (graceful degradation).
 */
class ObjectDetector(context: Context) {

    private var interpreter: Interpreter? = null
    private val interpreterLock = Any()

    // Held as AutoCloseable so we never reference the GpuDelegate class directly
    // (avoids NoClassDefFoundError on devices/builds where the GPU delegate .so is absent)
    private var gpuDelegateHandle: AutoCloseable? = null
    private val labels: List<String>
    private val inputSize = Constants.OBJECT_DETECTION_INPUT_SIZE

    // EfficientDet-Lite0 output shapes
    // Output 0: [1, 25, 4]  - boxes (y1, x1, y2, x2 normalized in letterboxed space)
    // Output 1: [1, 25]     - class indices
    // Output 2: [1, 25]     - scores
    // Output 3: [1]         - num detections
    private val MAX_DETECTIONS = 25

    // Letterbox state — set by letterboxBitmap(), read by unmapBox()
    // These are only written from detect() which is serialized under interpreterLock,
    // so they are safe.
    private var lbScale = 1f      // scale factor applied to source bitmap
    private var lbPadX = 0        // horizontal padding (pixels) added each side
    private var lbPadY = 0        // vertical padding (pixels) added each side
    private var lbScaledW = 0     // width of the scaled source inside the 320×320 canvas
    private var lbScaledH = 0     // height of the scaled source inside the 320×320 canvas

    init {
        labels = loadLabels(context)
        interpreter = buildInterpreter(context)
    }

    private fun loadLabels(context: Context): List<String> {
        return try {
            context.assets.open(Constants.OBJECT_DETECTION_LABELS).bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load labels: ${e.message}")
            emptyList()
        }
    }

    private fun buildInterpreter(context: Context): Interpreter? {
        return try {
            val modelBuffer = FileUtil.loadMappedFile(context, Constants.OBJECT_DETECTION_MODEL)

            val options = Interpreter.Options().apply {
                numThreads = 4
                // Load GpuDelegate dynamically — catches NoClassDefFoundError (Error subclass)
                // which is NOT caught by a plain `catch (e: Exception)` block.
                try {
                    val delegateClass = Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
                    val delegate = delegateClass.getDeclaredConstructor().newInstance()
                    val addDelegateMethod = Interpreter.Options::class.java
                        .getMethod("addDelegate", Class.forName("org.tensorflow.lite.Delegate"))
                    addDelegateMethod.invoke(this, delegate)
                    gpuDelegateHandle = delegate as AutoCloseable
                    Log.d(TAG, "GPU delegate enabled via reflection")
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU delegate not available, using CPU: ${t.message}")
                }
            }
            Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model '${Constants.OBJECT_DETECTION_MODEL}': ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Letterbox helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scale [bitmap] to fit inside a [inputSize]×[inputSize] square while preserving
     * aspect ratio, padding the remaining area with neutral grey (127,127,127).
     *
     * Also updates lbScale / lbPadX / lbPadY / lbScaledW / lbScaledH so that
     * [unmapBox] can convert model-output box coordinates back to original-image space.
     */
    private fun letterboxBitmap(bitmap: Bitmap): Bitmap {
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()

        lbScale  = inputSize / maxOf(srcW, srcH)
        lbScaledW = (srcW * lbScale).toInt().coerceAtMost(inputSize)
        lbScaledH = (srcH * lbScale).toInt().coerceAtMost(inputSize)
        lbPadX   = (inputSize - lbScaledW) / 2
        lbPadY   = (inputSize - lbScaledH) / 2

        val output  = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas  = Canvas(output)
        val bgPaint = Paint().apply { color = Color.rgb(127, 127, 127) }
        canvas.drawRect(0f, 0f, inputSize.toFloat(), inputSize.toFloat(), bgPaint)

        val scaled = Bitmap.createScaledBitmap(bitmap, lbScaledW, lbScaledH, true)
        canvas.drawBitmap(scaled, lbPadX.toFloat(), lbPadY.toFloat(), null)
        if (scaled !== bitmap) scaled.recycle()

        return output
    }

    /**
     * Convert a bounding box from [0,1] letterboxed-image space back to
     * [0,1] original-image space.
     *
     * Model output: normalized coords in the 320×320 letterboxed image.
     * We scale to pixel coords, subtract the padding offset, then divide by
     * the scaled source dimensions to get back to [0,1] in source space.
     *
     * @param box FloatArray [y1, x1, y2, x2] from model output
     * @return RectF (left, top, right, bottom) normalized to original image
     */
    private fun unmapBox(box: FloatArray): RectF {
        val left   = ((box[1] * inputSize - lbPadX) / lbScaledW).coerceIn(0f, 1f)
        val top    = ((box[0] * inputSize - lbPadY) / lbScaledH).coerceIn(0f, 1f)
        val right  = ((box[3] * inputSize - lbPadX) / lbScaledW).coerceIn(0f, 1f)
        val bottom = ((box[2] * inputSize - lbPadY) / lbScaledH).coerceIn(0f, 1f)
        return RectF(left, top, right, bottom)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Non-Maximum Suppression
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apply class-aware IoU-based NMS.
     *
     * Boxes are sorted descending by confidence. For each kept box, any subsequent
     * box of the **same class** whose IoU exceeds [iouThreshold] is suppressed.
     * Boxes of different classes are never suppressed against each other.
     */
    private fun applyNMS(
        detections: List<DetectedObject>,
        iouThreshold: Float = Constants.NMS_IOU_THRESHOLD
    ): List<DetectedObject> {
        val sorted     = detections.sortedByDescending { it.confidence }
        val kept       = mutableListOf<DetectedObject>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (sorted[i].label == sorted[j].label &&
                    sorted[i].iouWith(sorted[j]) > iouThreshold
                ) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run object detection on a bitmap.
     *
     * @param bitmap              Input bitmap (any size / aspect ratio)
     * @param confidenceThreshold Minimum score to include a detection
     * @return NMS-filtered list of detected objects; bounding boxes are in
     *         [0,1] coordinates relative to [bitmap]'s original dimensions.
     */
    fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float = Constants.OBJECT_DETECTION_CONFIDENCE
    ): List<DetectedObject> {
        val interp = interpreter ?: return emptyList()
        if (labels.isEmpty()) return emptyList()

        return try {
            synchronized(interpreterLock) {
                // 1. Letterbox — preserves aspect ratio, eliminates distortion
                val inputBitmap = letterboxBitmap(bitmap)
                val inputBuffer = bitmapToByteBuffer(inputBitmap)
                if (inputBitmap !== bitmap) inputBitmap.recycle()

                // 2. Allocate output tensors
                val outputBoxes     = Array(1) { Array(MAX_DETECTIONS) { FloatArray(4) } }
                val outputClasses   = Array(1) { FloatArray(MAX_DETECTIONS) }
                val outputScores    = Array(1) { FloatArray(MAX_DETECTIONS) }
                val numDetections   = FloatArray(1)

                val outputs = mapOf(
                    0 to outputBoxes,
                    1 to outputClasses,
                    2 to outputScores,
                    3 to numDetections
                )

                // 3. Run inference
                interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

                // 4. Parse raw results
                val rawResults = mutableListOf<DetectedObject>()
                val count = numDetections[0].toInt().coerceAtMost(MAX_DETECTIONS)

                for (i in 0 until count) {
                    val score = outputScores[0][i]
                    if (score < confidenceThreshold) continue

                    val classIdx = outputClasses[0][i].toInt()
                    val label    = if (classIdx < labels.size) labels[classIdx] else "unknown"

                    // Skip placeholder / unknown labels
                    if (label == "???" || label == "unknown" || label.isBlank()) continue

                    // 5. Unmap box from letterboxed space → original image space
                    val box   = outputBoxes[0][i]
                    val rectF = unmapBox(box)

                    // Skip degenerate boxes (zero area after unmapping)
                    if (rectF.width() <= 0f || rectF.height() <= 0f) continue

                    rawResults.add(
                        DetectedObject(
                            label        = label,
                            confidence   = score,
                            boundingBox  = rectF,
                            frameWidth   = bitmap.width,
                            frameHeight  = bitmap.height
                        )
                    )
                }

                // 6. Apply NMS to remove duplicate overlapping boxes
                val results = applyNMS(rawResults)

                if (results.isNotEmpty()) {
                    Log.d(TAG, "Detected ${results.size} objects after NMS: " +
                        results.map { "${it.label} (${(it.confidence * 100).toInt()}%)" })
                }
                results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Convert a 320×320 ARGB_8888 bitmap to a flat uint8 RGB ByteBuffer
     * suitable for EfficientDet-Lite0 input.
     */
    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            byteBuffer.put(((pixel shr 16) and 0xFF).toByte()) // R
            byteBuffer.put(((pixel shr 8)  and 0xFF).toByte()) // G
            byteBuffer.put((pixel          and 0xFF).toByte()) // B
        }
        return byteBuffer
    }

    /**
     * Release TFLite resources.
     */
    fun close() {
        synchronized(interpreterLock) {
            interpreter?.close()
            gpuDelegateHandle?.close()
            interpreter = null
            gpuDelegateHandle = null
        }
    }

    companion object {
        private const val TAG = "ObjectDetector"
    }
}
