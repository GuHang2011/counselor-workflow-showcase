param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$fixtureRoot = Join-Path $projectRoot ('.cache/package-check-' + [guid]::NewGuid().ToString('N'))
$checks = New-Object 'System.Collections.Generic.List[object]'
function Write-Utf8([string]$Path, [string]$Value) {
    [IO.File]::WriteAllText($Path,$Value,(New-Object Text.UTF8Encoding($false)))
}
function Sha([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Pass([string]$Name) { $checks.Add(@{name=$Name;status='passed'}); Write-Output "PASS: $Name" }
function Reject([string]$Name, [string]$Fragment, [scriptblock]$Action) {
    $caught=$false
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -notlike "*$Fragment*") { throw }
        $caught=$true
    }
    if (-not $caught) { throw "Expected rejection: $Name" }
    Pass $Name
}
foreach ($relative in @('deploy/package-release.ps1','tests/package_release.ps1')) {
    $tokens=$null; $parseErrors=$null
    [Management.Automation.Language.Parser]::ParseFile((Join-Path $projectRoot $relative),[ref]$tokens,[ref]$parseErrors) | Out-Null
    if ($parseErrors.Count) { throw "Script parse failed: $relative" }
    Pass "PowerShell syntax: $relative"
}
foreach ($relative in @('deploy/linux','deploy/mysql','docs','.cache','CropDiseasesDetection_springboot/target','CropDiseasesDetection_vue/dist/assets','data','uploads')) {
    New-Item -ItemType Directory -Path (Join-Path $fixtureRoot $relative) -Force | Out-Null
}
$runner = Join-Path $fixtureRoot 'deploy/package-release.ps1'
Copy-Item -LiteralPath (Join-Path $projectRoot 'deploy/package-release.ps1') -Destination $runner
foreach ($name in @('erp.env.example','counselor-erp.service','nginx.conf.example','baota-site-location.conf.example')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('deploy/linux/' + $name)) -Destination (Join-Path $fixtureRoot 'deploy/linux')
}
Write-Utf8 (Join-Path $fixtureRoot 'deploy/mysql/001_schema_mysql8.sql') '-- test fixture'
Write-Utf8 (Join-Path $fixtureRoot 'deploy/README.md') 'Deployment fixture'
Write-Utf8 (Join-Path $fixtureRoot 'docs/notes.md') 'Current release notes fixture'
Write-Utf8 (Join-Path $fixtureRoot 'docs/部署教程.md') 'Guide fixture'
Write-Utf8 (Join-Path $fixtureRoot 'docs/宝塔面板部署指南.md') 'Baota guide fixture'
Write-Utf8 (Join-Path $fixtureRoot 'docs/备份恢复与运行手册.md') 'Backup fixture'
Write-Utf8 (Join-Path $fixtureRoot 'data/secret.db') 'must never enter a release'
Write-Utf8 (Join-Path $fixtureRoot 'uploads/private.txt') 'must never enter a release'
$jarPath = Join-Path $fixtureRoot 'CropDiseasesDetection_springboot/target/counselor-erp-1.0.0.jar'
$htmlPath = Join-Path $fixtureRoot 'CropDiseasesDetection_vue/dist/index.html'
$jsPath = Join-Path $fixtureRoot 'CropDiseasesDetection_vue/dist/assets/main.js'
$browserPath = Join-Path $fixtureRoot '.cache/browser-results.json'
$acceptancePath = Join-Path $fixtureRoot '.cache/acceptance-result.json'
Write-Utf8 $jarPath 'isolated jar fixture, not executable'
Write-Utf8 $htmlPath '<html>fixture</html>'
Write-Utf8 $jsPath 'fixture();'
$browser=@{stats=@{expected=3;unexpected=0;flaky=0;skipped=0};errors=@();suites=@();privateTestValue='must not enter a release'}
Write-Utf8 $browserPath ($browser | ConvertTo-Json -Depth 6)
$digestLines = (Sha $jsPath) + '  assets/main.js' + "`n" + (Sha $htmlPath) + '  index.html' + "`n"
$algorithm=[Security.Cryptography.SHA256]::Create()
try { $frontendSha=([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($digestLines)))).Replace('-','').ToLowerInvariant() } finally { $algorithm.Dispose() }
$acceptance=@{status='passed';browser='passed';httpSmoke='passed';chatSmoke='passed';restart='passed';jarSha256=(Sha $jarPath);frontendSha256=$frontendSha;browserReportSha256=(Sha $browserPath);finishedAt=[DateTime]::UtcNow.ToString('o')}
function Save-Acceptance { Write-Utf8 $acceptancePath ($acceptance | ConvertTo-Json -Depth 5) }
Save-Acceptance
Reject 'Unsafe version path is rejected' 'Version' { & $runner -Version '../escape' -ReleaseNotes 'docs/notes.md' }
& $runner -Version fixture -ReleaseNotes 'docs/notes.md' | Out-Null
$release = Join-Path $fixtureRoot 'deploy/release-fixture'
$zipPath = $release + '.zip'
if (-not (Test-Path -LiteralPath $zipPath)) { throw 'Passing evidence did not produce a ZIP' }
if ((Get-Content -LiteralPath ($zipPath + '.sha256') -Raw).Trim() -ne ((Sha $zipPath) + '  release-fixture.zip')) { throw 'ZIP digest mismatch' }
foreach ($line in [IO.File]::ReadAllLines((Join-Path $release 'SHA256SUMS'))) {
    $parts=$line -split '  ',2
    if ((Sha (Join-Path $release $parts[1])) -ne $parts[0]) { throw "Manifest hash mismatch: $($parts[1])" }
}
Pass 'Passing evidence produces ZIP and verifiable file/ZIP digests'
$manifest=Get-Content -LiteralPath (Join-Path $release 'release-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.version -ne 'fixture' -or $manifest.releaseId -ne 'fixture' -or $manifest.jarSha256 -ne (Sha $jarPath) -or $manifest.frontendSha256 -ne $frontendSha -or (Get-Content -LiteralPath (Join-Path $release 'VERSION') -Raw).Trim() -ne 'fixture') { throw 'Release version or accepted artifact identity mismatch' }
Pass 'Release metadata identifies the version and accepted artifacts'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $entryPaths=@($archive.Entries | ForEach-Object { $_.FullName.Replace('\','/') })
    foreach ($required in @('backend/counselor-erp-1.0.0.jar','web/index.html','web/assets/main.js','config/linux/erp.env.example','config/linux/baota-site-location.conf.example','SHA256SUMS','RELEASE.md','DEPLOY.md','DEPLOY-GUIDE.md','DEPLOY-BAOTA.md','BACKUP-RESTORE.md')) {
        if (('release-fixture/' + $required) -notin $entryPaths) { throw "ZIP is missing a required file: $required" }
    }
    $expectedFiles=@(Get-ChildItem -LiteralPath $release -Recurse -File).Count
    $zipFiles=@($archive.Entries | Where-Object { $_.Name }).Count
    if ($expectedFiles -ne $zipFiles) { throw 'ZIP file count differs from release directory' }
} finally { $archive.Dispose() }
Pass 'ZIP contains the verified release files and deployment guides'
if (@(Get-ChildItem -LiteralPath $release -Recurse -File | Where-Object { $_.Name -in @('secret.db','private.txt','browser-results.json') }).Count) { throw 'Sensitive fixture was packaged' }
$evidence=Get-Content -LiteralPath (Join-Path $release 'evidence/acceptance-summary.json') -Raw
if ($evidence.Contains('must not enter a release') -or $evidence.Contains($fixtureRoot)) { throw 'Raw fixture evidence leaked' }
Pass 'Package excludes local data and raw browser evidence'
$before=Sha $zipPath
Reject 'Existing release is never overwritten' 'already exists' { & $runner -Version fixture -ReleaseNotes 'docs/notes.md' }
if ((Sha $zipPath) -ne $before) { throw 'Existing ZIP changed' }
Write-Utf8 (Join-Path $fixtureRoot 'deploy/release-stale-zip.zip') 'keep existing zip'
Reject 'Existing ZIP without its directory is protected' 'already exists' { & $runner -Version stale-zip -ReleaseNotes 'docs/notes.md' }
Write-Utf8 (Join-Path $fixtureRoot 'deploy/release-stale-checksum.zip.sha256') 'keep existing checksum'
Reject 'Existing checksum without its ZIP is protected' 'already exists' { & $runner -Version stale-checksum -ReleaseNotes 'docs/notes.md' }
Write-Utf8 $htmlPath '<html>changed after acceptance</html>'
Reject 'Changed production frontend is rejected' 'frontend changed' { & $runner -Version changed-web -ReleaseNotes 'docs/notes.md' }
Write-Utf8 $htmlPath '<html>fixture</html>'
Write-Utf8 $jarPath 'changed jar fixture'
Reject 'Changed backend is rejected' 'Backend changed' { & $runner -Version changed-jar -ReleaseNotes 'docs/notes.md' }
Write-Utf8 $jarPath 'isolated jar fixture, not executable'
$browser.stats.unexpected=1
Write-Utf8 $browserPath ($browser | ConvertTo-Json -Depth 6)
Reject 'Changed browser report is rejected' 'Browser evidence changed' { & $runner -Version changed-browser -ReleaseNotes 'docs/notes.md' }
$acceptance.browserReportSha256=Sha $browserPath
Save-Acceptance
Reject 'Failed browser tests block packaging' 'Browser report must' { & $runner -Version failed-browser -ReleaseNotes 'docs/notes.md' }
$browser.stats.unexpected=0; $browser.stats.skipped=1
Write-Utf8 $browserPath ($browser | ConvertTo-Json -Depth 6)
$acceptance.browserReportSha256=Sha $browserPath
Save-Acceptance
Reject 'Skipped browser tests block packaging' 'Browser report must' { & $runner -Version skipped-browser -ReleaseNotes 'docs/notes.md' }
$browser.stats.skipped=0
Write-Utf8 $browserPath ($browser | ConvertTo-Json -Depth 6)
$acceptance.browserReportSha256=Sha $browserPath
$acceptance.restart='not-run'
Save-Acceptance
Reject 'Incomplete persistence verification blocks packaging' 'Required acceptance check' { & $runner -Version missing-restart -ReleaseNotes 'docs/notes.md' }
$acceptance.restart='passed'
Save-Acceptance
$templatePath=Join-Path $fixtureRoot 'deploy/linux/erp.env.example'
$originalTemplate=[IO.File]::ReadAllText($templatePath)
Write-Utf8 $templatePath ($originalTemplate + "`nERP_AI_API_KEY=fixture-secret`n")
Reject 'Secrets in config template block packaging' 'must not contain passwords' { & $runner -Version unsafe-template -ReleaseNotes 'docs/notes.md' }
Write-Utf8 $templatePath $originalTemplate
Write-Utf8 (Join-Path $fixtureRoot 'CropDiseasesDetection_vue/dist/.env') 'fixture'
Reject 'Private files in frontend output block packaging' 'Private data or configuration' { & $runner -Version private-web -ReleaseNotes 'docs/notes.md' }
if (@(Get-ChildItem -LiteralPath (Join-Path $fixtureRoot 'deploy') -Directory -Filter 'release-*').Count -ne 1) { throw 'Rejected input created a release directory' }
Pass 'All rejected inputs leave release outputs untouched'
$report=@{status='passed';checkedAt=[DateTime]::UtcNow.ToString('o');checkCount=$checks.Count;checks=@($checks.ToArray());limitations=@('Uses isolated tiny fixtures; does not execute Java, Nginx or MySQL.')}
Write-Utf8 (Join-Path $projectRoot '.cache/package-release-checks.json') ($report | ConvertTo-Json -Depth 6)
