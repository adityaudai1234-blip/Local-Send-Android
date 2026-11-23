# LocalDrop Release & Deployment Guide

**Application ID:** `com.aistudio.localdrop.wzkp`  
**Version:** `2.2.0` (`versionCode = 7`)  
**Min SDK:** `24` (Android 7.0) | **Target / Compile SDK:** `36`

---

## 1. Pre-Release Checklist

1. **Verify Version & Metadata Sync**:
   - `app/build.gradle.kts`: `versionCode` and `versionName` match the target release (`7` / `"2.2.0"`).
   - `app/src/main/res/values/strings.xml` and `metadata.json` app names match (`LocalDrop`).
   - `ReleaseNotesCatalog` in `app/src/main/java/com/example/domain/model/ReleaseNotes.kt` contains accurate notes for the target version.
2. **Security & Permissions Audit**:
   - Confirm `AndroidManifest.xml` has no broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `MANAGE_EXTERNAL_STORAGE`).
   - Confirm `network_security_config.xml` has `cleartextTrafficPermitted="false"`.
   - Confirm zero hardcoded secrets or keystore passwords in source files or Gradle scripts.
3. **Run Automated Verification**:
   ```bash
   gradle :app:testDebugUnitTest
   gradle :app:compileReleaseKotlin
   gradle :app:lintVitalAnalyzeRelease
   ```

---

## 2. Signing Configuration

`app/build.gradle.kts` reads upload signing credentials strictly from environment variables (never hardcoded):

| Environment Variable | Purpose |
|---|---|
| `KEYSTORE_PATH` | Path to `.jks` / `.keystore` upload keystore (default: `${rootDir}/my-upload-key.jks`) |
| `STORE_PASSWORD` | Upload keystore password |
| `KEY_PASSWORD` | Upload key alias (`upload`) password |

### Generating an Upload Keystore (First-Time Setup)
```bash
keytool -genkey -v -keystore my-upload-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```
> Keep `my-upload-key.jks` and passwords strictly outside version control. Store CI/CD signing credentials in your secrets manager.

---

## 3. Generating Release Artifacts

Once `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` are exported:

```bash
# Signed Release APK (sideload / direct distribution)
gradle :app:assembleRelease

# Signed Android App Bundle (Google Play Console upload)
gradle :app:bundleRelease
```

Expected output paths:
- Release APK: `app/build/outputs/apk/release/app-release.apk`
- Release AAB: `app/build/outputs/bundle/release/app-release.aab`

For store release optimization, `app/proguard-rules.pro` preserves Room entities/DAOs, protocol & error models, Glance widgets, AndroidX SplashScreen, ZXing, and Coroutines. Enable `isMinifyEnabled = true` and `isShrinkResources = true` in the `release` buildType after testing minified release builds on physical Android hardware.

---

## 4. Physical Device Pre-Rollout Validation

Before publishing to production tracks, verify manually on at least two physical devices and a Tauri desktop companion:
- First-time onboarding completion and fresh SplashScreen transition (light & dark mode).
- Contextual Camera, Notification, and Android 13+ Nearby Wi-Fi permission prompts + Settings recovery when denied.
- Bidirectional file & nested folder transfers between Android ↔ Tauri Desktop (`Windows` / `macOS` / `Linux`).
- Background transfer survival with screen off via `FileTransferService`.
- App upgrade over previous `versionCode` to verify Room migration and one-time What's New dialog.
