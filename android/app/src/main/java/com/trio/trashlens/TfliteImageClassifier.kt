package com.trio.trashlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/** Result returned by the on-device TrashNet classifier. */
data class WastePrediction(
    val label: String,
    val confidence: Float,
    val scores: Map<String, Float>
)

/**
 * Runs the converted EfficientNetB0 TrashNet model on-device.
 *
 * The exported Keras model includes EfficientNet's own rescaling layer,
 * so this class passes RGB channel values in the 0..255 range, matching
 * the input path used to evaluate the Keras model in Colab.
 */
class TfliteImageClassifier(
    context: Context,
    modelAssetName: String = "trashnet_model_final.tflite"
) : Closeable {

    companion object {
        const val INPUT_SIZE = 224
        val LABELS = listOf(
            "cardboard",
            "glass",
            "metal",
            "paper",
            "plastic",
            "trash"
        )
    }

    private val appContext = context.applicationContext
    private val interpreter: Interpreter

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(loadModelFile(modelAssetName), options)

        val inputShape = interpreter.getInputTensor(0).shape()
        val outputShape = interpreter.getOutputTensor(0).shape()
        require(inputShape.contentEquals(intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3))) {
            "Bentuk input model tidak sesuai: ${inputShape.contentToString()}"
        }
        require(outputShape.size == 2 && outputShape[0] == 1 && outputShape[1] == LABELS.size) {
            "Bentuk output model tidak sesuai: ${outputShape.contentToString()}"
        }
    }

    private fun loadModelFile(assetName: String): ByteBuffer {
        val assetFile = appContext.assets.openFd(assetName)
        assetFile.use { afd ->
            FileInputStream(afd.fileDescriptor).use { input ->
                return input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
        }
    }

    fun predict(bitmap: Bitmap): WastePrediction {
        val resized = Bitmap.createScaledBitmap(
            bitmap,
            INPUT_SIZE,
            INPUT_SIZE,
            true
        )

        try {
            val inputBuffer = ByteBuffer.allocateDirect(
                INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES
            ).order(ByteOrder.nativeOrder())

            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(
                pixels,
                0,
                INPUT_SIZE,
                0,
                0,
                INPUT_SIZE,
                INPUT_SIZE
            )

            // Input is NHWC RGB float32 with raw pixel scale 0..255.
            for (pixel in pixels) {
                inputBuffer.putFloat(Color.red(pixel).toFloat())
                inputBuffer.putFloat(Color.green(pixel).toFloat())
                inputBuffer.putFloat(Color.blue(pixel).toFloat())
            }
            inputBuffer.rewind()

            val output = Array(1) { FloatArray(LABELS.size) }
            interpreter.run(inputBuffer, output)

            val probabilities = normalizeProbabilities(output[0])
            val bestIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
            val scoreMap = LABELS.indices.associate { index ->
                LABELS[index] to probabilities[index]
            }

            return WastePrediction(
                label = LABELS[bestIndex],
                confidence = probabilities[bestIndex],
                scores = scoreMap
            )
        } finally {
            if (resized !== bitmap) {
                resized.recycle()
            }
        }
    }

    private fun normalizeProbabilities(values: FloatArray): FloatArray {
        val allFinite = values.all { it.isFinite() }
        require(allFinite) { "Model menghasilkan nilai yang tidak valid." }

        // The exported model normally already emits softmax probabilities.
        val sum = values.sum()
        val alreadyProbabilities = values.all { it in 0f..1f } && sum in 0.95f..1.05f
        if (alreadyProbabilities) return values.copyOf()

        // Defensive fallback for a model export whose output is logits.
        val maxValue = values.maxOrNull() ?: 0f
        val expValues = values.map { kotlin.math.exp((it - maxValue).toDouble()).toFloat() }
        val expSum = expValues.sum().coerceAtLeast(1e-12f)
        return FloatArray(values.size) { expValues[it] / expSum }
    }

    override fun close() {
        interpreter.close()
    }
}
