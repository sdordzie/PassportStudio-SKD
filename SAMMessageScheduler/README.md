# SAM Message Scheduler — Android Phase 13

Native Android app using Kotlin, Jetpack Compose, Room and WorkManager.

## Phase 13 contact centre
- New Contacts tab with local contact storage in Room.
- Add contacts manually or pick an existing phone contact through Android's contact picker; SAM only receives the selected contact's name and phone number.
- Search contacts, edit saved details, delete locally, and organise contacts using named groups such as Family, Staff, Students or Customers.
- Database migration 2 to 3 adds contact storage without deleting existing scheduled messages.

## Phase 12 CSV bulk import
- Import the app's exported CSV history or a compatible CSV with Recipient, Message, Channel and Scheduled At columns.
- Validate each row, accepting only supported channels, repeat rules, valid phone numbers and future schedule times.
- Review a preview of valid messages and see how many invalid or past-due rows will be skipped before confirming.
- Imported messages are saved locally and their future WorkManager schedules are created.

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
Version: 5.0.0
Minimum Android: 8.0 (API 26)
Target SDK: 35


## Phase 14 — Bulk messaging (v5.1.0)
- Select multiple contacts from the SAM Contact Centre, including select-all and clear-all.
- Compose one message and schedule a separate message for each selected contact.
- Personalisation tokens: `{name}` and `{phone}`.
- Choose SMS or WhatsApp and a shared future date/time.
- SMS permission is requested before bulk schedules are created. WhatsApp messages open prepared chats for user review and confirmation.
- Each recipient receives an independent scheduled record, so messages can be managed individually.


## Phase 15 — Advanced scheduling and quiet hours (v5.2.0)
- Configure daily quiet hours with 24-hour start and end times, including overnight windows.
- Scheduled messages due during quiet hours are deferred until the quiet window ends.
- The original scheduled timestamp is preserved, so recurring daily, weekly and monthly schedules keep their intended cadence.
- Existing daily, weekly and monthly repeat options continue to schedule the next occurrence.


## Phase 16 — SMS delivery tracking and dual-SIM awareness (v5.3.0)
- SMS submission uses the Android system's default SMS subscription, including the preferred SIM configured on dual-SIM phones.
- The message list records separate SMS delivery states: pending, submitted, sent, delivered, send failed and delivery failed.
- Android sent/delivery callbacks update the saved message and show a notification when the mobile network reports a result.
- Delivery reports depend on carrier and network support; a missing delivery callback is not proof of failure.
- Change the preferred SMS SIM in Android system SIM settings. SAM does not silently switch SIMs or override carrier defaults.


## Phase 17 — Automatic replies and SMS forwarding (5.4.0)
- Optional automatic replies to incoming SMS, for all messages or keyword matches.
- Optional forwarding of incoming SMS content to a configured phone number.
- Both controls are off by default under Settings → Incoming SMS automation.
- Requires Android RECEIVE_SMS and SEND_SMS permissions; carrier charges may apply.
- Forwarding copies message content to the chosen destination. This feature applies to SMS, not WhatsApp.
