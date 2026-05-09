package com.catguard.app.camera

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.catguard.app.databinding.ActivityCameraBinding
import com.catguard.app.ml.CatDetector
import com.catguard.app.network.SignalingClient
import com.catguard.app.network.WebRtcManager
import kotlinx.coroutines.*
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"
        private const val DETECTION_INTERVAL_MS = 1000L  // Run detection every 1 second
        const val STATUS_DETECTED = "OBJECT_DETECTED"
        const val STATUS_LOST = "OBJECT_LOST"
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var catDetector: CatDetector
    private lateinit var cameraExecutor: ExecutorService

    private var signalingClient: SignalingClient? = null
    private var webRtcManager: WebRtcManager? = null
    private var eglBase: EglBase? = null

    private val isDetecting = AtomicBoolean(false)
    private var lastStatus = ""
    private var roomCode = ""
    private val deviceId = UUID.randomUUID().toString().take(8)

    // Latest bitmap for detection (updated by ImageAnalysis)
    @Volatile
    private var latestBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Show the room code that viewers will use
        roomCode = generateRoomCode()
        binding.tvRoomCode.text = "Room Code: $roomCode"
        binding.tvStatus.text = "Initializing..."

        initializeTFLite()
        startCameraX()
        initializeWebRTC()
        connectSignaling()
        startDetectionLoop()
    }

    private fun generateRoomCode(): String {
        return (1000..9999).random().toString()
    }

    private fun initializeTFLite() {
        catDetector = CatDetector(this)
        lifecycleScope.launch(Dispatchers.IO) {
            catDetector.initialize()
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "Ready — code: $roomCode"
            }
        }
    }

    private fun startCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                // Convert ImageProxy to Bitmap for TFLite
                val bitmap = imageProxy.toBitmap()
                latestBitmap = bitmap
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "CameraX binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initializeWebRTC() {
        eglBase = EglBase.create()
        webRtcManager = WebRtcManager(this, eglBase!!, isCamera = true)
        webRtcManager!!.initialize()

        // Local preview is handled by CameraX PreviewView; WebRTC captures separately
        // For a real implementation, share the same camera surface
    }

    private fun connectSignaling() {
        // Discover signaling server via mDNS or use fixed port on known LAN IP
        // For simplicity, use broadcast address; update SERVER_URL as needed
        val serverUrl = "ws://192.168.4.1"  // <-- Update to your signaling server IP

        signalingClient = SignalingClient(
            serverUrl = serverUrl,
            roomCode = roomCode,
            deviceId = deviceId,
            isCamera = true
        )

        lifecycleScope.launch {
            signalingClient!!.messages.collect { message ->
                handleSignalingMessage(message)
            }
        }

        signalingClient!!.connect()
    }

    private fun handleSignalingMessage(message: SignalingClient.SignalingMessage) {
        when (message) {
            is SignalingClient.SignalingMessage.PeerConnected -> {
                Log.i(TAG, "Viewer connected: ${message.peerId}")
                // Create WebRTC peer connection and offer
                setupPeerConnectionAndOffer(message.peerId)
            }
            is SignalingClient.SignalingMessage.Answer -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, message.sdp)
                webRtcManager?.setRemoteDescription(sdp)
            }
            is SignalingClient.SignalingMessage.IceCandidate -> {
                val candidate = IceCandidate(
                    message.sdpMid, message.sdpMLineIndex, message.candidate
                )
                webRtcManager?.addIceCandidate(candidate)
            }
            else -> {}
        }
    }

    private fun setupPeerConnectionAndOffer(peerId: String) {
        webRtcManager?.apply {
            startLocalStream(binding.previewView as? SurfaceViewRenderer ?: return)
            createPeerConnection()
            callbacks = object : WebRtcManager.Callbacks {
                override fun onLocalDescription(sdp: SessionDescription) {
                    signalingClient?.sendOffer(sdp.description)
                }
                override fun onIceCandidate(candidate: IceCandidate) {
                    signalingClient?.sendIceCandidate(
                        candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex
                    )
                }
                override fun onRemoteStreamAdded(stream: MediaStream) {}
                override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
                    runOnUiThread {
                        binding.tvConnectionStatus.text = "WebRTC: $state"
                    }
                }
            }
            createOffer()
        }
    }

    private fun startDetectionLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(DETECTION_INTERVAL_MS)
                val bitmap = latestBitmap ?: continue

                val result = catDetector.detectCat(bitmap)
                val newStatus = if (result.catDetected) STATUS_DETECTED else STATUS_LOST

                // Only broadcast on status change (avoid spamming)
                if (newStatus != lastStatus) {
                    lastStatus = newStatus
                    signalingClient?.sendStatus(newStatus)

                    withContext(Dispatchers.Main) {
                        updateStatusUI(result)
                    }
                } else {
                    // Still update UI confidence even without status change
                    withContext(Dispatchers.Main) {
                        updateStatusUI(result)
                    }
                }
            }
        }
    }

    private fun updateStatusUI(result: CatDetector.DetectionResult) {
        if (result.catDetected) {
            binding.tvStatus.text = "🐱 CAT DETECTED (${(result.confidence * 100).toInt()}%)"
            binding.tvStatus.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            )
            binding.overlayView.showDetection(true)
        } else {
            binding.tvStatus.text = "👁 Scanning... no cat"
            binding.tvStatus.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.darker_gray)
            )
            binding.overlayView.showDetection(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isDetecting.set(false)
        cameraExecutor.shutdown()
        catDetector.close()
        webRtcManager?.close()
        signalingClient?.disconnect()
        eglBase?.release()
    }
}
