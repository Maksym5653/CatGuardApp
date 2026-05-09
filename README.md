# 🐱 CatGuard — Smart Security Camera for Android

Android додаток, який перетворює смартфон на розумну камеру безпеки з розпізнаванням кота та управлінням ESP32 сигналізацією.

---

## 🏗 Архітектура системи

```
[Телефон-Камера]          [Сигналізаційний сервер]      [Телефон-Глядач]
  CameraActivity    ←ws→   Node.js :8080         ←ws→   ViewerActivity
  TFLite (EfficientDet)                                  AlarmLogic
  WebRTC offerer    ←p2p→                        ←p2p→  WebRTC answerer
        │                                                     │
        └──── status: OBJECT_DETECTED/LOST ─────────────────►│
                                                              │
                                                     [ESP32 @ 192.168.4.1]
                                               GET /toggle_alarm?state=on/off
```

---

## 📁 Структура проекту

```
CatGuardApp/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   └── efficientdet_lite0.tflite   ← СКАЧАТИ ВРУЧНУ (див. нижче)
│   └── java/com/catguard/app/
│       ├── ui/
│       │   ├── MainActivity.kt          ← Стартовий екран
│       │   └── DetectionOverlayView.kt  ← Анімований індикатор
│       ├── camera/
│       │   ├── CameraActivity.kt        ← Режим камери + TFLite
│       │   └── CameraStreamingService.kt← Foreground service
│       ├── viewer/
│       │   └── ViewerActivity.kt        ← Режим глядача + логіка тривоги
│       ├── network/
│       │   ├── SignalingClient.kt        ← WebSocket сигналізація
│       │   ├── WebRtcManager.kt          ← WebRTC peer connection
│       │   └── Esp32Controller.kt        ← HTTP до ESP32
│       └── ml/
│           └── CatDetector.kt            ← TFLite обгортка
├── signaling-server/
│   ├── signaling-server.js              ← Node.js WebRTC сервер
│   └── package.json
└── .github/workflows/
    └── android.yml                      ← GitHub Actions CI/CD
```

---

## ⚡ Швидкий старт

### Крок 1: Завантажити модель TFLite

```bash
# Завантажити EfficientDet-Lite0 (COCO, 80 класів включаючи "cat")
curl -L "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite" \
  -o app/src/main/assets/efficientdet_lite0.tflite
```

Або вручну: https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1

### Крок 2: Запустити сигналізаційний сервер

```bash
cd signaling-server
npm install
node signaling-server.js
# Сервер запустився на ws://YOUR_LAN_IP:8080
```

### Крок 3: Оновити IP в коді

У файлах `CameraActivity.kt` та `ViewerActivity.kt` знайди та заміни:
```kotlin
val serverUrl = "ws://192.168.1.100:8080"  // ← ТВІЙ IP
```

### Крок 4: Зібрати та встановити

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 🤖 Отримати APK через GitHub Actions

1. Завантаж цей репозиторій на GitHub
2. Зроби будь-який `git push`
3. GitHub автоматично збере APK
4. Знайди його у: **Actions → останній run → Artifacts** або **Releases**

---

## 📱 Як користуватися

### Camera Mode (Передавач)
1. Відкрий додаток → **Camera Mode**
2. На екрані з'явиться **4-значний код** (наприклад: `4721`)
3. Телефон починає сканувати кадр кожну секунду
4. Зелена рамка = кіт знайдений 🐱

### Viewer Mode (Глядач)
1. Відкрий додаток на іншому телефоні → **Viewer Mode**
2. Натисни **+ Add Camera**
3. Введи код з телефону-камери (наприклад: `4721`)
4. Підключи другу камеру так само (якщо є)
5. Екран розділиться навпіл для двох камер

### Логіка сигналізації
| Ситуація | Дія |
|----------|-----|
| Кіт є хоча б на 1 камері | Все гаразд, ESP32 = OFF |
| Кіт зник з УСІХ камер одночасно (2 сек) | 🚨 ESP32 = ON |
| Кіт знову з'явився | ESP32 = OFF |

---

## 🔧 ESP32 налаштування

ESP32 повинен бути в тій самій Wi-Fi мережі зі статичною IP `192.168.4.1`.

Приклад коду для ESP32 (Arduino):
```cpp
#include <WiFi.h>
#include <WebServer.h>

WebServer server(80);
const int ALARM_PIN = 2;

void setup() {
  WiFi.begin("YOUR_WIFI", "YOUR_PASS");
  // Або створити AP: WiFi.softAP("CatGuard", "password");
  
  server.on("/toggle_alarm", []() {
    String state = server.arg("state");
    digitalWrite(ALARM_PIN, state == "on" ? HIGH : LOW);
    server.send(200, "text/plain", "OK: " + state);
  });
  
  server.begin();
  pinMode(ALARM_PIN, OUTPUT);
}

void loop() {
  server.handleClient();
}
```

---

## 🧠 TFLite — пояснення підключення

У `app/build.gradle` додано такі залежності:

```groovy
// Основний рантайм
implementation 'org.tensorflow:tensorflow-lite:2.14.0'

// Допоміжна бібліотека (обробка зображень)
implementation 'org.tensorflow:tensorflow-lite-support:0.4.4'

// Task Vision API — найзручніший спосіб для object detection
implementation 'org.tensorflow:tensorflow-lite-task-vision:0.4.4'

// GPU делегат (опціонально, для прискорення)
implementation 'org.tensorflow:tensorflow-lite-gpu:2.14.0'
```

**ВАЖЛИВО** — без цього модель не знайдеться:
```groovy
android {
    aaptOptions {
        noCompress "tflite"  // не стискати .tflite файли!
    }
}
```

---

## 🐞 Поширені проблеми

| Проблема | Рішення |
|----------|---------|
| `FileNotFoundException: efficientdet_lite0.tflite` | Скачай модель і поклади в `assets/` |
| Камери не з'єднуються | Переконайся що обидва телефони в одній Wi-Fi мережі |
| ESP32 не відповідає | Перевір IP `192.168.4.1` та `usesCleartextTraffic="true"` у маніфесті |
| WebRTC не підключається | Перевір IP сигналізаційного сервера в коді |
| Build помилка "Duplicate class" | Додай `packagingOptions { exclude ... }` у build.gradle |

---

## 📜 Ліцензія

MIT — використовуй вільно.
