package com.catguard.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Sends HTTP GET commands to the ESP32 alarm controller.
 *
 * ESP32 static IP: 192.168.4.1
 * Alarm ON:  GET http://192.168.4.1/toggle_alarm?state=on
 * Alarm OFF: GET http://192.168.4.1/toggle_alarm?state=off
 */
class Esp32Controller {

    companion object {
        private const val TAG = "Esp32Controller"
        private const val ESP32_BASE_URL = "http://192.168.4.1"
        private const val ALARM_ENDPOINT = "/toggle_alarm"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private var lastState: Boolean? = null  // null = unknown, true = on, false = off

    /**
     * Activates the alarm. No-op if alarm is already on (avoids flooding ESP32).
     */
    suspend fun activateAlarm(): Result<Unit> = sendCommand(true)

    /**
     * Deactivates the alarm. No-op if alarm is already off.
     */
    suspend fun deactivateAlarm(): Result<Unit> = sendCommand(false)

    private suspend fun sendCommand(turnOn: Boolean): Result<Unit> {
        // Debounce: don't resend same state
        if (lastState == turnOn) {
            Log.d(TAG, "Alarm already ${if (turnOn) "ON" else "OFF"}, skipping")
            return Result.success(Unit)
        }

        return withContext(Dispatchers.IO) {
            try {
                val state = if (turnOn) "on" else "off"
                val url = "$ESP32_BASE_URL$ALARM_ENDPOINT?state=$state"
                Log.i(TAG, "Sending alarm command: $url")

                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    lastState = turnOn
                    Log.i(TAG, "ESP32 responded: ${response.code} - alarm $state")
                    Result.success(Unit)
                } else {
                    Log.w(TAG, "ESP32 error response: ${response.code}")
                    Result.failure(Exception("ESP32 returned ${response.code}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reach ESP32: ${e.message}")
                Result.failure(e)
            }
        }
    }

    fun resetState() {
        lastState = null
    }
}
