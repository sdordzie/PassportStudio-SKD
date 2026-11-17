# SAM Message Scheduler — Android Phase 2

Native Android app using Kotlin, Jetpack Compose, Room and WorkManager.

## Phase 2 features
- Automatic scheduled SMS submission using Android's SMS service (requires explicit SMS permission and a SIM/mobile service).
- WhatsApp scheduled hand-off: at the scheduled time, SAM posts a notification that opens a pre-filled WhatsApp chat. The user must review and tap Send; WhatsApp messages are not silently sent.
- One-time, daily, weekly and monthly schedules.
- Local Room storage with a non-destructive database migration from Phase 1.
- Background WorkManager execution and recurring rescheduling.
- Scheduled list, cancellation, calendar and notification workflow.

## Important delivery notes
SMS status "SENT" means Android accepted the SMS send request; it does not prove carrier delivery or recipient receipt. Carrier delivery depends on the SIM, network, device settings and provider. Android may restrict SMS permissions for apps distributed through Google Play; this APK is intended for direct installation/testing.
WhatsApp does not provide a general public API for silently sending personal messages. SAM therefore prepares the chat and leaves the final Send action to the user.

## Build
GitHub Actions builds a debug APK with JDK 17, Gradle 8.9 and Android SDK. Download the artifact named SAM-Message-Scheduler-APK from the Actions run.

Package: com.bigsammy.sammessagescheduler
Minimum Android: 8.0 (API 26)
Target SDK: 35
