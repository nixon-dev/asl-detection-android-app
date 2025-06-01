package com.nixon.signlanguagedetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nixon.signlanguagedetector.Constants.LABELS_PATH
import com.nixon.signlanguagedetector.Constants.MODEL_PATH
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference // To safely share Bitmap reference

class ScreenCaptureService : Service(), DetectorScreen.DetectorListener {

    private val TAG = "ScreenCaptureService"
    private val NOTIFICATION_ID = 123
    private val NOTIFICATION_CHANNEL_ID = "ScreenCaptureChannel"

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private lateinit var overlayImageView: ImageView
    private lateinit var stopOverlayButton: Button
    private lateinit var detectedObjectTextView: TextView
    private lateinit var inferenceTimeTextView: TextView

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var params: WindowManager.LayoutParams? = null

    private lateinit var detector: DetectorScreen

    private val currentFullScreenBitmap = AtomicReference<Bitmap?>()

    private lateinit var imageProcessingHandler: Handler
    private lateinit var imageProcessingLooper: Looper

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager?.currentWindowMetrics
            windowMetrics?.bounds?.let {
                screenWidth = it.width()
                screenHeight = it.height()
            }
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager?.defaultDisplay
            @Suppress("DEPRECATION")
            display?.getMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi
        }
        Log.d(TAG, "Screen Dimensions: ${screenWidth}x${screenHeight}, Density: $screenDensity")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        detector = DetectorScreen(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()
        Log.d(TAG, "Custom Detector initialized successfully.")

        val handlerThread = android.os.HandlerThread("ImageProcessingThread")
        handlerThread.start()
        imageProcessingLooper = handlerThread.looper
        imageProcessingHandler = Handler(imageProcessingLooper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra("data")

        if (resultCode == 0 || data == null) {
            Log.e(TAG, "Invalid resultCode or data for MediaProjection. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(MediaProjectionCallback(), Handler(Looper.getMainLooper()))

        startScreenCapture()
        addOverlayView()

        return START_STICKY
    }

    private fun startScreenCapture() {
        val captureWidth = screenWidth
        val captureHeight = screenHeight

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null. Cannot start screen capture.")
            return
        }

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            imageProcessingHandler.post {
                var image: Image? = null
                var bitmap: Bitmap? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer: ByteBuffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * image.width

                        bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)

                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)

                        // Store the full-screen bitmap for potential cropping later
                        currentFullScreenBitmap.set(croppedBitmap)

                        // Perform object detection using your Detector class
                        detector.detect(croppedBitmap) // Pass the full-screen bitmap to the detector
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring image or processing: ${e.message}", e)
                } finally {
                    image?.close()

                }
            }
        }, imageProcessingHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            captureWidth,
            captureHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        Log.d(TAG, "VirtualDisplay created with size: ${captureWidth}x${captureHeight}")
    }

    private fun addOverlayView() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        overlayImageView = overlayView!!.findViewById(R.id.overlayImageView)
        stopOverlayButton = overlayView!!.findViewById(R.id.stopOverlayButton)
        detectedObjectTextView = overlayView!!.findViewById(R.id.detectedObjectTextView)
        inferenceTimeTextView = overlayView!!.findViewById(R.id.inferenceTimeTextView)

        stopOverlayButton.setOnClickListener {
            Toast.makeText(this, "Stopping from overlay button...", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private val gestureDetector = GestureDetector(applicationContext, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    return false
                }
            })

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(event)

                val currentX = event.rawX.toInt()
                val currentY = event.rawY.toInt()

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params!!.x
                        initialY = params!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params!!.x = initialX + (currentX - initialTouchX).toInt()
                        params!!.y = initialY + (currentY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    MotionEvent.ACTION_UP -> {

                    }
                }
                return true
            }
        })

        try {
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Overlay view added.")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view: ${e.message}", e)
            Toast.makeText(this, "Failed to add overlay. Check permission.", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun removeOverlayView() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
                Log.d(TAG, "Overlay view removed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view: ${e.message}", e)
            } finally {
                overlayView = null
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen Capture Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Screen Capture Active")
            .setContentText("Your screen is being captured by the app.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system or user.")
            Toast.makeText(applicationContext, "Screen capture stopped.", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        stopScreenCapture()
        removeOverlayView()
        mediaProjection?.unregisterCallback(MediaProjectionCallback())
        mediaProjection?.stop()
        mediaProjection = null
        imageReader?.close()
        imageReader = null

        imageProcessingLooper.quitSafely()
        detector.clear()
        Log.d(TAG, "Detector resources cleared.")

        currentFullScreenBitmap.getAndSet(null)?.recycle()

        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun stopScreenCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        Log.d(TAG, "VirtualDisplay released.")
    }

    override fun onEmptyDetect() {
        Handler(Looper.getMainLooper()).post {
            currentFullScreenBitmap.get()?.let {
                overlayImageView.setImageBitmap(it)
            } ?: run {
                overlayImageView.setImageBitmap(null)
            }
            detectedObjectTextView.text = "No sign language detected."
            inferenceTimeTextView.text = "0ms"
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long, originalFrameWidth: Int, originalFrameHeight: Int) {
        Handler(Looper.getMainLooper()).post {
            inferenceTimeTextView.text = "${inferenceTime}ms"

            val fullScreenBitmap = currentFullScreenBitmap.get()
            if (fullScreenBitmap == null || fullScreenBitmap.isRecycled) {
                Log.w(TAG, "Full screen bitmap is null or recycled, cannot crop.")
                detectedObjectTextView.text = "Error: Bitmap missing for crop."
                overlayImageView.setImageBitmap(null)
                return@post
            }

            if (boundingBoxes.isEmpty()) {
                detectedObjectTextView.text = "No sign language detected."
                overlayImageView.setImageBitmap(fullScreenBitmap) // Show full screen if nothing found
                return@post
            }

            val detectedNames = StringBuilder()
            var handBox: BoundingBox? = null
            for (box in boundingBoxes) {
                val confidence = box.cnf * 100
                detectedNames.append("${box.clsName}\n")

                if (box.clsName.equals("hand", ignoreCase = true) || box.clsName.equals("person", ignoreCase = true)) {
                    if (handBox == null || box.cnf > handBox.cnf) {
                        handBox = box
                    }
                }
            }
            detectedObjectTextView.text = detectedNames.toString().trim()

            if (handBox != null) {
                try {
                    val x1Px = (handBox.x1 * originalFrameWidth).toInt()
                    val y1Px = (handBox.y1 * originalFrameHeight).toInt()
                    val x2Px = (handBox.x2 * originalFrameWidth).toInt()
                    val y2Px = (handBox.y2 * originalFrameHeight).toInt()

                    val detectedWidthPx = x2Px - x1Px
                    val detectedHeightPx = y2Px - y1Px

                    val padding = 0.2f
                    val expandWidth = (detectedWidthPx * padding).toInt()
                    val expandHeight = (detectedHeightPx * padding).toInt()

                    var cropX1 = maxOf(0, x1Px - expandWidth)
                    var cropY1 = maxOf(0, y1Px - expandHeight)
                    var cropX2 = minOf(originalFrameWidth, x2Px + expandWidth)
                    var cropY2 = minOf(originalFrameHeight, y2Px + expandHeight)

                    val currentCropWidth = cropX2 - cropX1
                    val currentCropHeight = cropY2 - cropY1
                    val desiredCropSize = maxOf(currentCropWidth, currentCropHeight)

                    val centerX = (x1Px + x2Px) / 2
                    val centerY = (y1Px + y2Px) / 2

                    cropX1 = maxOf(0, centerX - desiredCropSize / 2)
                    cropY1 = maxOf(0, centerY - desiredCropSize / 2)
                    cropX2 = minOf(originalFrameWidth, centerX + desiredCropSize / 2)
                    cropY2 = minOf(originalFrameHeight, centerY + desiredCropSize / 2)

                    if (cropX2 - cropX1 < desiredCropSize) {
                        if (cropX1 == 0) cropX2 = desiredCropSize
                        else if (cropX2 == originalFrameWidth) cropX1 = originalFrameWidth - desiredCropSize
                    }
                    if (cropY2 - cropY1 < desiredCropSize) {
                        if (cropY1 == 0) cropY2 = desiredCropSize
                        else if (cropY2 == originalFrameHeight) cropY1 = originalFrameHeight - desiredCropSize
                    }

                    cropX1 = maxOf(0, cropX1)
                    cropY1 = maxOf(0, cropY1)
                    cropX2 = minOf(originalFrameWidth, cropX2)
                    cropY2 = minOf(originalFrameHeight, cropY2)

                    val finalCropWidth = cropX2 - cropX1
                    val finalCropHeight = cropY2 - cropY1

                    if (finalCropWidth > 0 && finalCropHeight > 0) {
                        val zoomedBitmap = Bitmap.createBitmap(
                            fullScreenBitmap,
                            cropX1,
                            cropY1,
                            finalCropWidth,
                            finalCropHeight
                        )
                        overlayImageView.setImageBitmap(zoomedBitmap)

                    } else {
                        Log.w(TAG, "Calculated crop region invalid: ($cropX1,$cropY1) to ($cropX2,$cropY2)")
                        overlayImageView.setImageBitmap(fullScreenBitmap)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error cropping bitmap for zoom: ${e.message}", e)
                    overlayImageView.setImageBitmap(fullScreenBitmap)
                }
            } else {
                overlayImageView.setImageBitmap(fullScreenBitmap)
            }
        }
    }
}