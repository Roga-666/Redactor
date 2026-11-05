# Redactor (Android) — v0.1.0

A **100% on-device, offline Android** image/PDF whitelist-redaction prototype. No DOC/DOCX support.

> **Security status: early prototype, not certified for sensitive production use.** The app replaces pixels in a newly created PNG or image-only PDF. However, OCR may miss text entirely; the user must review and manually cover it. Neither OCR rechecking nor black-box overlays alone can prove a file is safe for release.

## Features

- Import JPG, PNG, WebP, and PDFs using Android's document picker. The picker requests local-only sources, though third-party document providers ultimately control access and may not honor that hint.
- Latin-script ML Kit OCR, **bundled with the app** (not dependent on first-run model downloads).
- Exact case-insensitive word whitelist; all detected non-whitelisted words are masked. Low-confidence OCR elements also get masked.
- Sanitary review screen: original with outlines vs redacted preview; finger-drag to add manual redaction boxes; undo/clear manual boxes.
- Every page requires an explicit inspection acknowledgement before export.
- Flatten into a new **PNG** (current page) or **image-only PDF** (all pages), no copied original text layers, annotations, vector objects or EXIF metadata.
- Local OCR scan of the finished pixels rejects recognized words not on the whitelist.
- `FLAG_SECURE`, disabled Android backup, no `INTERNET` permission, no permanent storage of imported source or unredacted rendered PDF pages.
- CI workflow produces a **debug APK** as GitHub Actions artifact.

## Get an APK from GitHub Actions

1. Create a **public** GitHub repository named `Redactor` under your account (or use another visibility if you change your mind). Initialize it with a README if you want the connected GitHub assistant to upload files in later steps.
2. Upload all project contents, including `.github/workflows/android.yml` (not the outer ZIP folder).
3. Open **Actions → Android APK → latest successful run → Artifacts → Redactor-debug-apk**. GitHub downloads an artifact ZIP containing `app-debug.apk`. Extract the APK and install it on Android.
4. For local building, open the project in Android Studio with Android SDK 35 / JDK 17, and use Gradle 8.11.1 (`gradle :app:assembleDebug`). A Gradle wrapper JAR is not included in this source archive; CI installs the exact Gradle version explicitly.

The debug APK is **not release-signed** and is intended for testing. Do not distribute as a verified security tool until independent testing is complete.

## Privacy and security design

- `android.permission.INTERNET` is intentionally omitted. The app cannot directly initiate network requests through ordinary Android networking APIs.
- Uses standalone `com.google.mlkit:text-recognition:16.0.1`, which bundles the OCR model.
- The original file remains with its Android document provider. Redactor decodes it into RAM but does not write the original to an app-managed file.
- `SecureExporter.sanitize` copies the source to a new bitmap and replaces redacted pixel rectangles with opaque black pixels. Its new bitmap is written as PNG or drawn as a raster image into a **new PDF**.
- The new PDF may contain visible, whitelisted text as pixels but **no searchable/selectable text layer**. The output is not reversible from blacked-out pixel content.
- OCR checks **do not ensure zero missed text**. Poor contrast, cursive writing, skewed pages, stamps, unusual scripts, and other content may evade both scans.
- Validating a file should include an additional independent OCR and manual visual inspection. Check that whitelisted words do not unintentionally reveal confidential context.
- Export is saved via system Storage Access Framework to a user-selected destination. If that destination is a cloud provider, the user may upload a redacted file; the app does not upload any file itself.
- Input files are **not** automatically erased; Android's storage provider, photo backups, or other apps may retain originals. In-memory bitmaps may persist until reclaimed by the OS.

## Current scope / limitations

- English/Latin OCR only; **no handwriting guarantee**, no OCR for other scripts.
- Maximum 12-page PDF and 36 million cumulative rendered pixels; fails without exporting partial documents when exceeded.
- For multi-page PDFs, use **Export PDF** for all pages; **Export PNG** exports just the selected page.
- No OCR result editing or selecting individual mistaken words yet; whitelist changes and manual cover controls are supported.
- No auto-detection of OCR-missed text. Manual review is required.
- A PDF may have complex forms or interactions not visible in a rendered page; only the visible raster image is carried into the output (intentional flattening).
- The output pixel edges are padded to reduce residual glyph fragments; this is not a proof against all OCR bounding-box errors.
- Large images may consume significant memory. The app does not attempt background jobs in v0.1.

## Developer structure

- `Whitelist.kt`: deterministic word matching.
- `DocumentImporter.kt`: decode images and render PDF pages in memory.
- `LocalOcr.kt`: bundled ML Kit word regions and final scan.
- `DocumentModels.kt`: page coordinates, auto-redaction boxes, manual boxes.
- `SecureExporter.kt`: create new sanitized pixels and flattened PNG/PDF.
- `MainActivity.kt`: Compose UI and Android SAF import/export.

## Reproducible verification checklist

1. Airplane mode, launch app, import offline PNG/PDF, perform OCR and export.
2. Confirm app manifest has no `INTERNET` permission.
3. Use a synthetic sample with an allowed word next to a sensitive word. Ensure sensitive pixels become fully black in PNG (sample test included).
4. For PDFs: attempt `pdftotext redacted.pdf -` and PDF object inspection; no original text objects should remain.
5. Re-OCR the exported raster and visually inspect all pages, rotations, line breaks, small lettering, and page boundaries.
6. Test accidental app interruption and failed storage permissions; no sensitive original document should be persisted by the app.
7. Audit app dependencies and minified release build before real-world sensitive use.

## License

Not yet selected. Public visibility does not automatically grant permission to redistribute or reuse source code. Choose an open-source license deliberately before inviting contributions.

## v0.2.0: Compact workspace and presets

The main document editor has a fixed-height layout: importing, switching presets, opening the collapsed allowed-content editor, marking each page reviewed, and exporting fit without vertical page scrolling. On small displays the preview area shrinks instead of adding document-page scroll.

- **Presets:** create, name, select, edit, rename, or delete any number of local presets. The existing v0.1 whitelist is migrated into the initial Quick preset.
- **Time ranges:** the Allowed editor has an opt-in time rule. The default covers 9:00 AM through 6:00 PM in 15-minute steps (37 times, including both endpoints). Add/edit multiple ranges with custom start/end and intervals. A time suffix is never globally whitelisted by these generated rules.
- **Erase:** tap or scribble over a redaction object to unmask that specific OCR word or manually drawn cover. A manual mask is selected first when masks overlap. Other instances of the same word remain redacted. Undo restores the last page edit.
- **Safety:** any rule, preset, or mask changes invalidate page review. Manual eraser exceptions are restricted to previously OCR-detected regions and exact matching contents during export verification. OCR can still miss visible text, so manual review is essential.
- **Privacy note:** moved into a collapsed Privacy & safety disclosure in the Allowed editor.

Profiles and whitelist rules are saved in private app preferences. No network permission is requested. Debug APKs built by GitHub Actions are not release-signed, independently security audited, or guaranteed to protect against OCR misses.
