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

## v0.3.0: Smart Cover, detailed export review and default folder

- **Smart Cover:** tap a detected word to cover that whole OCR region; scribble across several words to cover all touched regions. Longer drags with no OCR matches use a manual area mask for missed words. Erase continues to remove only selected masks. Undo restores the previous page state.
- **Export review:** before opening the save picker or writing to the default folder, sanitize the image and run a second offline OCR pass. If unexpected words are seen, show their full text and page numbers in a scrollable dialog. Tap **Cover found text** to add all corresponding masks, then manually re-review the affected pages and export again. Export is not claimed successful while validation is blocked.
- **Settings (⚙):** choose a default export directory through Android's Storage Access Framework; Redactor retains the selected directory permission and writes new PNG/PDF files directly there. Use **Ask every time instead** to restore the regular Save As picker. Not all folder providers are local—select an on-device directory to avoid cloud sync.
- **Preserved security design:** no Internet permission, bundled OCR, flattened output. A successful OCR recheck is not an absolute proof of no sensitive information; human inspection remains essential.
- **Build:** GitHub Actions compiles `app-debug.apk` under the latest Android APK workflow. An Android 11+ picker may refuse selecting root Downloads or root of shared storage; create a dedicated subfolder in Documents instead.


## v0.4.0 — Screenshots and automatic export destinations

- Screenshots and screen recording remain blocked by default. In **Settings → Allow screenshots**,
  explicitly enable capture (and disable it again when finished). This is saved locally.
  Enabling captures can expose unredacted document contents, including in recent-app previews.
- The reviewed toggle reads **Review** / **Reviewed** (no checkmark).
- With a default export folder, **Export PNG** and **Export PDF** write there immediately without
  launching the Android filename/location picker. A brief success toast confirms saving.
  If no folder is selected, Android's CreateDocument screen still lets you name and place the file.
- Note: OCR whitelist misclassifications are under investigation; the screenshot toggle helps
  collect non-sensitive reproduction screenshots. Secure export still requires review.

## v0.5.0 — OCR whitelist verification fix

- An **exact** user-whitelisted OCR word or phrase is no longer automatically covered
  solely because OCR reports less than 80% confidence. Every page still requires
  deliberate review. The UI reports weak-confidence permitted matches.
- Export verification matches surviving text to **original visible source regions**
  that were allowed (or manually revealed); a different confidence score in the
  second pass cannot turn an original permitted `EP` or `E` into an export error.
- Unexpected text, fragments like `N`, unrecognized source text, or text within
  covered areas still blocks export. Cover those regions before trying again.
- Exact whitelist matches are based on OCR recognition, which can still be wrong.
  Carefully inspect every document before sharing it.

## v0.6.0 — Profile backup, smarter OCR, and drawing tools

- **Redact** and **Erase** use identical dark buttons with a green outline
  indicating the active tool. Icons depict a blacked-out bar and an eraser.
- The eraser's blue trace is visible during every scribble and briefly afterward.
  Combined gesture detection handles taps and strokes consistently.
- Undo retains the most recent **five** document editing actions (not unlimited).
- **Presets → Export all** saves a portable JSON backup of every preset,
  including allowed entries and time ranges. **Import backup** merges profiles
  without overwriting existing profiles; conflicting edits receive new IDs.
  The JSON contains personal whitelist entries, so choose a local destination.
- Whitelisting supports OCR that joins already-approved labels into a single
  element, such as "EP/E" or "NP-CL"; it never approves just a substring of
  longer unexpected OCR text. Unknown words still stay covered.
- The bundled OCR model remains fully offline, and the APK still has no
  Android Internet permission.
- Exact OCR recognition of short labels is inherently imperfect: inspect
  **every** page, particularly eraser-revealed regions, before export. Do not
  treat builds without on-device verification as certified for sensitive data.

## v0.7.0 — Optional export with unresolved OCR findings

- **Settings → Allow export with OCR warnings** defaults **off**. With it off,
  export is blocked when the second offline OCR scan finds unexpected words.
- Enabling it explicitly permits export **despite** findings from that scan,
  with a prominent warning in Settings and a warning count on saved files.
- The scan still runs and its warning count is shown. Redaction pixels are still
  permanently replaced in a freshly flattened PNG or image-only PDF.
- **All document pages must still be marked Reviewed**. This is not a way to
  export unreviewed documents or restore covered pixels.
- The option is intended for known OCR false positives only. If the scan is
  correct, the saved document may visibly expose sensitive/unapproved text.
  Inspect the saved output before sharing. The app is experimental.

## v0.8.0 — Visual Whitelist brush and supplied launcher icon

- Uses the user-provided circular mint document artwork as a **512px
  adaptive Android launcher icon** (transparent WebP foreground).
- Adds **Whitelist** beside Redact and Erase. This mode shows the original
  document with existing redactions as **translucent amber regions**, so text
  is readable while selecting it. This temporary preview is never exported.
- Tap or scribble over **currently redacted, OCR-recognized** words to append
  the corresponding OCR text to the *active* preset. All selected terms are
  saved immediately and reused when the preset is selected again.
- Whitelist additions (and conservatively reversible manual masks) participate
  in the existing five-step Undo. Undo restores the prior saved whitelist
  and its original page state.
- Changing a whitelist can change every page's visibility. Every page must
  therefore be re-reviewed before the next export.
- Unrecognized regions are not guessed. OCR errors may add the wrong text;
  inspect the Allowed editor to confirm what was saved. Wide manual masks
  are intentionally retained, to avoid exposing other unrecognized content.
  Erase may remove those manually if appropriate.
- Exports remain flattened and use the existing review/verification pipeline.
  The optional OCR-warning export override remains off by default.
- This remains experimental software, not a certified redaction tool.

## v0.9.0 — Zoom, instant export, and import from empty preview

- Launcher icon artwork is slightly zoomed out (5dp inset on each side).
  The uploaded icon image itself remains unchanged.
- The manual **Review / Reviewed** button is removed. PNG/PDF buttons are
  available once a document is imported, with no acknowledgement step.
- The final OCR verification still runs. Its existing optional **Allow export
  with OCR warnings** setting continues to control whether unexpected text
  blocks export. The new exports are still flattened and generated locally.
- Pinch the document with **two fingers** to zoom between 1× and 6×.
  Move **two fingers** together to pan horizontally or vertically at zoom.
  Single-finger taps/scribbles remain tied to the selected Redact, Erase,
  or Whitelist tool. Multi-touch gestures cannot select or change masks.
- The full blank preview is now a tap-to-import target; once a document is
  open, use **New file** in the page controls to replace it.
- Removing required review makes it easier to accidentally share OCR misses;
  manually inspect sensitive documents and the exported file before sharing.

## v0.9.1 — Dark Android navigation bar

- Matches the navigation bar and gesture area to Redactor's dark background
  (`#101417`), with light navigation icons/buttons.
- Uses AndroidX edge-to-edge handling so modern Android devices draw the same
  dark Compose surface behind transparent gesture navigation.
- Disables the optional pale navigation-bar contrast scrim on Android 10+.
- Adds a dark native launch/window theme so the navigation bar does not flash
  white before Compose appears.
- The change is visual only: OCR, presets, tools, and secure export are unchanged.
- Actual appearance may vary slightly by phone manufacturer, system navigation
  mode, and device-wide contrast/accessibility settings.
