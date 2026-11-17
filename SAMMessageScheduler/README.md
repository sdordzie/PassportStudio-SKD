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
Version: 6.1.0
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

 
## Phase 18 — Reports and analytics dashboard (5.5.0)
- Home dashboard includes total message count, messages created in the last seven days, failed messages and carrier-confirmed deliveries.
- Completion-rate indicator and SMS/WhatsApp channel distribution.
- Metrics are calculated from locally stored records; delivery counts depend on carrier delivery reports.

 
## Phase 19 — Birthday and anniversary reminders (5.6.0)
- Save optional birthdays and anniversaries on each contact in MM-dd format.
- Daily background check posts reminders on matching dates, with yearly recurrence.
- Per-contact, per-date deduplication prevents repeated alerts on the same day.
- Reminders notify the user to prepare a greeting; the app does not silently send birthday or anniversary messages.
- Room migration 4 to 5 adds optional contact date fields without deleting existing records.


## Phase 20 — App security (5.7.0)
- Optional app-opening authentication using Android BiometricPrompt.
- Supports strong biometrics or the device's secure screen-lock credential (PIN, pattern, or password).
- Authentication is configured in Settings → App security and remains off by default.
- Requires the user to configure a secure screen lock or biometric in Android Settings first.
- Password-protected backups use PBKDF2-HMAC-SHA256 key derivation and AES-256-GCM authenticated encryption.
- Encrypted restore validates the backup authentication tag; incorrect passwords or tampered files are rejected. Legacy plain JSON backups remain restorable.
- Version code: 21.


## Phase 21 — Message categories (5.8.0)
- Added persistent message categories: General, Personal, Family, Work, School, Business, Bulk, Birthday and Reminder.
- Categories can be selected while composing or editing a scheduled message.
- Message cards display the category and message search also matches category names.
- CSV export includes a Category column; CSV import reads it when present and defaults older files to General.
- JSON backup and restore preserve message categories.
- Room migration 5→6 adds the category column without deleting existing messages.
- Version code: 22.


## Phase 22 — Encrypted cloud-compatible full backup (v5.9.0)
- Export a complete encrypted backup of scheduled-message history and the SAM Contact Centre, including contact groups, birthdays and anniversaries.
- Use Android's document picker to save the backup to Google Drive or another installed cloud-storage provider; no cloud account password or provider credentials are collected by SAM.
- Restore the encrypted backup on this or another Android device with the same backup password.
- Existing message-only SAM backups remain compatible. Restored future scheduled messages are re-queued with WorkManager.
- Backup encryption uses the existing AES-256-GCM and PBKDF2 password-derived key protection. Keep the password safe; it cannot be recovered by SAM.
- Cloud file selection and upload/download are handled by Android and the selected storage provider. This phase provides user-directed cloud backup and restore, not continuous background synchronisation.


## Phase 23 — Advanced SMS automation rules (v6.0.0)
- Configure multiple keyword-based automatic reply rules, evaluated in the order entered; the first matching rule is used.
- Add an optional default reply using a blank keyword rule at the end of the list.
- Apply a 12-hour per-sender, per-rule cooldown to reduce repeated automatic replies.
- Filter SMS forwarding by an optional keyword, or leave the filter blank to forward all incoming SMS.
- Existing single-keyword automatic reply settings are retained as a compatibility fallback when no multi-rule list has been saved.
- Automation remains opt-in and requires Android SMS permissions. SMS charges may apply; forwarding shares message content with the configured destination.


## Phase 24 — Reliability, mobile UI and release preparation (v6.1.0)
- Refined dashboard statistic cards with more compact spacing for mobile screens.
- Scheduled-message search now includes message category as well as recipient, body and channel.
- Backup and restore passwords are cleared from the Activity's in-memory field as soon as the Android file picker returns, including cancellation paths.
- Disabled Android's automatic app-data backup so the app's private database and settings are not silently copied outside the explicit encrypted backup workflow.
- Use Settings → Cloud backup to create a password-protected full backup before moving SAM to another phone. Restore it with the same password.
- Build version code: 25; version name: 6.1.0.
- Release verification is based on the GitHub Actions Android build. Real-device SMS, WhatsApp hand-off, biometric prompts, notification timing and manufacturer battery behaviour still require testing on supported devices.
