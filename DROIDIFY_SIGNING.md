# Persistent signing and in-place updates

This project has a separate **signed Droid-ify release** workflow. Normal debug workflows remain available for testing; do **not** distribute their APKs as updatable releases.

## One-time setup
1. **First check whether an existing permanent key can be recovered.** If an installed APK was built by a temporary GitHub Actions debug runner and that key is gone, Android cannot install an APK signed by a different key over it. Back up app data before a one-time transition. Never delete an app just to test an update.
2. On your own computer, generate a long-lived key **per app**, e.g. `keytool -genkeypair -keystore android-release.p12 -storetype PKCS12 -alias release -keyalg RSA -keysize 4096 -validity 15000`. Choose a strong password. Save the keystore and passwords in two offline, secure backups. **Never commit or send this key in chat.**
3. Under **GitHub repo → Settings → Secrets and variables → Actions**, create four repository secrets:
   - `ANDROID_RELEASE_KEYSTORE_BASE64`: single-line base64 of the binary `.p12` (Linux: `base64 -w0 android-release.p12`)
   - `ANDROID_RELEASE_STORE_PASSWORD`: keystore password
   - `ANDROID_RELEASE_KEY_ALIAS`: `release` (or your existing alias)
   - `ANDROID_RELEASE_KEY_PASSWORD`: key password
4. Run **Actions → Publish signed Droid-ify APK → Run workflow** on the default branch. The workflow safely skips publication if any secret is missing.

Every published signed build has an Android versionCode of `100000 + GitHub workflow run number`, which increases between workflow runs. It checks that its package name and signing certificate match the preceding `droidify-*` release, and refuses a downgrade.

**Important:** The first signed build is not automatically compatible with older debug APK installations. Compare their SHA-256 certificate fingerprints using `apksigner verify --print-certs existing.apk` and the new release before installing. If the fingerprints differ and the old private key is unavailable, a one-time backup/migration/reinstall may be unavoidable.

In the future, your central App-Store importer should only ingest `droidify-*` releases from this project; ignore old debug releases and workflow artifacts.
