package com.nixon.signlanguagedetector

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity


class MainActivity : AppCompatActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.cameraDetectionBtn).setOnClickListener {
            startActivity(Intent(this, CameraDetectionActivity::class.java))
        }

        findViewById<Button>(R.id.screenDetectionBtn).setOnClickListener {
            startActivity(Intent(this, ScreenDetectionActivity::class.java))
        }

    }




}

