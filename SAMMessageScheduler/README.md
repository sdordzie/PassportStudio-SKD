# SAM Message Scheduler — Android Phase 1
Native Android app using Kotlin, Jetpack Compose, Room and WorkManager.

## Phase 1 features
- Dashboard and navigation
- SMS and WhatsApp reminder composer
- Local Room database
- WorkManager scheduled reminders
- Scheduled message list, cancellation and mark-complete
- Chronological calendar view
- Notification permission request on Android 13+

## Message delivery
This release is reminder-first. At the scheduled time the app posts a notification so the user can send through SMS or WhatsApp. It does not silently transmit messages. Automatic WhatsApp interaction and direct SMS integrations require separate implementation, device testing and policy review.

## Build
GitHub Actions builds a debug APK with JDK 17, Gradle 8.9 and Android SDK. Download the artifact named SAM-Message-Scheduler-debug-APK from the Actions run.

Package: com.bigsammy.sammessagescheduler
Minimum Android: 8.0 (API 26)
Target SDK: 35
