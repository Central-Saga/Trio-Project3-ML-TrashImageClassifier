
package com.trio.trashlens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Page { HOME, SCAN, HISTORY, ABOUT }

    private data class ScanRecord(
        val id: Long,
        val imagePath: String,
        val label: String,
        val confidence: Float,
        val timestamp: Long
    )

    private lateinit var pageHome: View
    private lateinit var pageScan: View
    private lateinit var pageHistory: View
    private lateinit var pageAbout: View
    private lateinit var previewView: PreviewView
    private lateinit var tvCameraStatus: TextView
    private lateinit var btnGrantCamera: View
    private lateinit var btnCaptureAndScan: MaterialButton
    private lateinit var btnUploadImage: MaterialButton
    private lateinit var progressScan: ProgressBar
    private lateinit var scanLoadingCard: View
    private lateinit var tvScanLoadingMessage: TextView
    private var scanLoadingStartedAt: Long = 0L
    private var scanLoadingGeneration: Int = 0
    private val scanLoadingMinimumMs = 1800L
    private lateinit var resultCard: View
    private lateinit var ivResultImage: ImageView
    private lateinit var tvResultCategory: TextView
    private lateinit var tvResultConfidence: TextView
    private lateinit var tvResultScores: TextView
    private lateinit var tvResultDisclaimer: TextView
    private lateinit var navHome: View
    private lateinit var navScan: View
    private lateinit var navHistory: View
    private lateinit var navAbout: View
    private lateinit var iconHome: TextView
    private lateinit var iconScan: TextView
    private lateinit var iconHistory: TextView
    private lateinit var iconAbout: TextView
    private lateinit var labelHome: TextView
    private lateinit var labelScan: TextView
    private lateinit var labelHistory: TextView
    private lateinit var labelAbout: TextView
    private lateinit var tvHomeScanCount: TextView
    private lateinit var tvHistoryCount: TextView
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var historyListContainer: LinearLayout
    private lateinit var btnClearHistory: View

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var galleryPickerLauncher: ActivityResultLauncher<String>

    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var currentPage: Page? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageClassifier: TfliteImageClassifier? = null
    private var isCapturing = false
    private var resultBitmap: Bitmap? = null
    private var isCameraStarting = false

    private val scanHistory = mutableListOf<ScanRecord>()

    private val historyPrefs by lazy {
        getSharedPreferences("trashlens_scan_history", MODE_PRIVATE)
    }

    companion object {
        private const val PREF_CAMERA_REQUEST_COUNT = "camera_permission_request_count"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = getColor(R.color.background_navy)
        window.navigationBarColor = getColor(R.color.surface_primary)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

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
                tvCameraStatus.text = "Izin kamera diberikan. Menyiapkan kamera..."

                if (currentPage == Page.SCAN) {
                    startCamera()
                }
            } else {
                showCameraPermissionNeeded()

                val requestCount = historyPrefs.getInt(
                    PREF_CAMERA_REQUEST_COUNT,
                    0
                )
                val shouldShowRationale =
                    shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

                if (requestCount >= 2 && !shouldShowRationale) {
                    showCameraSettingsDialog()
                }
            }
        }

        galleryPickerLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null && !isCapturing) {
                classifyGalleryImage(uri)
            }
        }

        loadHistory()
        setupListeners()
        renderHistory()
        showPage(Page.HOME)
    }

    private fun bindViews() {
        pageHome = findViewById(R.id.pageHome)
        pageScan = findViewById(R.id.pageScan)
        pageHistory = findViewById(R.id.pageHistory)
        pageAbout = findViewById(R.id.pageAbout)

        previewView = findViewById(R.id.cameraPreview)
        tvCameraStatus = findViewById(R.id.tvCameraStatus)
        btnGrantCamera = findViewById(R.id.btnGrantCamera)
        btnCaptureAndScan = findViewById(R.id.btnCaptureAndScan)
        btnUploadImage = findViewById(R.id.btnUploadImage)
        progressScan = findViewById(R.id.progressScan)
        scanLoadingCard = findViewById(R.id.scanLoadingCard)
        tvScanLoadingMessage = findViewById(R.id.tvScanLoadingMessage)

        resultCard = findViewById(R.id.resultCard)
        ivResultImage = findViewById(R.id.ivResultImage)
        tvResultCategory = findViewById(R.id.tvResultCategory)
        tvResultConfidence = findViewById(R.id.tvResultConfidence)
        tvResultScores = findViewById(R.id.tvResultScores)
        tvResultDisclaimer = findViewById(R.id.tvResultDisclaimer)

        navHome = findViewById(R.id.navHome)
        navScan = findViewById(R.id.navScan)
        navHistory = findViewById(R.id.navHistory)
        navAbout = findViewById(R.id.navAbout)

        iconHome = findViewById(R.id.iconHome)
        iconScan = findViewById(R.id.iconScan)
        iconHistory = findViewById(R.id.iconHistory)
        iconAbout = findViewById(R.id.iconAbout)

        labelHome = findViewById(R.id.labelHome)
        labelScan = findViewById(R.id.labelScan)
        labelHistory = findViewById(R.id.labelHistory)
        labelAbout = findViewById(R.id.labelAbout)

        tvHomeScanCount = findViewById(R.id.tvHomeScanCount)
        tvHistoryCount = findViewById(R.id.tvHistoryCount)
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty)
        historyListContainer = findViewById(R.id.historyListContainer)
        btnClearHistory = findViewById(R.id.btnClearHistory)
    }

    private fun setupListeners() {
        navHome.setOnClickListener { showPage(Page.HOME) }
        navScan.setOnClickListener { showPage(Page.SCAN) }
        navHistory.setOnClickListener { showPage(Page.HISTORY) }
        navAbout.setOnClickListener { showPage(Page.ABOUT) }

        findViewById<View>(R.id.btnHomeScan).setOnClickListener {
            showPage(Page.SCAN)
        }

        btnCaptureAndScan.setOnClickListener { captureAndClassify() }
        btnUploadImage.setOnClickListener { launchGalleryPicker() }

        btnGrantCamera.setOnClickListener {
            if (hasCameraPermission()) {
                startCamera()
            } else {
                requestCameraPermissionIfNeeded()
            }
        }

        btnClearHistory.setOnClickListener { confirmClearHistory() }
    }

    private fun launchGalleryPicker() {
        if (isCapturing || isFinishing || isDestroyed) return

        try {
            galleryPickerLauncher.launch("image/*")
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Galeri tidak dapat dibuka. Coba lagi.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun pageView(page: Page): View = when (page) {
        Page.HOME -> pageHome
        Page.SCAN -> pageScan
        Page.HISTORY -> pageHistory
        Page.ABOUT -> pageAbout
    }

    /** Menghormati pengaturan reduced-motion Android. */
    private fun motionAnimationsEnabled(): Boolean {
        return try {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !ValueAnimator.areAnimatorsEnabled()
            ) {
                false
            } else {
                val animatorScale = Settings.Global.getFloat(
                    contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                )
                val transitionScale = Settings.Global.getFloat(
                    contentResolver,
                    Settings.Global.TRANSITION_ANIMATION_SCALE,
                    1f
                )
                val windowScale = Settings.Global.getFloat(
                    contentResolver,
                    Settings.Global.WINDOW_ANIMATION_SCALE,
                    1f
                )

                animatorScale > 0f && transitionScale > 0f && windowScale > 0f
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun showPage(page: Page) {
        val previousPage = currentPage

        if (previousPage != page && previousPage == Page.SCAN) {
            stopCamera()
        }

        val incoming = pageView(page)
        val outgoing = previousPage?.let { pageView(it) }

        currentPage = page
        setNavigationSelection(page)

        if (outgoing === incoming) {
            incoming.visibility = View.VISIBLE
        } else if (outgoing == null) {
            listOf(pageHome, pageScan, pageHistory, pageAbout).forEach { candidate ->
                candidate.animate().cancel()
                candidate.visibility =
                    if (candidate === incoming) View.VISIBLE else View.GONE
                candidate.alpha = 1f
                candidate.translationX = 0f
            }
        } else if (!motionAnimationsEnabled()) {
            outgoing.animate().cancel()
            outgoing.visibility = View.GONE
            outgoing.alpha = 1f
            outgoing.translationX = 0f
            incoming.animate().cancel()

            listOf(pageHome, pageScan, pageHistory, pageAbout).forEach { candidate ->
                if (candidate !== incoming) {
                    candidate.visibility = View.GONE
                    candidate.alpha = 1f
                    candidate.translationX = 0f
                }
            }

            incoming.visibility = View.VISIBLE
            incoming.alpha = 1f
            incoming.translationX = 0f
        } else {
            val direction =
                if (page.ordinal > (previousPage?.ordinal ?: 0)) 1f else -1f

            outgoing.animate().cancel()
            incoming.animate().cancel()
            incoming.bringToFront()
            incoming.visibility = View.VISIBLE
            incoming.alpha = 0f
            incoming.translationX = dp(22) * direction

            incoming.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(230L)
                .setInterpolator(DecelerateInterpolator())
                .start()

            outgoing.animate()
                .alpha(0f)
                .translationX(-dp(16) * direction)
                .setDuration(170L)
                .withEndAction {
                    if (currentPage != previousPage) {
                        outgoing.visibility = View.GONE
                        outgoing.alpha = 1f
                        outgoing.translationX = 0f
                    }
                }
                .start()
        }

        when (page) {
            Page.HOME -> updateHomeStats()
            Page.SCAN -> {
                if (hasCameraPermission()) {
                    startCamera()
                } else {
                    requestCameraPermissionIfNeeded()
                }
            }
            Page.HISTORY -> renderHistory()
            Page.ABOUT -> Unit
        }
    }

    private fun setNavigationSelection(page: Page) {
        val active = getColor(R.color.accent_teal)
        val inactive = getColor(R.color.text_muted)

        fun applyItem(
            item: View,
            icon: TextView,
            label: TextView,
            selected: Boolean
        ) {
            label.setTextColor(if (selected) active else inactive)
            icon.setTextColor(if (selected) active else inactive)
            item.alpha = if (selected) 1f else 0.82f
        }

        applyItem(navHome, iconHome, labelHome, page == Page.HOME)
        applyItem(navHistory, iconHistory, labelHistory, page == Page.HISTORY)
        applyItem(navAbout, iconAbout, labelAbout, page == Page.ABOUT)

        labelScan.setTextColor(if (page == Page.SCAN) active else inactive)

        findViewById<ImageView>(R.id.ivNavHomeSafe)
            .setColorFilter(if (page == Page.HOME) active else inactive)
        findViewById<ImageView>(R.id.ivNavHistorySafe)
            .setColorFilter(if (page == Page.HISTORY) active else inactive)
        findViewById<ImageView>(R.id.ivNavScanOutline)
            .setColorFilter(if (page == Page.SCAN) active else inactive)
        findViewById<ImageView>(R.id.ivNavAboutSafe)
            .setColorFilter(if (page == Page.ABOUT) active else inactive)

        iconScan.alpha = if (page == Page.SCAN) 1f else 0.82f
        navScan.alpha = if (page == Page.SCAN) 1f else 0.82f
    }

    private fun updateHomeStats() {
        if (::tvHomeScanCount.isInitialized) {
            tvHomeScanCount.text = scanHistory.size.toString()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Meminta izin kamera setelah pengguna mendapat penjelasan yang jelas.
     * Jika izin tidak dapat diminta lagi, pengguna diarahkan ke Settings.
     */
    private fun requestCameraPermissionIfNeeded() {
        if (hasCameraPermission()) {
            btnGrantCamera.visibility = View.GONE
            startCamera()
            return
        }

        tvCameraStatus.text =
            "Izin kamera diperlukan untuk scan. Kamu juga bisa memilih gambar dari galeri."
        btnGrantCamera.visibility = View.VISIBLE
        btnCaptureAndScan.isEnabled = false

        val requestCount = historyPrefs.getInt(PREF_CAMERA_REQUEST_COUNT, 0)
        val shouldShowRationale =
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

        when {
            shouldShowRationale -> {
                showCameraRationaleDialog()
            }

            requestCount >= 2 -> {
                showCameraSettingsDialog()
            }

            requestCount == 0 -> {
                // Jelaskan tujuan akses sebelum permintaan izin sistem pertama kali.
                showCameraRationaleDialog()
            }

            else -> {
                launchCameraPermissionRequest()
            }
        }
    }

    private fun showCameraRationaleDialog() {
        if (isFinishing || isDestroyed) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Mengapa TrashLens memerlukan kamera?")
            .setMessage(
                "Kamera digunakan untuk mengambil foto sampah agar model AI dapat " +
                        "mengenali kategorinya. Gambar diproses langsung di perangkat; " +
                        "koneksi internet tidak diperlukan untuk klasifikasi. Kamu tetap " +
                        "bisa memilih foto dari galeri jika tidak ingin memakai kamera."
            )
            .setNegativeButton("Nanti", null)
            .setNeutralButton("Pilih dari galeri") { _, _ ->
                launchGalleryPicker()
            }
            .setPositiveButton("Lanjutkan") { _, _ ->
                launchCameraPermissionRequest()
            }
            .show()
    }

    private fun launchCameraPermissionRequest() {
        if (isFinishing || isDestroyed) return

        if (hasCameraPermission()) {
            btnGrantCamera.visibility = View.GONE
            if (currentPage == Page.SCAN) startCamera()
            return
        }

        val requestCount = historyPrefs.getInt(PREF_CAMERA_REQUEST_COUNT, 0)

        historyPrefs.edit()
            .putInt(PREF_CAMERA_REQUEST_COUNT, requestCount + 1)
            .apply()

        try {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } catch (_: Exception) {
            showCameraPermissionNeeded()

            Toast.makeText(
                this,
                "Permintaan izin kamera gagal. Coba lagi melalui tombol izin.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showCameraPermissionNeeded() {
        tvCameraStatus.text =
            "Izin kamera belum diberikan. Izinkan kamera untuk scan atau pilih gambar dari galeri."
        btnGrantCamera.visibility = View.VISIBLE
        btnCaptureAndScan.isEnabled = false
    }

    private fun showCameraSettingsDialog() {
        if (isFinishing || isDestroyed) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Izin kamera belum tersedia")
            .setMessage(
                "Izin kamera tidak dapat diminta lagi melalui dialog biasa. " +
                        "Buka pengaturan aplikasi dan aktifkan izin Kamera, atau gunakan galeri."
            )
            .setNegativeButton("Gunakan galeri") { _, _ ->
                launchGalleryPicker()
            }
            .setPositiveButton("Buka pengaturan") { _, _ ->
                try {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Pengaturan aplikasi tidak dapat dibuka.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()

        // Pengguna mungkin baru kembali dari Settings setelah mengaktifkan izin kamera.
        if (
            currentPage == Page.SCAN &&
            hasCameraPermission() &&
            imageCapture == null &&
            !isCameraStarting
        ) {
            startCamera()
        }
    }

    private fun startCamera() {
        if (
            currentPage != Page.SCAN ||
            !hasCameraPermission() ||
            isFinishing ||
            isDestroyed ||
            isCameraStarting
        ) return

        if (cameraProvider != null && imageCapture != null) {
            btnGrantCamera.visibility = View.GONE
            btnCaptureAndScan.isEnabled = !isCapturing
            return
        }

        isCameraStarting = true
        tvCameraStatus.text = "Menyiapkan kamera..."
        btnCaptureAndScan.isEnabled = false

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

            cameraProviderFuture.addListener({
                isCameraStarting = false

                if (currentPage != Page.SCAN || isFinishing || isDestroyed) {
                    return@addListener
                }

                try {
                    if (!hasCameraPermission()) {
                        showCameraPermissionNeeded()
                        return@addListener
                    }

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
                    btnCaptureAndScan.isEnabled = !isCapturing
                    tvCameraStatus.text =
                        "Kamera aktif · klasifikasi lokal, internet tidak diperlukan"
                } catch (_: Exception) {
                    cameraProvider = null
                    imageCapture = null
                    btnCaptureAndScan.isEnabled = false
                    btnGrantCamera.visibility = View.VISIBLE
                    tvCameraStatus.text =
                        "Kamera tidak dapat dibuka. Periksa izin atau tutup aplikasi lain yang memakai kamera."
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
            isCameraStarting = false
            cameraProvider = null
            imageCapture = null
            btnCaptureAndScan.isEnabled = false
            btnGrantCamera.visibility = View.VISIBLE
            tvCameraStatus.text =
                "Gagal menyiapkan kamera. Tekan tombol untuk mencoba lagi."
        }
    }

    private fun stopCamera() {
        isCameraStarting = false

        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
            // Tidak ada resource tambahan yang perlu dilepas.
        }

        cameraProvider = null
        imageCapture = null
    }

    /** Menampilkan spinner dan pesan loading selama foto dianalisis. */
    private fun showScanLoading(message: String) {
        if (isFinishing || isDestroyed) return

        scanLoadingGeneration += 1
        scanLoadingStartedAt = android.os.SystemClock.elapsedRealtime()
        tvScanLoadingMessage.text = message
        progressScan.visibility = View.VISIBLE
        scanLoadingCard.visibility = View.VISIBLE
    }

    /** Menyembunyikan loading setelah proses selesai, dengan durasi minimum 1,8 detik. */
    private fun hideScanLoading(afterHidden: (() -> Unit)? = null) {
        val generation = scanLoadingGeneration
        val elapsed = android.os.SystemClock.elapsedRealtime() - scanLoadingStartedAt
        val delayMs = (scanLoadingMinimumMs - elapsed).coerceAtLeast(0L)

        val hideAction = Runnable {
            if (generation == scanLoadingGeneration && !isFinishing && !isDestroyed) {
                progressScan.visibility = View.GONE
                scanLoadingCard.visibility = View.GONE
                afterHidden?.invoke()
            }
        }

        if (delayMs == 0L) {
            hideAction.run()
        } else {
            window.decorView.postDelayed(hideAction, delayMs)
        }
    }
    /** Memulihkan UI setelah proses scan kamera atau galeri selesai/gagal. */
    private fun finishScanUi(
        status: String,
        cameraReady: Boolean = hasCameraPermission() && imageCapture != null
    ) {
        if (isFinishing || isDestroyed) return

        tvCameraStatus.text = status
        btnCaptureAndScan.text = "Ambil foto & scan"
        btnUploadImage.text = "Pilih gambar dari galeri"

        // Pertahankan tombol nonaktif hingga panel loading selesai ditampilkan.
        btnCaptureAndScan.isEnabled = false
        btnUploadImage.isEnabled = false

        hideScanLoading {
            if (isFinishing || isDestroyed) return@hideScanLoading

            isCapturing = false
            btnCaptureAndScan.isEnabled = cameraReady && hasCameraPermission()
            btnUploadImage.isEnabled = true

            if (!hasCameraPermission()) {
                btnGrantCamera.visibility = View.VISIBLE
                btnCaptureAndScan.isEnabled = false
            }

            // Baru gulir ke hasil setelah panel loading hilang, agar loading terlihat.
            if (resultCard.visibility == View.VISIBLE && currentPage == Page.SCAN) {
                (pageScan as? android.widget.ScrollView)?.smoothScrollTo(0, resultCard.bottom)
            }
        }
    }

    private fun captureAndClassify() {
        if (isCapturing) return

        if (!hasCameraPermission()) {
            requestCameraPermissionIfNeeded()
            return
        }

        val capture = imageCapture

        if (capture == null) {
            tvCameraStatus.text =
                "Kamera belum siap. Tunggu sebentar lalu coba lagi."
            startCamera()
            return
        }

        val outputDirectory = File(filesDir, "scan_history")

        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            tvCameraStatus.text = "Penyimpanan foto tidak dapat disiapkan."

            Toast.makeText(
                this,
                "Tidak dapat menyiapkan penyimpanan foto.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val imageFile = File(
            outputDirectory,
            "scan_${System.currentTimeMillis()}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(imageFile).build()

        isCapturing = true
        btnCaptureAndScan.isEnabled = false
        btnUploadImage.isEnabled = false
        btnCaptureAndScan.text = "Sedang menganalisis..."
        showScanLoading("Sedang menganalisis foto...")
        tvCameraStatus.text = "Mengambil foto dan menganalisis..."
        resultCard.visibility = View.GONE

        try {
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {

                    override fun onImageSaved(
                        outputFileResults: ImageCapture.OutputFileResults
                    ) {
                        try {
                            inferenceExecutor.execute {
                                var bitmap: Bitmap? = null

                                try {
                                    val decodedBitmap = decodeImageFile(
                                        imageFile,
                                        maxSide = 1600
                                    )
                                    bitmap = decodedBitmap

                                    val activeClassifier = imageClassifier
                                        ?: TfliteImageClassifier(applicationContext).also {
                                            imageClassifier = it
                                        }

                                    val prediction = activeClassifier.predict(decodedBitmap)
                                    val timestamp = System.currentTimeMillis()

                                    val record = ScanRecord(
                                        id = timestamp,
                                        imagePath = imageFile.absolutePath,
                                        label = prediction.label,
                                        confidence = prediction.confidence,
                                        timestamp = timestamp
                                    )

                                    runOnUiThread {
                                        if (isFinishing || isDestroyed) {
                                            if (!decodedBitmap.isRecycled) {
                                                decodedBitmap.recycle()
                                            }
                                            isCapturing = false
                                            return@runOnUiThread
                                        }

                                        resultBitmap?.let { old ->
                                            ivResultImage.setImageDrawable(null)
                                            if (!old.isRecycled) old.recycle()
                                        }

                                        resultBitmap = decodedBitmap
                                        ivResultImage.setImageBitmap(decodedBitmap)
                                        tvResultCategory.text =
                                            displayWasteLabel(prediction.label)

                                        tvResultConfidence.text = String.format(
                                            Locale("id", "ID"),
                                            "Keyakinan model: %.2f%%",
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

                                        tvResultDisclaimer.text =
                                            if (prediction.confidence < 0.70f) {
                                                "Prediksi belum meyakinkan. Coba pindai ulang dengan satu objek dan pencahayaan yang baik."
                                            } else {
                                                "AI dapat salah mengenali sampah. Periksa kembali sebelum memilah."
                                            }

                                        resultCard.visibility = View.VISIBLE

                                        if (motionAnimationsEnabled()) {
                                            resultCard.alpha = 0f
                                            resultCard.translationY = dp(28).toFloat()

                                            resultCard.animate()
                                                .alpha(1f)
                                                .translationY(0f)
                                                .setDuration(280L)
                                                .setInterpolator(DecelerateInterpolator())
                                                .start()
                                        } else {
                                            resultCard.alpha = 1f
                                            resultCard.translationY = 0f
                                        }


                                        addHistoryRecord(record)
                                        finishScanUi(
                                            "Scan selesai · siap untuk objek berikutnya"
                                        )
                                    }
                                } catch (_: Exception) {
                                    bitmap?.let {
                                        if (!it.isRecycled) it.recycle()
                                    }

                                    imageFile.delete()

                                    runOnUiThread {
                                        finishScanUi(
                                            "Klasifikasi gagal. Periksa gambar atau model lokal, lalu coba lagi."
                                        )

                                        Toast.makeText(
                                            this@MainActivity,
                                            "Gagal menganalisis foto. Coba lagi.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            imageFile.delete()
                            finishScanUi(
                                "Proses analisis tidak dapat dimulai. Silakan coba lagi."
                            )
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        imageFile.delete()

                        finishScanUi(
                            "Gagal mengambil foto: ${exception.message ?: "silakan coba lagi"}"
                        )

                        Toast.makeText(
                            this@MainActivity,
                            "Foto gagal diambil. Periksa kamera lalu coba lagi.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        } catch (_: Exception) {
            imageFile.delete()
            finishScanUi(
                "Tidak dapat memulai pengambilan foto. Coba lagi."
            )

            Toast.makeText(
                this,
                "Kamera gagal memulai proses scan.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Klasifikasi galeri memakai model TensorFlow Lite lokal; tidak perlu internet. */
    private fun classifyGalleryImage(uri: Uri) {
        if (isCapturing) return

        val outputDirectory = File(filesDir, "scan_history")

        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            Toast.makeText(
                this,
                "Tidak dapat menyiapkan penyimpanan foto.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val imageFile = File(
            outputDirectory,
            "gallery_${System.currentTimeMillis()}.jpg"
        )

        isCapturing = true
        btnCaptureAndScan.isEnabled = false
        btnUploadImage.isEnabled = false
        btnUploadImage.text = "Sedang menganalisis..."
        showScanLoading("Sedang menganalisis gambar dari galeri...")
        tvCameraStatus.text =
            "Membaca gambar galeri · analisis dilakukan di perangkat..."
        resultCard.visibility = View.GONE

        try {
            inferenceExecutor.execute {
                var decodedBitmap: Bitmap? = null

                try {
                    val inputStream = contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException(
                            "Gambar yang dipilih tidak dapat dibuka."
                        )

                    inputStream.use { input ->
                        FileOutputStream(imageFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    val bitmap = decodeImageFile(imageFile, maxSide = 1600)
                    decodedBitmap = bitmap

                    val activeClassifier = imageClassifier
                        ?: TfliteImageClassifier(applicationContext).also {
                            imageClassifier = it
                        }

                    val prediction = activeClassifier.predict(bitmap)
                    val timestamp = System.currentTimeMillis()

                    val record = ScanRecord(
                        id = timestamp,
                        imagePath = imageFile.absolutePath,
                        label = prediction.label,
                        confidence = prediction.confidence,
                        timestamp = timestamp
                    )

                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            imageFile.delete()
                            isCapturing = false
                            return@runOnUiThread
                        }

                        resultBitmap?.let { old ->
                            ivResultImage.setImageDrawable(null)
                            if (!old.isRecycled) old.recycle()
                        }

                        resultBitmap = bitmap
                        ivResultImage.setImageBitmap(bitmap)
                        tvResultCategory.text =
                            displayWasteLabel(prediction.label)

                        tvResultConfidence.text = String.format(
                            Locale("id", "ID"),
                            "Keyakinan model: %.2f%%",
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

                        tvResultDisclaimer.text =
                            if (prediction.confidence < 0.70f) {
                                "Prediksi belum meyakinkan. Coba pilih foto satu objek dengan pencahayaan yang baik."
                            } else {
                                "AI dapat salah mengenali sampah. Periksa kembali sebelum memilah."
                            }

                        resultCard.visibility = View.VISIBLE

                        if (motionAnimationsEnabled()) {
                            resultCard.alpha = 0f
                            resultCard.translationY = dp(28).toFloat()

                            resultCard.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(280L)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                        } else {
                            resultCard.alpha = 1f
                            resultCard.translationY = 0f
                        }


                        addHistoryRecord(record)
                        finishScanUi("Gambar dari galeri selesai dianalisis.")

                        if (
                            hasCameraPermission() &&
                            imageCapture == null &&
                            currentPage == Page.SCAN
                        ) {
                            startCamera()
                        }
                    }
                } catch (_: Exception) {
                    decodedBitmap?.let {
                        if (!it.isRecycled) it.recycle()
                    }

                    imageFile.delete()

                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            finishScanUi(
                                "Gagal memproses gambar. Pilih foto lain lalu coba lagi."
                            )

                            Toast.makeText(
                                this@MainActivity,
                                "Gagal menganalisis gambar. Periksa foto lalu coba lagi.",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        isCapturing = false
                    }
                }
            }
        } catch (_: Exception) {
            imageFile.delete()
            finishScanUi(
                "Proses analisis tidak dapat dimulai. Silakan coba lagi."
            )
        }
    }

    private fun decodeImageFile(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(file.absolutePath, bounds)

        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Foto yang dipilih tidak valid."
        }

        var sampleSize = 1
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)

        while (maxDimension / sampleSize > maxSide) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("Tidak dapat membaca foto.")

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
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                matrix.setScale(-1f, 1f)

            ExifInterface.ORIENTATION_ROTATE_180 ->
                matrix.setRotate(180f)

            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                matrix.setScale(1f, -1f)

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 ->
                matrix.setRotate(90f)

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 ->
                matrix.setRotate(270f)
        }

        if (matrix.isIdentity) return decoded

        val oriented = Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            matrix,
            true
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
        else -> label.replaceFirstChar {
            it.uppercase(Locale.getDefault())
        }
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

                if (
                    record.label.isNotBlank() &&
                    File(record.imagePath).exists()
                ) {
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

        historyPrefs.edit()
            .putString("items", array.toString())
            .apply()
    }

    private fun renderHistory() {
        if (!::historyListContainer.isInitialized) return

        historyListContainer.removeAllViews()
        tvHistoryCount.text = "${scanHistory.size} hasil tersimpan"
        updateHomeStats()

        tvHistoryEmpty.visibility =
            if (scanHistory.isEmpty()) View.VISIBLE else View.GONE

        btnClearHistory.isEnabled = scanHistory.isNotEmpty()

        scanHistory.forEachIndexed { recordIndex, record ->
            val categoryTint = when (record.label) {
                "cardboard" -> getColor(R.color.accent_orange)
                "glass" -> getColor(R.color.accent_blue)
                "metal" -> getColor(R.color.divider_color)
                "paper" -> getColor(R.color.accent_yellow)
                "plastic" -> getColor(R.color.accent_coral)
                else -> getColor(R.color.accent_purple)
            }

            val card = MaterialCardView(this).apply {
                radius = dp(20).toFloat()
                cardElevation = dp(3).toFloat()
                strokeWidth = dp(1)
                strokeColor = getColor(R.color.divider_color)
                setCardBackgroundColor(getColor(R.color.surface_primary))

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
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
                setBackgroundColor(categoryTint)
                clipToOutline = true
            }

            val file = File(record.imagePath)

            if (file.exists()) {
                try {
                    image.setImageBitmap(decodeImageFile(file, maxSide = 240))
                } catch (_: Exception) {
                    image.setImageResource(
                        android.R.drawable.ic_menu_report_image
                    )
                }
            } else {
                image.setImageResource(
                    android.R.drawable.ic_menu_report_image
                )
            }

            row.addView(image)

            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL

                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = dp(12)
                }
            }

            textColumn.addView(TextView(this).apply {
                text = displayWasteLabel(record.label)
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            textColumn.addView(TextView(this).apply {
                text = SimpleDateFormat(
                    "dd MMM yyyy, HH:mm",
                    Locale("id", "ID")
                ).format(Date(record.timestamp))

                setTextColor(getColor(R.color.text_muted))
                textSize = 11f
                setPadding(0, dp(4), 0, dp(5))
            })

            textColumn.addView(TextView(this).apply {
                text = String.format(
                    Locale("id", "ID"),
                    "Keyakinan %.2f%%",
                    record.confidence * 100f
                )

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

            if (motionAnimationsEnabled()) {
                card.alpha = 0f
                card.translationY = dp(12).toFloat()

                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((recordIndex * 38L).coerceAtMost(280L))
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun confirmClearHistory() {
        if (scanHistory.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus semua riwayat?")
            .setMessage(
                "Foto dan catatan hasil scan yang tersimpan di aplikasi akan dihapus."
            )
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                scanHistory.forEach {
                    File(it.imagePath).delete()
                }

                scanHistory.clear()
                saveHistory()
                renderHistory()

                Toast.makeText(
                    this,
                    "Riwayat scan telah dihapus.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        stopCamera()
        inferenceExecutor.shutdownNow()
        imageClassifier?.close()
        imageClassifier = null

        resultBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }

        resultBitmap = null

        super.onDestroy()
    }
}
