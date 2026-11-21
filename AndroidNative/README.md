# PassportPro Studio — Native Android

The native Android application is being developed alongside the existing PassportStudio Pro PWA. Existing web application files are preserved.

Phase 1 foundation: Kotlin + Jetpack Compose dashboard and navigation. Package: `gh.com.bigsammy.passportpro`. Minimum Android API 26.

Camera capture, AI/manual editing, WhatsApp delivery, printing, customer management, business reports and backup will be delivered in subsequent phases.
## v1.1.0 — Instant delivery

- **Capture → send in one step.** After the shutter, PassportPro frames the head to passport proportions, replaces the background with AI and opens the share menu (or WhatsApp) automatically. No trip to the Gallery.
- **Auto-send setting** on the camera and result screens: Share menu (default), WhatsApp, or Off.
- **Ready-to-deliver screen:** size presets (Studio size, Passport 35 × 45 mm, Visa 2 × 2 in, Full photo), background swatches, brightness, WhatsApp / Share / Save / Retake / Next customer. Choices are remembered.
- **Faster camera:** full-screen viewfinder with passport frame, low-latency capture, camera pauses while the photo is processed, photo import from the camera screen.
- **Editor:** tools grouped into Background / Crop / Adjust / Transform tabs; Save & Share and WhatsApp straight from the save dialog.
