package com.guangchang.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: TextView
    private lateinit var status: TextView
    private var imageCapture: ImageCapture? = null
    private var processing = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            status.text = "需要相机权限才能拍摄"
            Toast.makeText(this, "请在系统设置中允许相机权限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 11, 14))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(15), dp(22), dp(12))
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "光场"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "CINEMATIC CAMERA"; textSize = 10f; letterSpacing = .18f; setTextColor(Color.rgb(174, 164, 141))
        })
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "自动后期  ·  本地处理"; textSize = 11f; setTextColor(Color.rgb(216,199,160))
        })
        root.addView(header)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(24))
        }
        status = TextView(this).apply {
            text = "拍摄后自动塑造光影"; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(190,190,185))
        }
        bottom.addView(status, LinearLayout.LayoutParams(-1, dp(28)))
        captureButton = TextView(this).apply {
            text = "拍 摄"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(15,16,17)); typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.rgb(216,199,160))
            setPadding(dp(24), dp(15), dp(24), dp(15))
            setOnClickListener { takePhoto() }
        }
        val captureWrap = FrameLayout(this).apply { addView(captureButton, FrameLayout.LayoutParams(dp(180), dp(54), Gravity.CENTER)) }
        bottom.addView(captureWrap, LinearLayout.LayoutParams(-1, dp(64)))
        bottom.addView(TextView(this).apply {
            text = "保留原始结构 · 不使用生成式重绘"; textSize = 10f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(115,119,121))
        })
        root.addView(bottom)
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                status.text = "已就绪 · 拍摄后自动处理"
            } catch (e: Exception) {
                status.text = "相机启动失败：${e.localizedMessage ?: "未知错误"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (processing) return
        val capture = imageCapture ?: run {
            Toast.makeText(this, "相机正在启动，请稍后", Toast.LENGTH_SHORT).show(); return
        }
        processing = true
        captureButton.alpha = .45f
        status.text = "正在拍摄并塑造光影…"
        val file = java.io.File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                Thread {
                    try {
                        val source = BitmapFactory.decodeFile(file.absolutePath)
                            ?: throw IllegalStateException("无法读取照片")
                        val oriented = orientBitmap(source, file.absolutePath)
                        val processed = CinematicProcessor.process(oriented)
                        val uri = saveToGallery(processed)
                        runOnUiThread {
                            status.text = if (uri != null) "已保存电影感成片到相册" else "处理完成，但保存失败"
                            if (uri != null) Toast.makeText(this@MainActivity, "成片已保存至相册", Toast.LENGTH_LONG).show()
                            processing = false; captureButton.alpha = 1f
                        }
                        if (oriented !== source) source.recycle()
                        oriented.recycle()
                        processed.recycle()
                    } catch (e: Exception) {
                        runOnUiThread {
                            status.text = "处理失败，原图未被修改"
                            Toast.makeText(this@MainActivity, e.localizedMessage ?: "处理失败", Toast.LENGTH_LONG).show()
                            processing = false; captureButton.alpha = 1f
                        }
                    } finally { file.delete() }
                }.start()
            }
            override fun onError(exception: ImageCaptureException) {
                status.text = "拍摄失败"; processing = false; captureButton.alpha = 1f
            }
        })
    }

    private fun orientBitmap(bitmap: Bitmap, path: String): Bitmap {
        return try {
            val exif = androidxExifOrientation(path)
            val matrix = Matrix()
            when (exif) {
                3 -> matrix.postRotate(180f)
                6 -> matrix.postRotate(90f)
                8 -> matrix.postRotate(270f)
            }
            if (exif == 1) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Exception) { bitmap }
    }

    // JPEG from CameraX is normally oriented by the camera pipeline; kept isolated for future EXIF support.
    private fun androidxExifOrientation(path: String): Int = 1

    private fun saveToGallery(bitmap: Bitmap): Uri? {
        val name = "GuangChang_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/光场相机")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            contentResolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)) throw IllegalStateException("编码失败")
            }
            uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null); null
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

