package com.putragandad.cameraxdemo

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.putragandad.cameraxdemo.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringAction.FLAG_AF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.TorchState
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.PermissionChecker
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private var cameraControl: CameraControl? = null

    private var cameraInfo: CameraInfo? = null
    private var lensFacing: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA // default back camerax


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Set up the listeners for take photo and video capture buttons
//        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        viewBinding.flashButton.setOnClickListener {
            cameraInfo?.let { cameraInfo ->
                if(cameraInfo.hasFlashUnit()) {
                    val isTorchOn = cameraInfo.torchState.value == TorchState.ON

                    cameraControl?.enableTorch(!isTorchOn)
                }
            }
        }

        viewBinding.switchCamera.setOnClickListener {
            when(lensFacing) {
                CameraSelector.DEFAULT_BACK_CAMERA -> {
                    lensFacing = CameraSelector.DEFAULT_FRONT_CAMERA
                    startCamera()
                }
                CameraSelector.DEFAULT_FRONT_CAMERA -> {
                    lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
                    startCamera()
                }
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {}

    private fun captureVideo() {
        val timestamp = System.currentTimeMillis()

        val videoCapture = this.videoCapture ?: return

        viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        // ContentValues that later handled on backward compatibility manner
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, timestamp)
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // content values for MediaStore Android >= 10
            contentValues.put(MediaStore.Video.Media.DATE_TAKEN, timestamp)
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")

            recording = videoCapture.output
                .prepareRecording(this, mediaStoreOutputOptions)
                .apply {
                    if (PermissionChecker.checkSelfPermission(this@MainActivity,
                            Manifest.permission.RECORD_AUDIO) ==
                        PermissionChecker.PERMISSION_GRANTED)
                    {
                        withAudioEnabled()
                    }
                }
                .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                    when(recordEvent) {
                        is VideoRecordEvent.Start -> {
                            viewBinding.videoCaptureButton.apply {
                                isEnabled = true
                            }.setImageResource(R.drawable.ic_outline_stop_circle_24)

                            viewBinding.switchCamera.visibility = View.GONE
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!recordEvent.hasError()) {
                                val msg = "Video capture succeeded: " +
                                        "${recordEvent.outputResults.outputUri}"

                                // notify mediastore so this video can be indexed on google photo/gallery
                                // for android >= 10
                                contentValues.put(MediaStore.Video.Media.IS_PENDING, false)
                                this.contentResolver.update(recordEvent.outputResults.outputUri, contentValues, null, null)

                                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                    .show()
                                Log.d(TAG, msg)
                            } else {
                                recording?.close()
                                recording = null
                                Log.e(TAG, "Video capture ends with error: " +
                                        "${recordEvent.error}")
                            }
                            viewBinding.videoCaptureButton.apply {
                                isEnabled = true
                            }.setImageResource(R.drawable.ic_baseline_videocam_24)

                            viewBinding.switchCamera.visibility = View.VISIBLE
                        }
                    }
                }
        } else {
            // for Android < 10, we use FileoutputOptions as fallback for saving video
            val videoFileFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).toString() + "/CameraX_Video")
            if (!videoFileFolder.exists()) {
                videoFileFolder.mkdirs()
            }
            val mImageName = "$timestamp.mp4"
            val videoFile = File(videoFileFolder, mImageName)

            val fileOutputOptions = FileOutputOptions.Builder(videoFile).build()

            recording = videoCapture.output
                .prepareRecording(this, fileOutputOptions) // use the overload constructor for API 21 found in Recorder.java of Camerax video
                .apply {
                    if (PermissionChecker.checkSelfPermission(this@MainActivity,
                            Manifest.permission.RECORD_AUDIO) ==
                        PermissionChecker.PERMISSION_GRANTED)
                    {
                        withAudioEnabled()
                    }
                }
                .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                    when(recordEvent) {
                        is VideoRecordEvent.Start -> {
                            viewBinding.videoCaptureButton.apply {
                                isEnabled = true
                            }.setImageResource(R.drawable.ic_outline_stop_circle_24)

                            viewBinding.switchCamera.visibility = View.GONE
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!recordEvent.hasError()) {
                                val msg = "Video capture succeeded: " +
                                        "${recordEvent.outputResults.outputUri}"

                                // notify mediastore so this video can be indexed on google photo/gallery
                                // for android < 10
                                contentValues.put(MediaStore.Video.Media.DATA, videoFile.absolutePath)
                                this.contentResolver.insert(
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                    contentValues
                                )

                                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                    .show()
                                Log.d(TAG, msg)
                            } else {
                                recording?.close()
                                recording = null
                                Log.e(TAG, "Video capture ends with error: " +
                                        "${recordEvent.error}")
                            }
                            viewBinding.videoCaptureButton.apply {
                                isEnabled = true
                            }.setImageResource(R.drawable.ic_baseline_videocam_24)

                            viewBinding.switchCamera.visibility = View.VISIBLE
                        }
                    }
                }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                val camera = cameraProvider.bindToLifecycle(
                    this, lensFacing, preview, videoCapture)

                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo

                cameraInfo?.torchState?.observe(this) { state ->
                    if (state == TorchState.ON) {
                        viewBinding.flashButton.setImageResource(R.drawable.flash_on_24px)
                    } else {
                        viewBinding.flashButton.setImageResource(R.drawable.flash_off_24px)
                    }
                }

                cameraInfo?.let {
                    if(it.hasFlashUnit()) {
                        viewBinding.flashButton.visibility = View.VISIBLE
                    } else {
                        viewBinding.flashButton.visibility = View.GONE
                    }
                }

                tapToFocus()
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun tapToFocus() {
        viewBinding.viewFinder.setOnTouchListener { _, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    // Get the MeteringPointFactory from PreviewView
                    val factory = viewBinding.viewFinder.meteringPointFactory

                    // Create a MeteringPoint from the tap coordinates
                    val point = factory.createPoint(motionEvent.x, motionEvent.y)

                    // Create a FocusMeteringAction
                    val action = FocusMeteringAction.Builder(point)
                        .addPoint(point, FLAG_AF)
                        .addPoint(point, FocusMeteringAction.FLAG_AE)
                        .addPoint(point, FocusMeteringAction.FLAG_AWB)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()

                    // Trigger the focus and metering
                    cameraControl?.startFocusAndMetering(action)

                    // Show focus indicator
                    showFocusIndicator(motionEvent.x, motionEvent.y)

                    true
                }
                else -> false
            }
        }
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val focusIndicator = viewBinding.focusIndicator

        // Position the indicator
        focusIndicator.x = x - focusIndicator.width / 2
        focusIndicator.y = y - focusIndicator.height / 2

        // Make it visible
        focusIndicator.visibility = View.VISIBLE

        // Animate the indicator (scale and fade out)
        focusIndicator.animate()
            .scaleX(1.5f)
            .scaleY(1.5f)
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                focusIndicator.visibility = View.GONE
                focusIndicator.scaleX = 1f
                focusIndicator.scaleY = 1f
                focusIndicator.alpha = 1f
            }
            .start()
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

}