package com.trio.trashlens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Page { SCAN, HISTORY, ABOUT }

    private data class ScanRecord(
        val id: Long,
        val imagePath: String,
        val label: String,
        val confidence: Float,
        val timestamp: Long
    )

    private lateinit var pageScan: View
    private lateinit var pageHistory: View
    private lateinit var pageAbout: View
    private lateinit var previewView: PreviewView
    private lateinit var tvCameraStatus: TextView
    private lateinit var btnGrantCamera: View
    private lateinit var btnCaptureAndScan: MaterialButton
    private lateinit var progressScan: ProgressBar
    private lateinit var resultCard: View
    private lateinit var ivResultImage: ImageView
    private lateinit var tvResultCategory: TextView
    private lateinit var tvResultConfidence: TextView
    private lateinit var tvResultScores: TextView
    private lateinit var tvResultDisclaimer: TextView
    private lateinit var navScan: View
    private lateinit var navHistory: View
    private lateinit var navAbout: View
    private lateinit var iconScan: TextView
    private lateinit var iconHistory: TextView
    private lateinit var iconAbout: TextView
    private lateinit var labelScan: TextView
    private lateinit var labelHistory: TextView
    private lateinit var labelAbout: TextView
    private lateinit var tvHistoryCount: TextView
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var historyListContainer: LinearLayout
    private lateinit var btnClearHistory: View

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var currentPage: Page? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageClassifier: TfliteImageClassifier? = null
    private var isCapturing = false
    private var resultBitmap: Bitmap? = null
    private val scanHistory = mutableListOf<ScanRecord>()

    private val historyPrefs by lazy {
        getSharedPreferences("trashlens_scan_history", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.background_navy)
        window.navigationBarColor = getColor(R.color.surface_primary)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val appRoot = findViewById<View>(R.id.appRoot)
        ViewCompat.setOnApplyWindowInsetsListener(appRoot) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                systemBars.bottom
            )
            windowInsets
        }

        bindViews()
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                btnGrantCamera.visibility = View.GONE
                startCamera()
            } else {
                showCameraPermissionNeeded()
            }
        }

        loadHistory()
        setupListeners()
        renderHistory()
        showPage(Page.SCAN)
    }

    private fun bindViews() {
        pageScan = findViewById(R.id.pageScan)
        pageHistory = findViewById(R.id.pageHistory)
        pageAbout = findViewById(R.id.pageAbout)
        previewView = findViewById(R.id.cameraPreview)
        tvCameraStatus = findViewById(R.id.tvCameraStatus)
        btnGrantCamera = findViewById(R.id.btnGrantCamera)
        btnCaptureAndScan = findViewById(R.id.btnCaptureAndScan)
        progressScan = findViewById(R.id.progressScan)
        resultCard = findViewById(R.id.resultCard)
        ivResultImage = findViewById(R.id.ivResultImage)
        tvResultCategory = findViewById(R.id.tvResultCategory)
        tvResultConfidence = findViewById(R.id.tvResultConfidence)
        tvResultScores = findViewById(R.id.tvResultScores)
        tvResultDisclaimer = findViewById(R.id.tvResultDisclaimer)
        navScan = findViewById(R.id.navScan)
        navHistory = findViewById(R.id.navHistory)
        navAbout = findViewById(R.id.navAbout)
        iconScan = findViewById(R.id.iconScan)
        iconHistory = findViewById(R.id.iconHistory)
        iconAbout = findViewById(R.id.iconAbout)
        labelScan = findViewById(R.id.labelScan)
        labelHistory = findViewById(R.id.labelHistory)
        labelAbout = findViewById(R.id.labelAbout)
        tvHistoryCount = findViewById(R.id.tvHistoryCount)
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty)
        historyListContainer = findViewById(R.id.historyListContainer)
        btnClearHistory = findViewById(R.id.btnClearHistory)
    }

    private fun setupListeners() {
        navScan.setOnClickListener { showPage(Page.SCAN) }
        navHistory.setOnClickListener { showPage(Page.HISTORY) }
        navAbout.setOnClickListener { showPage(Page.ABOUT) }
        btnCaptureAndScan.setOnClickListener { captureAndClassify() }
        btnGrantCamera.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        btnClearHistory.setOnClickListener { confirmClearHistory() }
    }

    private fun showPage(page: Page) {
        if (currentPage != page && currentPage == Page.SCAN) stopCamera()
        currentPage = page
        pageScan.visibility = if (page == Page.SCAN) View.VISIBLE else View.GONE
        pageHistory.visibility = if (page == Page.HISTORY) View.VISIBLE else View.GONE
        pageAbout.visibility = if (page == Page.ABOUT) View.VISIBLE else View.GONE

        setNavigationSelection(page)
        if (page == Page.SCAN) {
            if (hasCameraPermission()) startCamera() else requestCameraPermissionIfNeeded()
        } else if (page == Page.HISTORY) {
            renderHistory()
        }
    }

    private fun setNavigationSelection(page: Page) {
        val activeBg = getColor(R.color.surface_chip)
        val inactiveBg = android.graphics.Color.TRANSPARENT
        fun navBackground(color: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(15).toFloat()
        }
        navScan.background = navBackground(if (page == Page.SCAN) activeBg else inactiveBg)
        navHistory.background = navBackground(if (page == Page.HISTORY) activeBg else inactiveBg)
        navAbout.background = navBackground(if (page == Page.ABOUT) activeBg else inactiveBg)
        val active = getColor(R.color.accent_teal)
        val inactive = getColor(R.color.text_muted)
        iconScan.setTextColor(if (page == Page.SCAN) active else inactive)
        iconHistory.setTextColor(if (page == Page.HISTORY) active else inactive)
        iconAbout.setTextColor(if (page == Page.ABOUT) active else inactive)
        labelScan.setTextColor(if (page == Page.SCAN) active else inactive)
        labelHistory.setTextColor(if (page == Page.HISTORY) active else inactive)
        labelAbout.setTextColor(if (page == Page.ABOUT) active else inactive)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermissionIfNeeded() {
        tvCameraStatus.text = "Akses kamera dibutuhkan untuk memindai sampah."
        btnGrantCamera.visibility = View.VISIBLE
        btnCaptureAndScan.isEnabled = false
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showCameraPermissionNeeded() {
        tvCameraStatus.text = "Izin kamera belum diberikan. Izinkan agar bisa mulai scan."
        btnGrantCamera.visibility = View.VISIBLE
        btnCaptureAndScan.isEnabled = false
    }

    private fun startCamera() {
        if (currentPage != Page.SCAN || !hasCameraPermission()) return
        if (cameraProvider != null && imageCapture != null) return

        tvCameraStatus.text = "Menyiapkan kamera..."
        btnCaptureAndScan.isEnabled = false
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (currentPage != Page.SCAN || isFinishing || isDestroyed) return@addListener
            try {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(92)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                cameraProvider = provider
                imageCapture = capture
                btnGrantCamera.visibility = View.GONE
                btnCaptureAndScan.isEnabled = true
                tvCameraStatus.text = "Kamera aktif · arahkan ke satu objek"
            } catch (e: Exception) {
                cameraProvider = null
                imageCapture = null
                btnCaptureAndScan.isEnabled = false
                tvCameraStatus.text = "Kamera tidak dapat dibuka: ${e.localizedMessage ?: "periksa perangkat"}"
                btnGrantCamera.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
            // Nothing else to release.
        }
        cameraProvider = null
        imageCapture = null
    }

    private fun captureAndClassify() {
        if (!hasCameraPermission()) {
            requestCameraPermissionIfNeeded()
            return
        }
        val capture = imageCapture
        if (capture == null) {
            tvCameraStatus.text = "Kamera belum siap. Tunggu sebentar lalu coba lagi."
            startCamera()
            return
        }
        if (isCapturing) return

        val outputDirectory = File(filesDir, "scan_history")
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            Toast.makeText(this, "Tidak dapat menyiapkan penyimpanan foto.", Toast.LENGTH_LONG).show()
            return
        }
        val imageFile = File(outputDirectory, "scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(imageFile).build()

        isCapturing = true
        btnCaptureAndScan.isEnabled = false
        btnCaptureAndScan.text = "MEMINDAI SAMPAH..."
        progressScan.visibility = View.VISIBLE
        tvCameraStatus.text = "Mengambil foto dan menganalisis..."
        resultCard.visibility = View.GONE

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    inferenceExecutor.execute {
                        var bitmap: Bitmap? = null
                        try {
                            val decodedBitmap = decodeImageFile(imageFile, maxSide = 1600)
                            bitmap = decodedBitmap
                            val activeClassifier = imageClassifier
                                ?: TfliteImageClassifier(applicationContext).also {
                                    imageClassifier = it
                                }
                            val prediction = activeClassifier.predict(decodedBitmap)
                            val record = ScanRecord(
                                id = System.currentTimeMillis(),
                                imagePath = imageFile.absolutePath,
                                label = prediction.label,
                                confidence = prediction.confidence,
                                timestamp = System.currentTimeMillis()
                            )
                            val finalBitmap = decodedBitmap
                            runOnUiThread {
                                if (isFinishing || isDestroyed) {
                                    finalBitmap.recycle()
                                    return@runOnUiThread
                                }
                                resultBitmap?.let { old ->
                                    ivResultImage.setImageDrawable(null)
                                    if (!old.isRecycled) old.recycle()
                                }
                                resultBitmap = finalBitmap
                                ivResultImage.setImageBitmap(finalBitmap)
                                tvResultCategory.text = displayWasteLabel(prediction.label)
                                tvResultConfidence.text = String.format(
                                    Locale("id", "ID"),
                                    "Confidence: %.2f%%",
                                    prediction.confidence * 100f
                                )
                                tvResultScores.text = prediction.scores.entries
                                    .sortedByDescending { it.value }
                                    .take(3)
                                    .joinToString("\n") { entry ->
                                        String.format(
                                            Locale("id", "ID"),
                                            "%s  ·  %.2f%%",
                                            displayWasteLabel(entry.key),
                                            entry.value * 100f
                                        )
                                    }
                                tvResultDisclaimer.text = if (prediction.confidence < 0.70f) {
                                    "Prediksi belum meyakinkan. Coba pindai ulang dengan satu objek dan pencahayaan yang baik."
                                } else {
                                    "AI dapat salah mengenali sampah. Periksa kembali sebelum memilah."
                                }
                                resultCard.visibility = View.VISIBLE
                                addHistoryRecord(record)
                                tvCameraStatus.text = "Scan selesai · siap untuk objek berikutnya"
                                progressScan.visibility = View.GONE
                                btnCaptureAndScan.isEnabled = true
                                btnCaptureAndScan.text = "●  AMBIL FOTO & KLASIFIKASIKAN"
                                isCapturing = false
                            }
                        } catch (e: Exception) {
                            bitmap?.let { if (!it.isRecycled) it.recycle() }
                            imageFile.delete()
                            runOnUiThread {
                                tvCameraStatus.text = "Klasifikasi gagal: ${e.localizedMessage ?: "model tidak dapat dijalankan"}"
                                progressScan.visibility = View.GONE
                                btnCaptureAndScan.isEnabled = hasCameraPermission() && imageCapture != null
                                btnCaptureAndScan.text = "COBA SCAN LAGI"
                                isCapturing = false
                                Toast.makeText(
                                    this@MainActivity,
                                    "Gagal menganalisis foto. Coba lagi.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    imageFile.delete()
                    isCapturing = false
                    progressScan.visibility = View.GONE
                    btnCaptureAndScan.isEnabled = true
                    btnCaptureAndScan.text = "COBA SCAN LAGI"
                    tvCameraStatus.text = "Gagal mengambil foto: ${exception.message ?: "coba lagi"}"
                }
            }
        )
    }

    private fun decodeImageFile(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Foto yang ditangkap tidak valid." }

        var sampleSize = 1
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDimension / sampleSize > maxSide) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("Tidak dapat membaca foto dari kamera.")

        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        if (matrix.isIdentity) return decoded
        val oriented = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true
        )
        if (oriented !== decoded) decoded.recycle()
        return oriented
    }

    private fun displayWasteLabel(label: String): String = when (label) {
        "cardboard" -> "Kardus / Cardboard"
        "glass" -> "Kaca / Glass"
        "metal" -> "Logam / Metal"
        "paper" -> "Kertas / Paper"
        "plastic" -> "Plastik / Plastic"
        "trash" -> "Sampah lainnya / Trash"
        else -> label.replaceFirstChar { it.uppercase(Locale.getDefault()) }
    }

    private fun addHistoryRecord(record: ScanRecord) {
        scanHistory.removeAll { it.id == record.id }
        scanHistory.add(0, record)
        while (scanHistory.size > 50) {
            val removed = scanHistory.removeAt(scanHistory.lastIndex)
            File(removed.imagePath).delete()
        }
        saveHistory()
        renderHistory()
    }

    private fun loadHistory() {
        scanHistory.clear()
        val raw = historyPrefs.getString("items", "[]") ?: "[]"
        try {
            val json = JSONArray(raw)
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                val record = ScanRecord(
                    id = item.optLong("id"),
                    imagePath = item.optString("imagePath"),
                    label = item.optString("label"),
                    confidence = item.optDouble("confidence", 0.0).toFloat(),
                    timestamp = item.optLong("timestamp")
                )
                if (record.label.isNotBlank() && File(record.imagePath).exists()) {
                    scanHistory.add(record)
                }
            }
        } catch (_: Exception) {
            scanHistory.clear()
        }
    }

    private fun saveHistory() {
        val array = JSONArray()
        scanHistory.take(50).forEach { record ->
            array.put(JSONObject().apply {
                put("id", record.id)
                put("imagePath", record.imagePath)
                put("label", record.label)
                put("confidence", record.confidence.toDouble())
                put("timestamp", record.timestamp)
            })
        }
        historyPrefs.edit().putString("items", array.toString()).apply()
    }

    private fun renderHistory() {
        if (!::historyListContainer.isInitialized) return
        historyListContainer.removeAllViews()
        tvHistoryCount.text = "${scanHistory.size} hasil tersimpan"
        tvHistoryEmpty.visibility = if (scanHistory.isEmpty()) View.VISIBLE else View.GONE
        btnClearHistory.isEnabled = scanHistory.isNotEmpty()

        scanHistory.forEach { record ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = dp(1).toFloat()
                strokeWidth = dp(1)
                strokeColor = getColor(R.color.divider_color)
                setCardBackgroundColor(getColor(R.color.surface_primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(11), dp(12), dp(11))
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Foto ${displayWasteLabel(record.label)}"
                setBackgroundColor(getColor(R.color.background_navy))
            }
            val file = File(record.imagePath)
            if (file.exists()) {
                try {
                    image.setImageBitmap(decodeImageFile(file, maxSide = 240))
                } catch (_: Exception) {
                    image.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            } else {
                image.setImageResource(android.R.drawable.ic_menu_report_image)
            }
            row.addView(image)

            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(12) }
            }
            textColumn.addView(TextView(this).apply {
                text = displayWasteLabel(record.label)
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            textColumn.addView(TextView(this).apply {
                text = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID")).format(Date(record.timestamp))
                setTextColor(getColor(R.color.text_muted))
                textSize = 11f
                setPadding(0, dp(4), 0, dp(5))
            })
            textColumn.addView(TextView(this).apply {
                text = String.format(Locale("id", "ID"), "Confidence %.2f%%", record.confidence * 100f)
                setTextColor(getColor(R.color.accent_teal))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            row.addView(textColumn)
            card.addView(
                row,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            historyListContainer.addView(card)
        }
    }

    private fun confirmClearHistory() {
        if (scanHistory.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus semua riwayat?")
            .setMessage("Foto dan catatan hasil scan yang tersimpan di aplikasi akan dihapus.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                scanHistory.forEach { File(it.imagePath).delete() }
                scanHistory.clear()
                saveHistory()
                renderHistory()
                Toast.makeText(this, "Riwayat scan telah dihapus.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopCamera()
        inferenceExecutor.shutdownNow()
        imageClassifier?.close()
        imageClassifier = null
        resultBitmap?.let { if (!it.isRecycled) it.recycle() }
        resultBitmap = null
        super.onDestroy()
    }
}
