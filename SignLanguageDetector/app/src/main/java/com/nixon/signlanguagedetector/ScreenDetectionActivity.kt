package com.nixon.signlanguagedetector

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat


class ScreenDetectionActivity : AppCompatActivity() {

    private val TAG = "ScreenDetectionActivity"

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private var overlayPermissionLauncher: ActivityResultLauncher<Intent>
    private var notificationPermissionLauncher: ActivityResultLauncher<String>

    companion object {
        const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
        const val REQUEST_CODE_MEDIA_PROJECTION = 1002
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1003
    }

    init {
        // Initialize launchers
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // User granted MediaProjection permission
                Log.d(TAG, "MediaProjection permission granted.")
                startScreenCaptureService(result.data!!)
            } else {
                Log.w(TAG, "MediaProjection permission denied or cancelled.")
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

        overlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission granted.")
                // Now that overlay permission is granted, request MediaProjection
                requestMediaProjectionPermission()
            } else {
                Log.w(TAG, "Overlay permission denied.")
                Toast.makeText(this, "Overlay permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "Notification permission granted.")
                // Proceed with other permissions or start service
                requestOverlayPermission()
            } else {
                Log.w(TAG, "Notification permission denied. Foreground service notification may not show.")
                // Still try to proceed, but warn the user.
                requestOverlayPermission()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_detection)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.startButton).setOnClickListener {
            // First, request notification permission (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    requestOverlayPermission()
                }
            } else {
                // For older Android versions, directly request overlay
                requestOverlayPermission()
            }
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopScreenCaptureService()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(intent)
        } else {
            // Overlay permission already granted or not needed on older SDKs
            requestMediaProjectionPermission()
        }
    }

    private fun requestMediaProjectionPermission() {
        Log.d(TAG, "Requesting MediaProjection permission...")
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun startScreenCaptureService(data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("resultCode", Activity.RESULT_OK)
            putExtra("data", data)
        }

        // Start the service as a foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Screen Capture Service Starting...", Toast.LENGTH_SHORT).show()
    }

    private fun stopScreenCaptureService() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)
        Toast.makeText(this, "Screen Capture Service Stopped.", Toast.LENGTH_SHORT).show()
    }
}