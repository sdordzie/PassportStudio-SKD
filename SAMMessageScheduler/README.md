# SAM Message Scheduler — Android Phase 11

Native Android app using Kotlin, Jetpack Compose, Room and WorkManager.

## Phase 11 safe message removal
- Selecting Cancel now opens a confirmation dialog showing the recipient.
- Keep message dismisses the dialog without changing the saved message or its scheduled work.
- Remove message confirms the action and cancels the scheduled work before deleting the record.

## Phase 10 message duplication
- Duplicate any saved message from the message list, including completed, failed, paused and scheduled items.
- Copies recipient, message body and channel into a new draft.
- Sets the new schedule to one hour from the time of duplication.
- Resets repeat frequency to Never so the copy does not unintentionally create a second recurring series.
- The original record remains unchanged; saving creates a separate scheduled record.

## Phase 9 spreadsheet export
- Export all local message records to CSV with recipient, body, channel, scheduled time, status, repeat rule and creation time.
- CSV values are escaped and UTF-8 BOM is included for spreadsheet compatibility.

## Earlier features
- Pause, resume and edit scheduled messages.
- Reconcile pending WorkManager jobs when the app opens.
- Export and restore full JSON backups.
- Needs Attention dashboard, retry, mark done, search and status filters.
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
Version: 4.8.0
Minimum Android: 8.0 (API 26)
Target SDK: 35
