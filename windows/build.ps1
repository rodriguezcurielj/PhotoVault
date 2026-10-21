# Builds the Windows app into windows\dist\Gallery.exe (one file, no .NET install needed) and puts it on Pepe's Apps.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $here
try {
    & dotnet publish -c Release -o dist
    if ($LASTEXITCODE -ne 0) { throw 'The Windows build failed.' }
} finally { Pop-Location }
$pepes = Join-Path $env:USERPROFILE 'PepesApps\publish.ps1'
if (Test-Path $pepes) { & powershell -ExecutionPolicy Bypass -File $pepes Gallery-Windows.exe }
Write-Host "Saved $here\dist\Gallery.exe"
