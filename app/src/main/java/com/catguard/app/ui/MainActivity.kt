package com.catguard.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.catguard.app.camera.CameraActivity
import com.catguard.app.databinding.ActivityMainBinding
import com.catguard.app.viewer.ViewerActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.INTERNET
    )

    private var pendingMode: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            launchPendingMode()
        } else {
            Toast.makeText(
                this,
                "Camera and microphone permissions are required",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCameraMode.setOnClickListener {
            pendingMode = "CAMERA"
            checkPermissionsAndLaunch()
        }

        binding.btnViewerMode.setOnClickListener {
            pendingMode = "VIEWER"
            checkPermissionsAndLaunch()
        }
    }

    private fun checkPermissionsAndLaunch() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            launchPendingMode()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun launchPendingMode() {
        when (pendingMode) {
            "CAMERA" -> startActivity(Intent(this, CameraActivity::class.java))
            "VIEWER" -> startActivity(Intent(this, ViewerActivity::class.java))
        }
    }
}
