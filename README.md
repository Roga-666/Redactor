# Redactor (Android) — v0.1.0

A **100% on-device, offline Android** image/PDF whitelist-redaction prototype. No DOC/DOCX support.

> **Security status: early prototype, not certified for sensitive production use.** The app replaces pixels in a newly created PNG or image-only PDF. OCR may miss text entirely. Review carefully and manually cover anything missed. Even a second OCR check cannot guarantee safe release.

## Features
- Import JPEG, PNG, WebP and PDFs through Android document picker.
- Bundled on-device ML Kit Latin OCR; no first-run OCR model download.
- Case-insensitive exact whole-word whitelist; non-whitelisted or low-confidence recognized words are masked.
- Review sanitized previews and inspect original; draw manual cover boxes, undo/clear boxes.
- Explicit review acknowledgment on each page.
- Export current page to fresh PNG or all pages to image-only PDF, without copied original text layers or EXIF.
- Re-OCR final pixels and block export if it detects unapproved words.
- Screenshot blocking, backup disabled, no INTERNET permission.
- GitHub Actions compiles a debug APK.

## Installation and build
Open the project in Android Studio with JDK 17 and SDK 35. Build with Gradle 8.11.1.
Alternatively open **Actions → Android APK → latest successful run → Artifacts → Redactor-debug-apk** to download the artifact ZIP containing app-debug.apk. It is a debug build for testing, not a security-certified release.
CI runs the system-installed Gradle executable; the project does not include Gradle wrapper JAR.

## Important security limitations
- Every export requires exhaustive visual review because both initial and verification OCR can miss the same text (especially tiny, handwritten, rotated or faint glyphs).
- The exported file contains newly rasterized pixels, not the original text layer; recovered pixels underneath black masks are not present in the exported file.
- Context can still reveal what a removed word might have been.
- Supports up to 12 PDF pages / 36 million cumulative rendered pixels. PNG export is one page.
- The source file, backups, and third-party Android document provider remain outside this app's control. Select local documents; some providers ignore the local-only hint.
- The app's internet permission is absent, but Android's document picker may expose cloud providers.
- No Word support; English/Latin text only. No reliable handwriting support.
- No assurance of regulatory compliance. Do not use for high-stakes sensitive releases until externally audited.

## Source map
- Whitelist.kt: matching rules
- DocumentImporter.kt: local image decoding and PDF rasterization
- LocalOcr.kt: bundled recognition and final verification
- SecureExporter.kt: pixel replacement and fresh PNG/PDF
- MainActivity.kt: UI, page inspection and file import/export
- DocumentModels.kt: word regions and redaction boxes

## License
No open-source license has been selected; public repository visibility does not automatically grant redistribution rights.
