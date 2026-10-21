# Builds the PhotoVault Android app and copies it to ..\PhotoVault.apk
# Run from anywhere:  powershell -ExecutionPolicy Bypass -File android\build-apk.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# First run: make a release signing key so later versions install over earlier ones. Keep these two files safe.
if (-not (Test-Path "$here\keystore.properties")) {
    $pass = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    & keytool -genkeypair -v -keystore "$here\photovault-release.jks" -alias photovault -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $pass -keypass $pass -dname "CN=PhotoVault" | Out-Null
    @("storeFile=photovault-release.jks", "storePassword=$pass", "keyAlias=photovault", "keyPassword=$pass") | Set-Content -Encoding ascii "$here\keystore.properties"
    Write-Host 'Created a release key (android\photovault-release.jks + keystore.properties). Back them up.'
}

# The built-in Tailscale link (tsproxy, Go) for phones (arm64). Needs Go: winget install GoLang.Go, or ~\tools\go.
$go = (Get-Command go -ErrorAction SilentlyContinue).Source
if (-not $go) { $go = "$env:USERPROFILE\tools\go\bin\go.exe" }
$libs = "$here\app\src\main\jniLibs"
Remove-Item "$libs\x86_64" -Recurse -Force -ErrorAction SilentlyContinue   # emulator-only test build
New-Item -ItemType Directory -Force "$libs\arm64-v8a" | Out-Null
Push-Location "$here\tsproxy"
try {
    $env:GOOS = 'android'; $env:GOARCH = 'arm64'; $env:CGO_ENABLED = '0'
    & $go build -trimpath -ldflags='-s -w' -o "$libs\arm64-v8a\libtsproxy.so" .
    if ($LASTEXITCODE -ne 0) { throw 'Building tsproxy failed.' }
} finally { Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue; Pop-Location }

Push-Location $here
try {
    & .\gradlew.bat assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'The Android build failed (see the messages above).' }
} finally { Pop-Location }

$apk = Join-Path $here 'app\build\outputs\apk\release\app-release.apk'
$out = Join-Path (Split-Path -Parent $here) 'PhotoVault.apk'
Copy-Item $apk $out -Force
# The server hands this APK to the app's "Update app" button, with its version number next to it.
$public = Join-Path (Split-Path -Parent $here) 'server\public'
Copy-Item $apk (Join-Path $public 'PhotoVault.apk') -Force
$gradle = Get-Content "$here\app\build.gradle" -Raw
$code = [int]([regex]::Match($gradle, 'versionCode (\d+)').Groups[1].Value)
$name = [regex]::Match($gradle, "versionName '([^']+)'").Groups[1].Value
@{ versionCode = $code; versionName = $name } | ConvertTo-Json -Compress | Set-Content -Encoding ascii (Join-Path $public 'app-version.json')
# Also put it on Pepe's Apps (the download page + store app) so phones see the update there.
$pepes = Join-Path $env:USERPROFILE 'PepesApps\publish.ps1'
if (Test-Path $pepes) { & powershell -ExecutionPolicy Bypass -File $pepes Gallery.apk }
Write-Host "Saved $out ($([math]::Round((Get-Item $out).Length / 1MB, 1)) MB)."
