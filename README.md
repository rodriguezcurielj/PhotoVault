# Gallery (PhotoVault)

**Download the Android app: [Gallery.apk](https://github.com/rodriguezcurielj/PhotoVault/releases/latest/download/Gallery.apk)** — open it on your phone and tap Install.

Self-hosted photo library: a Node server (Docker) that stores the photos, a web app, and an Android app.

- Website: https://rodriguezcurielj.github.io/PhotoVault/ (also served by the server itself)
- Server: https://budget-pc.tail05789d.ts.net:8443 (public through Tailscale Funnel; username + password login)
- Android app: [latest release](https://github.com/rodriguezcurielj/PhotoVault/releases/latest) (also https://budget-pc.tail05789d.ts.net:8443/PhotoVault.apk)

## Layout
- `server/` – Express + SQLite server; `server/public/` is the web app (published to GitHub Pages by `.github/workflows/pages.yml`).
- `android/` – Kotlin / Jetpack Compose app (`android/build-apk.ps1`).
- `docker-compose.yml` – runs the server on port 8420.

Set or change the login on the server:
`docker exec -e PV_USER=name -e PV_PASS=secret photovault node src/set-login.js`
