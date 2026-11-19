package com.guangchang.camera

import androidx.exifinterface.media.ExifInterface
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.util.Size
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: TextView
    private lateinit var flashButton: TextView
    private lateinit var status: TextView
    private var imageCapture: ImageCapture? = null
    private var processing = false
    private var useFrontCamera = false
    private var darkTheme = false
    private var flashEnabled = false
    private val ratios = listOf("4:3", "16:9", "1:1")
    private var ratioIndex = 0
    private var currentRoot: LinearLayout? = null

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
        val bg = if (darkTheme) Color.rgb(18, 19, 22) else Color.rgb(239, 241, 239)
        val panel = if (darkTheme) Color.rgb(30, 31, 34) else Color.rgb(250, 250, 248)
        val fg = if (darkTheme) Color.rgb(245, 245, 242) else Color.rgb(32, 34, 33)
        val muted = if (darkTheme) Color.rgb(165, 168, 166) else Color.rgb(112, 116, 113)
        val accent = Color.rgb(50, 126, 245)

        fun pill(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(fg)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(panel); cornerRadius = dp(22).toFloat()
            }
            setOnClickListener { onClick() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        currentRoot = root

        // Clean rounded header: app name on the left, camera controls and theme on the right.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackground(roundDrawable(panel, 28))
            setPadding(dp(10), dp(7), dp(7), dp(7))
        }
        val appName = TextView(this).apply {
            text = "光感相机"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextColor(fg)
            maxLines = 1
        }
        header.addView(appName, LinearLayout.LayoutParams(0, dp(44), 1f))
        flashButton = pill(if (flashEnabled) "闪光开" else "闪光关") {
            flashEnabled = !flashEnabled
            imageCapture?.flashMode = if (flashEnabled) {
                ImageCapture.FLASH_MODE_ON
            } else {
                ImageCapture.FLASH_MODE_OFF
            }
            flashButton.text = if (flashEnabled) "闪光开" else "闪光关"
        }
        header.addView(flashButton, LinearLayout.LayoutParams(dp(68), dp(42)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
        header.addView(pill("实况") {
            Toast.makeText(this, "实况模式暂未启用，目前使用高画质静态拍摄", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(46), dp(42)).apply { setMargins(dp(2), 0, dp(2), 0) })
        header.addView(pill(ratios[ratioIndex]) {
            ratioIndex = (ratioIndex + 1) % ratios.size
            buildUi()
            startCamera()
        }, LinearLayout.LayoutParams(dp(54), dp(42)).apply { setMargins(dp(2), 0, dp(2), 0) })
        header.addView(pill(if (darkTheme) "☀" else "☾") {
            darkTheme = !darkTheme
            buildUi()
            startCamera()
        }, LinearLayout.LayoutParams(dp(40), dp(42)).apply { setMargins(dp(2), 0, 0, 0) })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(58)))

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.BLACK)
        }
        val previewFrame = FrameLayout(this).apply {
            background = roundDrawable(Color.BLACK, 24)
            clipToOutline = true
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            val tag = TextView(this@MainActivity).apply {
                text = "自然光影"
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(dp(11), dp(7), dp(11), dp(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x55000000); cornerRadius = dp(16).toFloat()
                }
            }
            addView(tag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(12), dp(12), 0, 0)
            })
        }
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dp(18), dp(16), dp(18), dp(12))
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackground(roundDrawable(panel, 30))
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val modeNames = listOf("拍照", "视频", "人像", "夜景", "扫描")
        modeNames.forEachIndexed { index, mode ->
            val item = TextView(this).apply {
                text = mode
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) accent else muted)
                setPadding(dp(7), dp(9), dp(7), dp(9))
                setOnClickListener {
                    if (index != 0) Toast.makeText(this@MainActivity, "${mode}模式正在规划中，当前可使用拍照模式", Toast.LENGTH_SHORT).show()
                }
            }
            modes.addView(item, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        bottom.addView(modes, LinearLayout.LayoutParams(-1, dp(42)))
        val activeLine = View(this).apply { setBackgroundColor(accent) }
        bottom.addView(activeLine, LinearLayout.LayoutParams(dp(80), dp(3)).apply {
            gravity = Gravity.CENTER
        })

        status = TextView(this).apply {
            text = "拍摄后自动塑造光影"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }
        bottom.addView(status, LinearLayout.LayoutParams(-1, dp(27)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(2))
        }
        val galleryButton = TextView(this).apply {
            text = "▧"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = roundDrawable(if (darkTheme) Color.rgb(45, 46, 49) else Color.WHITE, 18)
            setOnClickListener {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { startActivity(intent) } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "暂时无法打开相册", Toast.LENGTH_SHORT).show()
                }
            }
        }
        controls.addView(galleryButton, LinearLayout.LayoutParams(dp(60), dp(60)))

        val shutter = TextView(this).apply {
            text = ""
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (darkTheme) Color.WHITE else Color.WHITE)
                setStroke(dp(5), if (darkTheme) Color.rgb(75, 77, 80) else Color.rgb(225, 228, 226))
            }
            setOnClickListener { takePhoto() }
        }
        captureButton = shutter
        controls.addView(shutter, LinearLayout.LayoutParams(dp(82), dp(82)).apply {
            gravity = Gravity.CENTER
            weight = 1f
            setMargins(dp(20), dp(3), dp(20), dp(3))
        })
        val switchButton = TextView(this).apply {
            text = "⟳"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = roundDrawable(if (darkTheme) Color.rgb(45, 46, 49) else Color.WHITE, 50)
            setOnClickListener {
                useFrontCamera = !useFrontCamera
                startCamera()
            }
        }
        controls.addView(switchButton, LinearLayout.LayoutParams(dp(60), dp(60)))
        bottom.addView(controls, LinearLayout.LayoutParams(-1, dp(92)))
        bottom.addView(TextView(this).apply {
            text = "保留真实细节 · 本地光影处理"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }, LinearLayout.LayoutParams(-1, dp(22)))
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = if (darkTheme) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun roundDrawable(color: Int, radiusDp: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // Request the highest 4:3 resolution exposed by CameraX on this device.
            // Some manufacturers reserve special 50MP/108MP modes for their own camera app;
            // CameraX can only use resolutions the device exposes to third-party apps.
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(Int.MAX_VALUE, Int.MAX_VALUE),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                    )
                )
                .build()
            imageCapture = ImageCapture.Builder()
                .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .setResolutionSelector(resolutionSelector)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
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
                        val cropped = cropToSelectedRatio(oriented)
                        val processed = CinematicProcessor.process(cropped)
                        val uri = saveToGallery(processed)
                        runOnUiThread {
                            status.text = if (uri != null) "已保存电影感成片到相册" else "处理完成，但保存失败"
                            if (uri != null) Toast.makeText(this@MainActivity, "成片已保存至相册", Toast.LENGTH_LONG).show()
                            processing = false; captureButton.alpha = 1f
                        }
                        if (oriented !== source) source.recycle()
                        if (cropped !== oriented) oriented.recycle()
                        cropped.recycle()
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


    private fun cropToSelectedRatio(bitmap: Bitmap): Bitmap {
        val target = when (ratios[ratioIndex]) {
            "16:9" -> 16.0 / 9.0
            "1:1" -> 1.0
            else -> 4.0 / 3.0
        }
        val current = bitmap.width.toDouble() / bitmap.height.toDouble()
        if (abs(current - target) < 0.015) return bitmap
        val cropW: Int
        val cropH: Int
        if (current > target) {
            cropH = bitmap.height
            cropW = (cropH * target).roundToInt().coerceIn(1, bitmap.width)
        } else {
            cropW = bitmap.width
            cropH = (cropW / target).roundToInt().coerceIn(1, bitmap.height)
        }
        val left = (bitmap.width - cropW) / 2
        val top = (bitmap.height - cropH) / 2
        return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
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
  private fun androidxExifOrientation(path: String): Int {
    val exif = ExifInterface(path)
    return when (
        exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    ) {
        ExifInterface.ORIENTATION_ROTATE_180 -> 3
        ExifInterface.ORIENTATION_ROTATE_90 -> 6
        ExifInterface.ORIENTATION_ROTATE_270 -> 8
        else -> 1
    }
}
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
