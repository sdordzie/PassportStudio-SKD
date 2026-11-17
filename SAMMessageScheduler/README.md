# SAM Message Scheduler — Android Phase 3

Native Android app using Kotlin, Jetpack Compose, Room and WorkManager.

## Phase 3 features
- Scheduled SMS submission using Android's SMS service after explicit permission.
- WhatsApp hand-off: opens a pre-filled WhatsApp chat at the scheduled time; the user must review and tap Send.
- One-time, daily, weekly and monthly schedules.
- Quick message templates for greetings, reminders, birthdays and meetings.
- Search messages by recipient, channel or message text.
- Filter by all, upcoming, sent and failed messages.
- Local Room storage and migration from previous versions.
- Background scheduling through WorkManager.

## Important delivery notes
SMS status "SENT" means Android accepted the send request; it does not prove carrier delivery or recipient receipt. Delivery depends on the SIM, network, device settings and carrier. Android may restrict SMS permissions for apps distributed through Google Play; this APK is intended for direct installation/testing.
WhatsApp does not provide a general public API for silently sending personal messages. SAM prepares the chat and leaves the final Send action to the user.

## Build
GitHub Actions builds a debug APK with JDK 17, Gradle 8.9 and Android SDK. Download the artifact named SAM-Message-Scheduler-APK from the Actions run.

Package: com.bigsammy.sammessagescheduler
Version: 3.0.0
Minimum Android: 8.0 (API 26)
Target SDK: 35
