# SAM Message Scheduler — Android Phase 7

Native Android app using Kotlin, Jetpack Compose, Room and WorkManager.

## Phase 7 reliability improvements
- Reconciles all saved scheduled messages whenever the app opens.
- Rebuilds pending WorkManager jobs from the database after app relaunch, including schedules whose time has passed while the app was closed.
- Uses one stable unique-work name per message, so rescheduling replaces the previous pending job instead of leaving stale duplicate jobs behind.
- Reconciliation helps correct pending delays after manual device clock or time-zone changes. Android background execution and manufacturer battery controls can still affect exact timing.

## Phase 6
- Export all scheduled message records to a portable JSON backup using Android's document picker.
- Restore records from a SAM JSON backup and re-enqueue future scheduled messages.

## Phase 5
- Attention dashboard for failed, permission-required and WhatsApp-awaiting-user messages.
- Filter messages that need attention.
- Retry failed actions; SMS retries request permission when needed.
- Marking a scheduled message complete cancels its pending background work.

## Earlier features
- Edit and reschedule existing scheduled messages.
- Responsive composer, wrapping template/repeat controls, and scrollable screens.
- Scheduled SMS submission using Android's SMS service after explicit permission.
- WhatsApp hand-off: opens a pre-filled WhatsApp chat at the scheduled time; the user must review and tap Send.
- One-time, daily, weekly and monthly schedules.
- Quick templates, search and status filters.
- Local Room storage and background scheduling through WorkManager.

## Important delivery notes
SMS status "SENT" means Android accepted the send request; it does not prove carrier delivery or recipient receipt. Android may restrict SMS permissions for apps distributed through Google Play; this APK is intended for direct installation/testing.
WhatsApp does not provide a general public API for silently sending personal messages. SAM prepares the chat and leaves the final Send action to the user.

## Build
GitHub Actions builds a debug APK with JDK 17, Gradle 8.9 and Android SDK. Download the artifact named SAM-Message-Scheduler-APK from the Actions run.

Package: com.bigsammy.sammessagescheduler
Version: 4.4.0
Minimum Android: 8.0 (API 26)
Target SDK: 35
