package com.catguard.app.viewer

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.catguard.app.camera.CameraActivity
import com.catguard.app.databinding.ActivityViewerBinding
import com.catguard.app.network.Esp32Controller
import com.catguard.app.network.SignalingClient
import com.catguard.app.network.WebRtcManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ViewerActivity"
        private const val MAX_CAMERAS = 2
        // Alarm triggers when ALL cameras report LOST simultaneously
        private const val ALL_LOST_TIMEOUT_MS = 2000L  // Grace period before triggering alarm
    }

    private lateinit var binding: ActivityViewerBinding

    // Map: cameraId -> last known status
    private val cameraStatuses = ConcurrentHashMap<String, String>()
    // Map: cameraId -> SignalingClient
    private val signalingClients = ConcurrentHashMap<String, SignalingClient>()
    // Map: cameraId -> WebRtcManager
    private val webRtcManagers = ConcurrentHashMap<String, WebRtcManager>()

    private lateinit var esp32Controller: Esp32Controller
    private var eglBase: EglBase? = null
    private val deviceId = UUID.randomUUID().toString().take(8)

    private var alarmActive = false
    private var allLostJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        eglBase = EglBase.create()
        esp32Controller = Esp32Controller()

        setupRemoteViews()
        setupButtons()

        binding.tvAlarmStatus.text = "No cameras connected"
    }

    private fun setupRemoteViews() {
        binding.surfaceView1.init(eglBase!!.eglBaseContext, null)
        binding.surfaceView1.setMirror(false)
        binding.surfaceView2.init(eglBase!!.eglBaseContext, null)
        binding.surfaceView2.setMirror(false)
    }

    private fun setupButtons() {
        binding.btnAddCamera.setOnClickListener {
            if (signalingClients.size >= MAX_CAMERAS) {
                Toast.makeText(this, "Maximum $MAX_CAMERAS cameras connected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showAddCameraDialog()
        }

        binding.btnDisconnectAll.setOnClickListener {
            disconnectAll()
        }

        binding.btnTestAlarm.setOnClickListener {
            lifecycleScope.launch {
                if (!alarmActive) {
                    esp32Controller.activateAlarm()
                    binding.tvAlarmStatus.text = "🔴 TEST ALARM ON"
                    delay(3000)
                    esp32Controller.deactivateAlarm()
                    binding.tvAlarmStatus.text = "✅ Alarm off"
                }
            }
        }
    }

    private fun showAddCameraDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Enter 4-digit room code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }

        AlertDialog.Builder(this)
            .setTitle("Connect to Camera")
            .setMessage("Enter the 4-digit code shown on the camera device")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val code = input.text.toString()
                if (code.length == 4) {
                    connectToCamera(code)
                } else {
                    Toast.makeText(this, "Please enter exactly 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectToCamera(roomCode: String) {
        if (signalingClients.containsKey(roomCode)) {
            Toast.makeText(this, "Already connected to room $roomCode", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Connecting to room $roomCode...", Toast.LENGTH_SHORT).show()

        val serverUrl = "ws://192.168.4.1"  // Update to your signaling server IP
        val sigClient = SignalingClient(
            serverUrl = serverUrl,
            roomCode = roomCode,
            deviceId = deviceId,
            isCamera = false
        )
        signalingClients[roomCode] = sigClient

        // Initialize WebRTC for this camera slot
        val remoteView = if (signalingClients.size == 1) binding.surfaceView1 else binding.surfaceView2
        val webRtc = WebRtcManager(this, eglBase!!, isCamera = false)
        webRtc.initialize()
        webRtcManagers[roomCode] = webRtc

        updateCameraLayout()

        lifecycleScope.launch {
            sigClient.messages.collect { message ->
                handleSignalingMessage(roomCode, message, webRtc, remoteView)
            }
        }

        sigClient.connect()

        // Initialize status as unknown
        cameraStatuses[roomCode] = CameraActivity.STATUS_DETECTED  // Assume cat is present until told otherwise
    }

    private fun handleSignalingMessage(
        roomCode: String,
        message: SignalingClient.SignalingMessage,
        webRtc: WebRtcManager,
        remoteView: SurfaceViewRenderer
    ) {
        when (message) {
            is SignalingClient.SignalingMessage.Offer -> {
                Log.i(TAG, "Received offer from camera $roomCode")
                webRtc.createPeerConnection()
                webRtc.callbacks = object : WebRtcManager.Callbacks {
                    override fun onLocalDescription(sdp: SessionDescription) {
                        signalingClients[roomCode]?.sendAnswer(sdp.description)
                    }
                    override fun onIceCandidate(candidate: IceCandidate) {
                        signalingClients[roomCode]?.sendIceCandidate(
                            candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex
                        )
                    }
                    override fun onRemoteStreamAdded(stream: MediaStream) {
                        runOnUiThread {
                            stream.videoTracks.firstOrNull()?.addSink(remoteView)
                            Log.i(TAG, "Remote video rendering for room $roomCode")
                        }
                    }
                    override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
                        Log.i(TAG, "Camera $roomCode WebRTC: $state")
                        if (state == PeerConnection.PeerConnectionState.DISCONNECTED ||
                            state == PeerConnection.PeerConnectionState.FAILED) {
                            handleCameraDisconnected(roomCode)
                        }
                    }
                }
                val sdp = SessionDescription(SessionDescription.Type.OFFER, message.sdp)
                webRtc.setRemoteDescription(sdp)
            }
            is SignalingClient.SignalingMessage.IceCandidate -> {
                webRtc.addIceCandidate(
                    IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate)
                )
            }
            is SignalingClient.SignalingMessage.StatusUpdate -> {
                handleCameraStatusUpdate(roomCode, message.status)
            }
            is SignalingClient.SignalingMessage.PeerDisconnected -> {
                handleCameraDisconnected(roomCode)
            }
            else -> {}
        }
    }

    /**
     * Core alarm logic:
     * - If ANY camera reports OBJECT_DETECTED → deactivate alarm
     * - If ALL cameras report OBJECT_LOST simultaneously → activate alarm (after grace period)
     */
    private fun handleCameraStatusUpdate(cameraId: String, status: String) {
        cameraStatuses[cameraId] = status
        Log.d(TAG, "Camera $cameraId status: $status | All: $cameraStatuses")

        runOnUiThread { updateStatusDisplay() }

        val allLost = cameraStatuses.values.all { it == CameraActivity.STATUS_LOST }
        val anyDetected = cameraStatuses.values.any { it == CameraActivity.STATUS_DETECTED }

        when {
            anyDetected -> {
                // Cat seen on at least one camera — cancel any pending alarm trigger
                allLostJob?.cancel()
                if (alarmActive) {
                    alarmActive = false
                    lifecycleScope.launch {
                        esp32Controller.deactivateAlarm()
                        Log.i(TAG, "Alarm DEACTIVATED — cat visible on camera $cameraId")
                    }
                }
            }
            allLost && cameraStatuses.size > 0 -> {
                // Start grace-period timer before triggering alarm
                allLostJob?.cancel()
                allLostJob = lifecycleScope.launch {
                    Log.i(TAG, "All cameras lost cat — waiting ${ALL_LOST_TIMEOUT_MS}ms...")
                    delay(ALL_LOST_TIMEOUT_MS)
                    // Re-check after delay
                    val stillAllLost = cameraStatuses.values.all { it == CameraActivity.STATUS_LOST }
                    if (stillAllLost && !alarmActive) {
                        alarmActive = true
                        esp32Controller.activateAlarm()
                        Log.i(TAG, "ALARM ACTIVATED — cat missing from all cameras!")
                        runOnUiThread {
                            binding.tvAlarmStatus.text = "🚨 ALARM! Cat missing!"
                            binding.tvAlarmStatus.setBackgroundColor(
                                android.graphics.Color.RED
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateStatusDisplay() {
        if (cameraStatuses.isEmpty()) {
            binding.tvAlarmStatus.text = "No cameras connected"
            binding.tvAlarmStatus.setBackgroundColor(
                resources.getColor(android.R.color.darker_gray, theme)
            )
            return
        }

        val lines = cameraStatuses.entries.mapIndexed { i, (id, status) ->
            val emoji = if (status == CameraActivity.STATUS_DETECTED) "🐱" else "👁"
            "Cam ${i + 1}: $emoji $status"
        }

        if (!alarmActive) {
            binding.tvAlarmStatus.text = lines.joinToString("\n")
            val allSafe = cameraStatuses.values.all { it == CameraActivity.STATUS_DETECTED }
            binding.tvAlarmStatus.setBackgroundColor(
                resources.getColor(
                    if (allSafe) android.R.color.holo_green_dark else android.R.color.holo_orange_dark,
                    theme
                )
            )
        }
    }

    private fun handleCameraDisconnected(roomCode: String) {
        Log.w(TAG, "Camera $roomCode disconnected")
        cameraStatuses.remove(roomCode)
        signalingClients.remove(roomCode)
        webRtcManagers[roomCode]?.close()
        webRtcManagers.remove(roomCode)
        runOnUiThread {
            updateCameraLayout()
            updateStatusDisplay()
            Toast.makeText(this, "Camera $roomCode disconnected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCameraLayout() {
        val count = signalingClients.size
        when (count) {
            0 -> {
                binding.surfaceView1.visibility = View.GONE
                binding.surfaceView2.visibility = View.GONE
                binding.tvNoCameras.visibility = View.VISIBLE
            }
            1 -> {
                binding.surfaceView1.visibility = View.VISIBLE
                binding.surfaceView2.visibility = View.GONE
                binding.tvNoCameras.visibility = View.GONE
            }
            2 -> {
                binding.surfaceView1.visibility = View.VISIBLE
                binding.surfaceView2.visibility = View.VISIBLE
                binding.tvNoCameras.visibility = View.GONE
            }
        }
    }

    private fun disconnectAll() {
        signalingClients.forEach { (_, client) -> client.disconnect() }
        webRtcManagers.forEach { (_, manager) -> manager.close() }
        signalingClients.clear()
        webRtcManagers.clear()
        cameraStatuses.clear()
        updateCameraLayout()
        binding.tvAlarmStatus.text = "Disconnected"
    }

    override fun onDestroy() {
        super.onDestroy()
        allLostJob?.cancel()
        disconnectAll()
        binding.surfaceView1.release()
        binding.surfaceView2.release()
        eglBase?.release()
    }
}