private object CinematicProcessor {
    /**
     * Deterministic, non-generative image processing:
     * a single broad diagonal light field, gentle tone curve, restrained saturation and
     * detail-preserving shadow/highlight handling. No object synthesis or segmentation.
     */
    fun process(input: Bitmap): Bitmap {
        val maxDimension = max(input.width, input.height)
        // Keep full resolution; process in tiles to avoid allocating multiple full-size float arrays.
        val out = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val tileH = 96
        val width = input.width
        val height = input.height
        val pixels = IntArray(width * min(tileH, height))
        val output = IntArray(width * min(tileH, height))

        // Estimate dominant broad lighting axis from a small thumbnail's luminance centroid.
        val thumb = Bitmap.createScaledBitmap(input, min(96, width), min(96, height), true)
        var weightedX = 0.0; var weightedY = 0.0; var total = 0.0
        for (y in 0 until thumb.height) for (x in 0 until thumb.width) {
            val c = thumb.getPixel(x, y)
            val lum = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0
            val w = max(0.0, lum - 0.35).pow(1.5)
            weightedX += x * w; weightedY += y * w; total += w
        }
        val cx = if (total > 1e-6) weightedX / total / thumb.width else 0.5
        val cy = if (total > 1e-6) weightedY / total / thumb.height else 0.35
        thumb.recycle()

        // Direction follows the existing bright mass but remains broad and globally coherent.
        val vx = (cx - 0.5).coerceIn(-0.45, 0.45)
        val vy = (cy - 0.5).coerceIn(-0.45, 0.45)
        val norm = max(0.2, hypot(vx, vy))
        val dx = vx / norm
        val dy = vy / norm
        val invW = 1.0 / max(1, width - 1)
        val invH = 1.0 / max(1, height - 1)

        var top = 0
        while (top < height) {
            val h = min(tileH, height - top)
            input.getPixels(pixels, 0, width, 0, top, width, h)
            for (ly in 0 until h) {
                val y = top + ly
                val ny = y * invH
                for (x in 0 until width) {
                    val c = pixels[ly * width + x]
                    val r0 = Color.red(c) / 255.0
                    val g0 = Color.green(c) / 255.0
                    val b0 = Color.blue(c) / 255.0
                    val lum = 0.2126*r0 + 0.7152*g0 + 0.0722*b0

                    // One broad axis, no vignette, no isolated spotlights.
                    val nx = x * invW
                    val position = ((nx - 0.5) * dx + (ny - 0.5) * dy)
                    val range = (abs(dx) + abs(dy)).coerceAtLeast(0.7)
                    val axis = (position / range + 0.5).coerceIn(0.0, 1.0)
                    val broad = smoothstep(0.08, 0.92, axis)
                    val lightGain = 0.76 + 0.42 * broad

                    // Stronger contrast with a restrained toe and shoulder.
                    fun tone(v: Double): Double {
                        val exposed = (v * lightGain).coerceIn(0.0, 1.0)
                        val contrast = ((exposed - 0.5) * 1.24 + 0.5).coerceIn(0.0, 1.0)
                        val toe = contrast * contrast * (3.0 - 2.0 * contrast)
                        return (0.84 * contrast + 0.16 * toe).coerceIn(0.0, 1.0)
                    }
                    var r = tone(r0)
                    var g = tone(g0)
                    var b = tone(b0)

                    // Mild color-density lift; preserve hue rather than stylized color grading.
                    val newLum = 0.2126*r + 0.7152*g + 0.0722*b
                    val sat = 1.055
                    r = (newLum + (r-newLum)*sat).coerceIn(0.0, 1.0)
                    g = (newLum + (g-newLum)*sat).coerceIn(0.0, 1.0)
                    b = (newLum + (b-newLum)*sat).coerceIn(0.0, 1.0)

                    output[ly * width + x] = Color.rgb((r*255).roundToInt(), (g*255).roundToInt(), (b*255).roundToInt())
                }
            }
            out.setPixels(output, 0, width, 0, top, width, h)
            top += h
        }
        return out
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x-edge0)/(edge1-edge0)).coerceIn(0.0, 1.0)
        return t*t*(3.0-2.0*t)
    }
}
