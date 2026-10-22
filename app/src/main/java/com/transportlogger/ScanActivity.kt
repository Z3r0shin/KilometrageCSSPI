package com.transportlogger

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Scans a P.O. label and returns it in [EXTRA_CODE].
 *
 * Play Services' ready-made scanner returns the first read and closes, and on
 * these Code 39 labels that first read was wrong most of the time. This screen
 * keeps the camera open instead, decodes every frame, and lets [ScanVote]
 * decide when the same 9-character value has come back often enough.
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CODE = "code"

        /** Set instead of a code when the user chose to type the number. */
        const val EXTRA_MANUAL = "manual"
    }

    private val vote = ScanVote()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private lateinit var code: TextView
    private lateinit var verify: TextView
    private lateinit var dots: List<View>
    @Volatile private var done = false

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to
                    listOf(BarcodeFormat.CODE_39, BarcodeFormat.CODE_128, BarcodeFormat.CODE_93),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Caméra refusée : scan impossible.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The overlay is a tall stack around a wide viewfinder; it has no landscape form.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_scan)
        Ui.edgeToEdge(this, findViewById(R.id.root))
        // Dark in both themes, so the system bars always take light icons.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        code = findViewById(R.id.scanCode)
        verify = findViewById(R.id.scanVerify)
        dots = listOf(R.id.scanDot1, R.id.scanDot2, R.id.scanDot3).map { findViewById(it) }
        findViewById<View>(R.id.scanClose).setOnClickListener { finish() }
        findViewById<View>(R.id.scanManual).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_MANUAL, true))
            finish()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = findViewById<PreviewView>(R.id.preview).surfaceProvider
            }
            // The default 640x480 leaves too few pixels per bar on a long label.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            try {
                provider.unbindAll()
                bindTorch(
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Caméra indisponible : ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** The lamp button only exists on a camera that has a lamp. */
    private fun bindTorch(camera: Camera) {
        if (!camera.cameraInfo.hasFlashUnit()) return
        val torch = findViewById<View>(R.id.scanTorch)
        torch.visibility = View.VISIBLE
        camera.cameraInfo.torchState.observe(this) { state ->
            torch.isSelected = state == TorchState.ON
            ViewCompat.setStateDescription(torch, if (torch.isSelected) "Allumée" else "Éteinte")
        }
        torch.setOnClickListener { camera.cameraControl.enableTorch(!torch.isSelected) }
    }

    private fun showCandidate(candidate: String, reads: Int) {
        val n = reads.coerceAtMost(dots.size)
        code.text = candidate
        verify.text = "Vérification $n sur ${dots.size}"
        dots.forEachIndexed { i, dot ->
            dot.setBackgroundResource(if (i < n) R.drawable.bg_dot_amber else R.drawable.bg_dot_ring)
        }
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            if (done) return
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val luma = ByteArray(w * h)
            for (y in 0 until h) {
                plane.buffer.position(y * plane.rowStride)
                plane.buffer.get(luma, y * w, w)
            }

            // 1D decoding runs along rows, so the label has to lie along them.
            // Held upright the sensor is sideways; try the other way as well in
            // case the phone itself is turned.
            val turned = rotate(luma, w, h)
            val sideways = image.imageInfo.rotationDegrees % 180 != 0
            val text = (if (sideways) decode(turned, h, w, true) ?: decode(luma, w, h, false)
                        else decode(luma, w, h, true) ?: decode(turned, h, w, false))
                ?: return

            Log.d("TransportLogger", "scan raw=$text")
            val accepted = vote.offer(text)
            val leader = vote.leader
            runOnUiThread {
                if (accepted != null) {
                    if (done) return@runOnUiThread
                    done = true
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, accepted))
                    finish()
                } else if (leader != null) {
                    showCandidate(leader.first, leader.second)
                }
            }
        }
    }

    private fun decode(luma: ByteArray, w: Int, h: Int, thorough: Boolean): String? {
        val source = PlanarYUVLuminanceSource(luma, w, h, 0, 0, w, h, false)
        val attempts =
            if (thorough) listOf(GlobalHistogramBinarizer(source), HybridBinarizer(source))
            else listOf(GlobalHistogramBinarizer(source))
        for (binarizer in attempts) {
            try {
                return reader.decodeWithState(BinaryBitmap(binarizer)).text
            } catch (_: NotFoundException) {
            } catch (_: Exception) {
            } finally {
                reader.reset()
            }
        }
        return null
    }

    /** Quarter turn clockwise: a w x h image becomes h x w. */
    private fun rotate(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until h) {
            val col = h - 1 - y
            for (x in 0 until w) out[x * h + col] = src[y * w + x]
        }
        return out
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
