<#
.SYNOPSIS
  Nahra postavene APK do Google Drive cez rclone.

.DESCRIPTION
  Rclone sa pouziva zamerne namiesto service accountu: service account ma nulovu
  uloznu kvotu, takze nahravanie do zlozky v osobnom Disku zlyha na
  "storageQuotaExceeded". Rclone sa autorizuje tvojim vlastnym uctom, takze
  funguje pre osobny Disk aj pre zdielany.

  Prihlasovacie udaje nikdy neprechadzaju cez tento skript - rclone si ich drzi
  vo svojej konfiguracii, ktoru si vytvoris sam cez `rclone config`.

.PARAMETER FolderId
  ID cielovej zlozky na Disku. Zamerne sa necita z repozitara - viz README.
  Da sa zadat aj premennou prostredia RJSEAT_DRIVE_FOLDER.

.EXAMPLE
  .\docker\upload-apk.ps1
  .\docker\upload-apk.ps1 -FolderId 15aeyfd...
#>
param(
    [string]$Remote = $(if ($env:RJSEAT_DRIVE_REMOTE) { $env:RJSEAT_DRIVE_REMOTE } else { 'gdrive' }),
    [string]$FolderId = $env:RJSEAT_DRIVE_FOLDER,
    [string]$Apk
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

if (-not $Apk) { $Apk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk' }
if (-not (Test-Path $Apk)) { throw "APK sa nenaslo: $Apk  (najprv .\docker\build.ps1)" }

if (-not (Get-Command rclone -ErrorAction SilentlyContinue)) {
    Write-Host "rclone nie je nainstalovany - preskakujem nahravanie." -ForegroundColor Yellow
    Write-Host "Nastavenie: winget install Rclone.Rclone; potom rclone config" -ForegroundColor Yellow
    exit 0
}
if (-not $FolderId) {
    Write-Host "Nie je zadane ID zlozky (RJSEAT_DRIVE_FOLDER) - preskakujem." -ForegroundColor Yellow
    exit 0
}

# Nazov s datumom a commitom, aby sa buildy v zlozke hromadili a dalo sa spatne
# zistit, z coho ktore APK vzniklo.
$stamp = Get-Date -Format 'yyyy-MM-dd-HHmm'
$sha = (git -C $repo rev-parse --short HEAD 2>$null)
if (-not $sha) { $sha = 'nogit' }
$name = "rjseat-$stamp-$sha.apk"

Write-Host "Nahravam $name do Drive..." -ForegroundColor Cyan
rclone copyto $Apk "${Remote}:$name" --drive-root-folder-id $FolderId --progress
if ($LASTEXITCODE -ne 0) { throw "rclone zlyhal (exit $LASTEXITCODE)" }
Write-Host "Hotovo: $name" -ForegroundColor Green
