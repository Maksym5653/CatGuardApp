# TensorFlow Lite
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.task.** { *; }
-dontwarn org.tensorflow.**

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# App classes
-keep class com.catguard.app.** { *; }
