# Builds home-server\dist\Gallery-Server-Setup.exe: the tray app + Node + ffmpeg + the Gallery server, in one installer.
# Downloads are cached in home-server\cache, so later builds are quick.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$server = Join-Path (Split-Path -Parent $here) 'server'
$cache = Join-Path $here 'cache'
$stage = Join-Path $here 'stage'
$dist = Join-Path $here 'dist'

$nodeVersion = 'v24.20.0'
$ffmpegZip = 'ffmpeg-n9.0-latest-win64-lgpl-shared-9.0.zip'   # needs 7.1+ for iPhone HEIC photos

function Get-Cached($url, $name) {
    $file = Join-Path $cache $name
    if (-not (Test-Path $file)) {
        Write-Host "Downloading $name"
        Invoke-WebRequest $url -OutFile "$file.part"
        Move-Item "$file.part" $file
    }
    $file
}
function Expand-Fresh($zip, $name) {
    $dir = Join-Path $cache $name
    if (-not (Test-Path $dir)) { Expand-Archive $zip "$dir.part"; Move-Item "$dir.part" $dir }
    $dir
}

New-Item -ItemType Directory -Force $cache, $dist | Out-Null
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force "$stage\node", "$stage\ffmpeg", "$stage\app\geo" | Out-Null

# Node
$nodeDir = Expand-Fresh (Get-Cached "https://nodejs.org/dist/$nodeVersion/node-$nodeVersion-win-x64.zip" "node-$nodeVersion.zip") "node-$nodeVersion"
Copy-Item "$nodeDir\node-$nodeVersion-win-x64\node.exe", "$nodeDir\node-$nodeVersion-win-x64\LICENSE" "$stage\node"

# ffmpeg (LGPL build, shared DLLs so ffmpeg and ffprobe don't each carry a copy)
$ffDir = Expand-Fresh (Get-Cached "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/$ffmpegZip" $ffmpegZip) ($ffmpegZip -replace '\.zip$', '')
$ffBin = Get-ChildItem $ffDir -Recurse -Filter ffmpeg.exe | Select-Object -First 1 | ForEach-Object DirectoryName
Copy-Item "$ffBin\ffmpeg.exe", "$ffBin\ffprobe.exe", "$ffBin\*.dll" "$stage\ffmpeg"
Get-ChildItem $ffDir -Recurse -Filter LICENSE.txt | Select-Object -First 1 | Copy-Item -Destination "$stage\ffmpeg\LICENSE.txt"

# City names for "where was this taken" (GeoNames, CC-BY 4.0), same files as the Dockerfile
$cities = Expand-Fresh (Get-Cached 'https://download.geonames.org/export/dump/cities15000.zip' 'cities15000.zip') 'cities15000'
Copy-Item "$cities\cities15000.txt" "$stage\app\geo"
Copy-Item (Get-Cached 'https://download.geonames.org/export/dump/admin1CodesASCII.txt' 'admin1CodesASCII.txt') "$stage\app\geo"
Copy-Item (Get-Cached 'https://download.geonames.org/export/dump/countryInfo.txt' 'countryInfo.txt') "$stage\app\geo"

# The server. The phone app APK isn't included: home servers get the app from the store.
Copy-Item -Recurse "$server\src" "$stage\app\src"
Copy-Item -Recurse "$server\public" "$stage\app\public"
Remove-Item -Force -ErrorAction SilentlyContinue "$stage\app\public\PhotoVault.apk", "$stage\app\public\app-version.json"
Copy-Item "$server\package.json" "$stage\app"
if (Test-Path "$server\package-lock.json") { Copy-Item "$server\package-lock.json" "$stage\app" }
Push-Location "$stage\app"
try {
    & npm install --omit=dev --no-audit --no-fund --loglevel=error
    if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
} finally { Pop-Location }

# The tray app
& dotnet build "$here\GalleryServer.csproj" -c Release -o "$here\bin\out" --nologo -v q
if ($LASTEXITCODE -ne 0) { throw 'The tray app build failed.' }
Copy-Item "$here\bin\out\Gallery Server.exe", "$here\bin\out\Gallery Server.exe.config", "$here\bin\out\QRCoder.dll" $stage

# Access from anywhere (Tailscale Funnel, Go + tsnet). Needs Go: winget install GoLang.Go, or ~\tools\go.
$go = "$env:USERPROFILE\tools\go\bin\go.exe"
if (-not (Test-Path $go)) { $go = (Get-Command go -ErrorAction SilentlyContinue).Source }
if (-not $go) { throw 'Go not found (needed for gallery-tunnel.exe).' }
Push-Location "$here\tunnel"
try {
    $env:GOOS = 'windows'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'
    & $go build -trimpath -ldflags='-s -w' -o "$stage\gallery-tunnel.exe" .
    if ($LASTEXITCODE -ne 0) { throw 'The tunnel build failed.' }
} finally { Pop-Location; Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue }

# Installer (NSIS from electron-builder's cache, or a normal NSIS install)
$makensis = @(
    (Get-ChildItem "$env:LOCALAPPDATA\electron-builder\Cache\nsis" -Recurse -Filter makensis.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName),
    "${env:ProgramFiles(x86)}\NSIS\makensis.exe", "$env:ProgramFiles\NSIS\makensis.exe"
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $makensis) { throw 'makensis.exe not found. Install NSIS (winget install NSIS.NSIS).' }
$version = ([xml](Get-Content "$here\GalleryServer.csproj")).Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1
& $makensis /V2 "/DVERSION=$version" "/DSTAGE=$stage" "/DOUT=$dist\Gallery-Server-Setup.exe" "$here\installer.nsi"
if ($LASTEXITCODE -ne 0) { throw 'The installer build failed.' }
Write-Host ("Saved $dist\Gallery-Server-Setup.exe ({0:0} MB)" -f ((Get-Item "$dist\Gallery-Server-Setup.exe").Length / 1MB))
