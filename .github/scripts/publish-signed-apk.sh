#!/usr/bin/env bash
# Signed Droid-ify release. Never commit a keystore or print its passwords.
set -euo pipefail
for secret in ANDROID_RELEASE_KEYSTORE_BASE64 ANDROID_RELEASE_STORE_PASSWORD ANDROID_RELEASE_KEY_ALIAS ANDROID_RELEASE_KEY_PASSWORD; do
  if [ -z "$(printenv "$secret")" ]; then echo "::error::Missing secret $secret"; exit 1; fi
done
test -s "$APK_INPUT" || { echo "::error::Unsigned release APK not found: $APK_INPUT"; exit 1; }
tools_dir="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
test -x "$tools_dir/apksigner" && test -x "$tools_dir/zipalign" && test -x "$tools_dir/aapt"
keystore="$RUNNER_TEMP/app-store-release.p12"
umask 077
printf '%s' "$ANDROID_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$keystore"
"$tools_dir/zipalign" -p -f 4 "$APK_INPUT" "$RUNNER_TEMP/aligned.apk"
apk="$RUNNER_TEMP/$APP_NAME-droidify-$GITHUB_RUN_NUMBER.apk"
"$tools_dir/apksigner" sign --ks "$keystore" --ks-key-alias "$ANDROID_RELEASE_KEY_ALIAS" --ks-pass env:ANDROID_RELEASE_STORE_PASSWORD --key-pass env:ANDROID_RELEASE_KEY_PASSWORD --out "$apk" "$RUNNER_TEMP/aligned.apk"
rm -f "$keystore"
# Accept verified V2/V3 and legacy signer output, but reject multiple signing IDs.
certificates="$("$tools_dir/apksigner" verify --verbose --print-certs "$apk" | awk '/certificate SHA-256 digest:/ {print tolower($NF)}' | sort -u)"
if ! [[ "$certificates" =~ ^[0-9a-f]{64}$ ]]; then
  echo "::error::Expected exactly one verified signing certificate SHA-256 digest"; exit 1
fi
certificate="$certificates"
badging="$("$tools_dir/aapt" dump badging "$apk")"
package_line="$(printf '%s\n' "$badging" | grep '^package: ' | head -n 1)"
package="$(printf '%s\n' "$package_line" | grep -oP "^package: name='\\K[^']+")"
code="$(printf '%s\n' "$package_line" | grep -oP "versionCode='\\K[0-9]+")"
version="$(printf '%s\n' "$package_line" | grep -oP "versionName='\\K[^']*")"
printf '::notice::Verified APK metadata: package=%s expected=%s versionCode=%s signerDigestLength=%s\\n' "$package" "$EXPECTED_PACKAGE" "$code" "${#certificate}"
test -n "$certificate" && test -n "$code" && test "$package" = "$EXPECTED_PACKAGE" || { echo "::error::Invalid package ID, version, or signer"; exit 1; }
if [ "$DENY_NETWORK" = "true" ] && "$tools_dir/aapt" dump permissions "$apk" | grep -E 'android.permission.(INTERNET|ACCESS_NETWORK_STATE)'; then
  echo "::error::Unexpected network permission in offline-only app"; exit 1
fi
previous="$(gh release list --limit 100 --json tagName --jq '[.[] | select(.tagName | startswith("droidify-"))] | first | .tagName // empty')"
if [ -n "$previous" ]; then
  mkdir -p "$RUNNER_TEMP/prior"
  gh release download "$previous" --pattern droidify-metadata.txt --dir "$RUNNER_TEMP/prior"
  previous_file="$RUNNER_TEMP/prior/droidify-metadata.txt"
  old_package="$(grep '^package=' "$previous_file" | cut -d= -f2-)"
  old_cert="$(grep '^certSha256=' "$previous_file" | cut -d= -f2-)"
  old_code="$(grep '^versionCode=' "$previous_file" | cut -d= -f2-)"
  test "$package" = "$old_package" || { echo "::error::Android package ID changed"; exit 1; }
  test "$certificate" = "$old_cert" || { echo "::error::APK signing certificate changed"; exit 1; }
  if [ "$code" -le "$old_code" ]; then echo "::error::versionCode $code must exceed $old_code"; exit 1; fi
fi
printf 'package=%s\nversionCode=%s\nversionName=%s\ncertSha256=%s\n' "$package" "$code" "$version" "$certificate" > "$RUNNER_TEMP/droidify-metadata.txt"
sha256sum "$apk" > "$apk.sha256"
tag="droidify-$GITHUB_RUN_NUMBER"
gh release create "$tag" "$apk" "$apk.sha256" "$RUNNER_TEMP/droidify-metadata.txt" --target "$GITHUB_SHA" --title "$APP_NAME signed build $GITHUB_RUN_NUMBER" --notes "Signed Android release for Droid-ify. Package: $package; version: $version ($code). Check the signing certificate against your installed APK before the first migration."
