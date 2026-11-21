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

## v1.4.0 — Pro features (all off by default)

Switch them on in **Studio Control Centre → Pro features**, or **Pro tools** on the Ready-to-deliver screen. With everything off, the app works exactly as before.

- **Automatic photo checks** — head size (using the real top of the hair from the AI mask), centring, tilt/turn, eyes open, neutral expression, even lighting, exposure, sharpness, glasses glare and background. US photos also get the eye-height check. Auto-send pauses if a check fails.
- **Document size library** — Ghana passport (35 × 45 mm, head 31.5–36 mm, white), US passport/visa (2 × 2 in, head 25–35 mm, white), UK passport (35 × 45 mm, head 29–34 mm, light grey/cream), Schengen visa (35 × 45 mm, head 32–36 mm, light), Canada passport (50 × 70 mm, head 31–36 mm, white). Picking one also picks its background; light grey and cream backgrounds are added.
- **HD background removal** — ML Kit Subject Segmentation with a guided-filter hair-edge refinement, used on the delivery screen and in the editor. The model is downloaded by Google Play services the first time (internet needed once); the app falls back to the standard model until then.
- **Print straight from capture** — lays copies at true size on 4 × 6 in or A4 with cut guides, then prints through Android's print service or shares the PDF.

## v1.4.1 — One button per pro feature

Four separate on/off buttons (Photo checks, Doc library, HD background, Quick print) on the Home screen and the Ready-to-deliver screen. Each tap switches only that feature. The full descriptions and switches remain in Studio Control Centre.
