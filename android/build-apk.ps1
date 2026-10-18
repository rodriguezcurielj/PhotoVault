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

Push-Location $here
try {
    & .\gradlew.bat assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'The Android build failed (see the messages above).' }
} finally { Pop-Location }

$apk = Join-Path $here 'app\build\outputs\apk\release\app-release.apk'
$out = Join-Path (Split-Path -Parent $here) 'PhotoVault.apk'
Copy-Item $apk $out -Force
Write-Host "Saved $out ($([math]::Round((Get-Item $out).Length / 1MB, 1)) MB)."
