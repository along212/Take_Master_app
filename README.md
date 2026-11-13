# Take Master App

Multi-take drum cover aligner, interactive comping timeline, and hardware-accelerated stitcher for **Samsung Galaxy S26 Ultra** + **Roland VAD706**.

## Project Structure
- `android-app/` — Native Android app (**Kotlin + Jetpack Compose + AndroidX Media3**) for running 100% locally on Galaxy S26 Ultra.
- `web-prototype/` — Standalone browser-local Comping Studio (`drum_cover_comping_studio.html`).
- `.github/workflows/build-apk.yml` — Cloud CI workflow that builds `app-debug.apk` automatically on every push to GitHub (no Android Studio required).
- `align_and_stitch_m2.py` — Companion macOS Apple Silicon M2 (`hevc_videotoolbox`) CLI aligner & stitcher.

## Building the `.apk` via GitHub Actions (No Android Studio Needed)
1. Create a new repository on GitHub named `take-master-app`.
2. Push this folder to GitHub:
   ```bash
   git remote add origin https://github.com/<your-username>/take-master-app.git
   git push -u origin main
   ```
3. Open the **Actions** tab on GitHub → click the latest **Build Take Master App APK** run → download **`TakeMasterApp-S26Ultra-debug`** and install it on your Galaxy S26 Ultra.
