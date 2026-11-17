# SAM Message Scheduler — Android Phase 9

Native Android app using Kotlin, Jetpack Compose, Room and WorkManager.

## Phase 9 spreadsheet export
- Export all locally saved message records to a CSV file through Android's document picker.
- Includes record ID, recipient, message text, channel, scheduled date/time, status, repeat rule and creation date.
- CSV values are escaped and UTF-8 BOM is included for improved spreadsheet compatibility.
- Open the exported file in Microsoft Excel, Google Sheets or another spreadsheet application.

## Phase 8 recurring schedule controls
- Pause a scheduled message without deleting its content or recipient.
- Resume a paused message and rebuild its pending schedule.
- Edit paused messages and filter the list to show paused items.
- Paused items are excluded from automatic schedule reconciliation.

## Phase 7 reliability
- Reconciles saved scheduled messages whenever the app opens.
- Rebuilds pending WorkManager jobs and uses one stable unique-work name per message.

## Backups and earlier features
- Export and restore full JSON backups.
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
Version: 4.6.0
Minimum Android: 8.0 (API 26)
Target SDK: 35
