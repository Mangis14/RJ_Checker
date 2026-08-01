<#
.SYNOPSIS
  Postavi APK v Docker kontejneri - na hostovi netreba Android SDK.

.EXAMPLE
  .\docker\build.ps1                  # debug APK
  .\docker\build.ps1 -Task :core:test # len testy
  .\docker\build.ps1 -Rebuild         # znovu postavi obraz
#>
param(
    [string]$Task = ":app:assembleDebug",
    [switch]$Rebuild,
    # Po uspesnom builde nahra APK do Google Drive (potrebuje nakonfigurovany
    # rclone a RJSEAT_DRIVE_FOLDER - viz README).
    [switch]$Upload
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$image = 'rjseat-android'

# `docker images -q` vracia prazdny stdout, ked obraz nie je. Zamerne nie
# `docker image inspect` - ten pise na stderr a PowerShell 5.1 to pri
# $ErrorActionPreference='Stop' vyhodnoti ako chybu a zhodi cely skript.
$exists = docker images -q $image
if ($Rebuild -or [string]::IsNullOrWhiteSpace($exists)) {
    Write-Host "Stavim obraz $image (prvykrat to chvilu trva)..." -ForegroundColor Cyan
    docker build -t $image "$repo\docker"
    if ($LASTEXITCODE -ne 0) { throw "docker build zlyhal" }
}

# Gradle cache v pojmenovanom volume - bez toho by kazdy build stahoval
# zavislosti odznova.
docker volume create rjseat-gradle-cache | Out-Null

Write-Host "Spustam: gradlew $Task" -ForegroundColor Cyan
docker run --rm `
    -v "${repo}:/workspace" `
    -v rjseat-gradle-cache:/gradle-cache `
    $image `
    sh ./gradlew $Task.Split(' ') --no-daemon --console=plain

$buildExit = $LASTEXITCODE
if ($buildExit -eq 0 -and $Upload) {
    & "$PSScriptRoot\upload-apk.ps1"
}
exit $buildExit
