# SAM Message Scheduler — Android Phase 8

Native Android app using Kotlin, Jetpack Compose, Room and WorkManager.

## Phase 8 recurring schedule controls
- Pause a scheduled message without deleting its content or recipient.
- Resume a paused message and rebuild its pending schedule.
- Edit paused messages.
- Filter the message list to show paused items.
- Paused items are excluded from automatic schedule reconciliation and will not run until resumed.

## Phase 7 reliability
- Reconciles saved scheduled messages whenever the app opens.
- Rebuilds pending WorkManager jobs from the database.
- Uses one stable unique-work name per message to replace stale pending jobs.

## Phase 6 backups
- Export all saved message records to portable JSON.
- Restore records and re-enqueue future scheduled messages.

## Earlier features
- Needs Attention dashboard, retry, and mark done.
- Edit/reschedule, responsive composer, quick templates, search and filters.
- Scheduled SMS submission after explicit permission.
- WhatsApp hand-off prepares a chat; the user must review and tap Send.
- One-time, daily, weekly and monthly schedules.
- Local Room storage and WorkManager background scheduling.

## Delivery notes
SMS status "SENT" means Android accepted the send request; it does not prove carrier delivery or recipient receipt. Android background execution and manufacturer battery controls can affect exact timing.
WhatsApp messages are not silently sent; SAM leaves the final Send action to the user.

## Build
GitHub Actions builds a debug APK with JDK 17, Gradle 8.9 and Android SDK.

Package: com.bigsammy.sammessagescheduler
Version: 4.5.0
Minimum Android: 8.0 (API 26)
Target SDK: 35
