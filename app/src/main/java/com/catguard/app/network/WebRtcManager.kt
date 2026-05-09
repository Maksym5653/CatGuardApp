package com.catguard.app.network

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Manages a WebRTC PeerConnection.
 *
 * Camera side: creates local MediaStream (video + audio) and sends offer.
 * Viewer side: receives remote stream and renders to SurfaceViewRenderer.
 */
class WebRtcManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val isCamera: Boolean
) {

    companion object {
        private const val TAG = "WebRtcManager"

        // STUN server for ICE (works on LAN, but also allows internet fallback)
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    interface Callbacks {
        fun onLocalDescription(sdp: SessionDescription)
        fun onIceCandidate(candidate: IceCandidate)
        fun onRemoteStreamAdded(stream: MediaStream)
        fun onConnectionStateChange(state: PeerConnection.PeerConnectionState)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    var callbacks: Callbacks? = null

    fun initialize() {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory initialized")
    }

    /**
     * Sets up camera capture and creates local video/audio tracks.
     * Call only in Camera mode.
     */
    fun startLocalStream(localView: SurfaceViewRenderer): VideoTrack? {
        val factory = peerConnectionFactory ?: return null

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread", eglBase.eglBaseContext
        )

        videoCapturer = createCameraCapturer()
        val videoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        videoCapturer!!.startCapture(1280, 720, 30)

        localVideoTrack = factory.createVideoTrack("video_track", videoSource).also {
            it.addSink(localView)
            localView.init(eglBase.eglBaseContext, null)
            localView.setMirror(true)
        }

        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("audio_track", audioSource)

        Log.i(TAG, "Local stream started")
        return localVideoTrack
    }

    private fun createCameraCapturer(): CameraVideoCapturer {
        val enumerator = Camera2Enumerator(context)
        // Prefer back camera for security cam use-case
        val backCamera = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        val frontCamera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val deviceName = backCamera ?: frontCamera ?: enumerator.deviceNames.first()
        return enumerator.createCapturer(deviceName, null)
    }

    fun createPeerConnection(): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "New ICE candidate: ${candidate.sdp}")
                    callbacks?.onIceCandidate(candidate)
                }

                override fun onAddStream(stream: MediaStream) {
                    Log.i(TAG, "Remote stream added: ${stream.id}")
                    callbacks?.onRemoteStreamAdded(stream)
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    Log.i(TAG, "Connection state: $state")
                    callbacks?.onConnectionStateChange(state)
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )

        // Camera: add local tracks to peer connection
        if (isCamera) {
            val streamId = "local_stream"
            localVideoTrack?.let {
                peerConnection?.addTrack(it, listOf(streamId))
            }
            localAudioTrack?.let {
                peerConnection?.addTrack(it, listOf(streamId))
            }
        }

        return peerConnection
    }

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        callbacks?.onLocalDescription(sdp)
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) { Log.e(TAG, "SetLocalDesc failed: $p0") }
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "CreateOffer failed: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set")
                // Viewer creates answer after setting remote offer
                if (!isCamera) createAnswer()
            }
            override fun onSetFailure(error: String?) { Log.e(TAG, "SetRemoteDesc failed: $error") }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() { callbacks?.onLocalDescription(sdp) }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "CreateAnswer failed: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun initRemoteView(remoteView: SurfaceViewRenderer) {
        remoteView.init(eglBase.eglBaseContext, null)
        remoteView.setMirror(false)
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            localVideoTrack?.dispose()
            localAudioTrack?.dispose()
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnectionFactory?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebRTC: ${e.message}")
        }
    }
}
