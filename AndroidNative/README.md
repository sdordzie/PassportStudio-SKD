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

## v1.2.0 — Professional finish

- **Choose the photo size before you shoot.** Tap the size pill above the shutter: Passport 35 × 45 mm, Visa 2 × 2 in, ID 30 × 40 mm, Full photo, or your own custom size (mm / cm / in / px, 300 or 600 DPI). The camera frame and head guide change to match, and every photo is cropped to that size automatically. The custom size is also the Photo Editor's default.
- **One button style everywhere.** Single-line labels that never wrap, consistent heights and colours, sentence-case text, clear primary / secondary / danger actions.
- **Complete light and dark themes.** No stray Material purple; Print Designer, Backup, Control Centre, Customers and Gallery now read correctly in light mode.
- **Tidier screens.** New selection bar (select, all/none, delete) for customers, photos and jobs; job cards use a compact action row (Record payment, Receipt, Stage, Edit); chip rows wrap instead of being cut off; gallery preview adds WhatsApp.
- **About & developer** section in Studio Control Centre.
- Fix: importing a photo from the Gallery screen now opens it in the editor.

## v1.3.0 — Zoom in the editor

- Pinch to zoom (up to 600%), drag to move around, double-tap to zoom in / back to full view.
- Zoom buttons (+ / − / fit) with a live percentage on the preview.
- When zoomed in, a sharper high-resolution preview is rendered in the background so hair and background edges can be checked properly.
- Works with perspective correction: two fingers zoom and pan, one finger still drags the corner handles.
