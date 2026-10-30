param([string]$WebDirectory = 'CropDiseasesDetection_vue/dist')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& python (Join-Path $projectRoot 'scripts/check_version.py')
if ($LASTEXITCODE -ne 0) { throw 'Release version validation failed.' }
$productVersion = (Get-Content -LiteralPath (Join-Path $projectRoot 'VERSION') -Raw -Encoding UTF8).Trim()
& (Join-Path $PSScriptRoot 'package-release.ps1') -Version ('v' + $productVersion) -WebDirectory $WebDirectory -ReleaseNotes ('docs/releases/v' + $productVersion + '.md')
