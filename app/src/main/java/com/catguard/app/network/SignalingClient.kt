package com.catguard.app.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket-based signaling server client.
 *
 * Architecture:
 *  - Camera device acts as WebRTC "offerer" and WebSocket client
 *  - Viewer device acts as WebRTC "answerer" and WebSocket client
 *  - Both connect to a lightweight signaling server (Node.js or Python)
 *    running on port 8080 on the local network.
 *
 * 4-digit room code: cameras and viewers join the same "room" by code.
 *
 * Message format (JSON):
 *   { "type": "offer"|"answer"|"ice"|"status", "from": deviceId,
 *     "room": "1234", "data": <SDP or ICE or status string> }
 */
class SignalingClient(
    private val serverUrl: String,   // ws://192.168.x.x:8080
    private val roomCode: String,
    private val deviceId: String,
    private val isCamera: Boolean
) {
    companion object {
        private const val TAG = "SignalingClient"
    }

    sealed class SignalingMessage {
        data class Offer(val sdp: String, val fromId: String) : SignalingMessage()
        data class Answer(val sdp: String, val fromId: String) : SignalingMessage()
        data class IceCandidate(val candidate: String, val sdpMid: String,
                                val sdpMLineIndex: Int, val fromId: String) : SignalingMessage()
        data class StatusUpdate(val status: String, val fromId: String) : SignalingMessage()
        data class PeerConnected(val peerId: String) : SignalingMessage()
        data class PeerDisconnected(val peerId: String) : SignalingMessage()
    }

    private val _messages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 32)
    val messages: SharedFlow<SignalingMessage> = _messages

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null

    fun connect() {
        val request = Request.Builder()
            .url("$serverUrl/ws?room=$roomCode&id=$deviceId&role=${if (isCamera) "camera" else "viewer"}")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Signaling connected to room $roomCode")
                // Announce ourselves
                sendMessage(JSONObject().apply {
                    put("type", "join")
                    put("room", roomCode)
                    put("from", deviceId)
                    put("role", if (isCamera) "camera" else "viewer")
                })
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncoming(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: $reason")
                scheduleReconnect()
            }
        })
    }

    private fun handleIncoming(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            val from = json.optString("from")

            val msg: SignalingMessage? = when (type) {
                "offer" -> SignalingMessage.Offer(json.getString("data"), from)
                "answer" -> SignalingMessage.Answer(json.getString("data"), from)
                "ice" -> {
                    val ice = JSONObject(json.getString("data"))
                    SignalingMessage.IceCandidate(
                        ice.getString("candidate"),
                        ice.optString("sdpMid", ""),
                        ice.optInt("sdpMLineIndex", 0),
                        from
                    )
                }
                "status" -> SignalingMessage.StatusUpdate(json.getString("data"), from)
                "peer_joined" -> SignalingMessage.PeerConnected(from)
                "peer_left" -> SignalingMessage.PeerDisconnected(from)
                else -> null
            }

            msg?.let { scope.launch { _messages.emit(it) } }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    fun sendOffer(sdp: String) = sendMessage(JSONObject().apply {
        put("type", "offer")
        put("room", roomCode)
        put("from", deviceId)
        put("data", sdp)
    })

    fun sendAnswer(sdp: String) = sendMessage(JSONObject().apply {
        put("type", "answer")
        put("room", roomCode)
        put("from", deviceId)
        put("data", sdp)
    })

    fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) =
        sendMessage(JSONObject().apply {
            put("type", "ice")
            put("room", roomCode)
            put("from", deviceId)
            put("data", JSONObject().apply {
                put("candidate", candidate)
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
            }.toString())
        })

    fun sendStatus(status: String) = sendMessage(JSONObject().apply {
        put("type", "status")
        put("room", roomCode)
        put("from", deviceId)
        put("data", status)  // "OBJECT_DETECTED" or "OBJECT_LOST"
    })

    private fun sendMessage(json: JSONObject) {
        try {
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            Log.i(TAG, "Reconnecting...")
            connect()
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        scope.cancel()
    }
}
