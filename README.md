# Gallery (PhotoVault)

**Download the Android app: [Gallery.apk](https://github.com/rodriguezcurielj/PhotoVault/releases/latest/download/Gallery.apk)** — open it on your phone and tap Install.
**Windows uploader: [Gallery-Windows.exe](https://github.com/rodriguezcurielj/Pepes-Apps/releases/download/downloads/Gallery-Windows.exe)** — drop folders on it to upload thousands of photos/videos (`windows/`, build with `windows/build.ps1`).

Self-hosted photo library: a Node server (Docker) that stores the photos, a web app, and an Android app.

- Website: https://rodriguezcurielj.github.io/PhotoVault/ (also served by the server itself)
- Server: https://budget-pc.tail05789d.ts.net:8443 (public through Tailscale Funnel; username + password login)
- Android app: [latest release](https://github.com/rodriguezcurielj/PhotoVault/releases/latest) (also https://budget-pc.tail05789d.ts.net:8443/PhotoVault.apk)

## Layout
- `server/` – Express + SQLite server; `server/public/` is the web app (published to GitHub Pages by `.github/workflows/pages.yml`).
- `android/` – Kotlin / Jetpack Compose app (`android/build-apk.ps1`).
- `docker-compose.yml` – runs the server on port 8420.
- `home-server/` – **Gallery Server** for Windows: anyone's PC becomes their own Gallery server, no Docker. A tray app runs Node + ffmpeg + `server/`, asks where to keep the photos, and shows a QR code / address for the phone. `home-server/build.ps1` → `home-server/dist/Gallery-Server-Setup.exe`.

Set or change the login on the server:
`docker exec -e PV_USER=name -e PV_PASS=secret photovault node src/set-login.js`
