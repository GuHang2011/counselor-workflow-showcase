param(
    [ValidatePattern('^[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*$')]
    [ValidateLength(1,64)]
    [string]$Version = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [string]$WebDirectory = 'frontend/dist',
    [string]$ReleaseNotes = 'docs/上线优化与部署说明_20261018.md'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$releaseRoot = Join-Path $PSScriptRoot ('release-' + $Version)
$zipPath = $releaseRoot + '.zip'
if ((Test-Path -LiteralPath $releaseRoot) -or (Test-Path -LiteralPath $zipPath) -or (Test-Path -LiteralPath ($zipPath + '.sha256'))) {
    throw 'Release output already exists; choose a new version. Existing releases are never merged or overwritten.'
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-WebDigest([string]$Directory) {
    if (-not (Test-Path -LiteralPath (Join-Path $Directory 'index.html') -PathType Leaf)) {
        throw 'Production web build is missing index.html. Build the frontend before acceptance.'
    }
    if ((Get-Item -LiteralPath $Directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Production web build must not contain links or junctions.'
    }
    $entries = @(Get-ChildItem -LiteralPath $Directory -Recurse -Force)
    if (@($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) {
        throw 'Production web build must not contain links or junctions.'
    }
    $files = @($entries | Where-Object { -not $_.PSIsContainer })
    [string[]]$paths = @($files | ForEach-Object { $_.FullName.Substring($Directory.Length + 1).Replace('\','/') })
    [Array]::Sort($paths, [StringComparer]::Ordinal)
    $lines = foreach ($relative in $paths) {
        if ($relative -match '(^|/)(\.|data/|uploads/|backups/)' -or $relative -match '\.(db|sqlite|sqlite3|sql|log|pem|key|erpb|erpg)$') {
            throw "Private data or configuration is not allowed in the web build: $relative"
        }
        (Get-Sha256 (Join-Path $Directory $relative)) + '  ' + $relative + "`n"
    }
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($lines -join ''))))).Replace('-','').ToLowerInvariant()
    } finally { $sha.Dispose() }
}

$acceptancePath = Join-Path $projectRoot '.cache/acceptance-result.json'
$browserPath = Join-Path $projectRoot '.cache/browser-results.json'
$jar = Join-Path $projectRoot 'backend/target/counselor-erp-1.0.0.jar'
$web = if ([IO.Path]::IsPathRooted($WebDirectory)) { [IO.Path]::GetFullPath($WebDirectory) } else { [IO.Path]::GetFullPath((Join-Path $projectRoot $WebDirectory)) }
if (-not (Test-Path -LiteralPath $web -PathType Container)) { throw "Production web directory does not exist: $web" }
$web = $web.TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
$notes = if ([IO.Path]::IsPathRooted($ReleaseNotes)) { $ReleaseNotes } else { Join-Path $projectRoot $ReleaseNotes }
foreach ($required in @($acceptancePath,$browserPath,$jar,$notes)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required release input is missing: $required" }
}
$acceptance = Get-Content -LiteralPath $acceptancePath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($acceptance.status -ne 'passed' -or $acceptance.browser -ne 'passed') { throw 'Acceptance and production browser checks must pass before packaging.' }
foreach ($check in @('httpSmoke','chatSmoke','restart')) {
    if ($acceptance.$check -ne 'passed') { throw "Required acceptance check did not pass: $check" }
}
$jarSha = Get-Sha256 $jar
$webSha = Get-WebDigest $web
$browserSha = Get-Sha256 $browserPath
if ($jarSha -ne $acceptance.jarSha256) { throw 'Backend changed after acceptance.' }
if ($webSha -ne $acceptance.frontendSha256) { throw 'Production frontend changed after acceptance, or its digest is missing.' }
if ($browserSha -ne $acceptance.browserReportSha256) { throw 'Browser evidence changed after acceptance, or its digest is missing.' }
$browser = Get-Content -LiteralPath $browserPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $browser.stats -or $browser.stats.expected -le 0 -or $browser.stats.unexpected -ne 0 -or $browser.stats.flaky -ne 0 -or $browser.stats.skipped -ne 0 -or @($browser.errors | Where-Object { $null -ne $_ }).Count -gt 0) {
    throw 'Browser report must contain passing tests with no failed, flaky or skipped checks and no global errors.'
}
# Full browser reports can contain traces, request payloads or test-account details.
# Package only an allowlisted evidence summary, keeping the raw report locally.
$evidence = [ordered]@{
    status = 'passed'; version = $Version; packagedAt = [DateTime]::UtcNow.ToString('o')
    acceptanceFinishedAt = $acceptance.finishedAt
    jarSha256 = $jarSha; frontendSha256 = $webSha; browserReportSha256 = $browserSha
    httpSmoke = $acceptance.httpSmoke; chatSmoke = $acceptance.chatSmoke
    browser = $acceptance.browser; restart = $acceptance.restart; browserStats = $browser.stats
}
$template = Join-Path $PSScriptRoot 'linux/erp.env.example'
$templateText = Get-Content -LiteralPath $template -Raw -Encoding UTF8
if ($templateText -match '(?m)^[ \t]*(ERP_(?:DATA_KEY|BACKUP_KEY|ADMIN_PASSWORD|DB_PASSWORD|AI_API_KEY))[ \t]*=[ \t]*\S+') {
    throw 'Deployment configuration template must not contain passwords or keys.'
}

# Use a fresh staging directory; an interrupted build cannot merge stale release files.
$stageParent = Join-Path $PSScriptRoot ('.packaging-' + [guid]::NewGuid().ToString('N'))
$stageRoot = Join-Path $stageParent ('release-' + $Version)
$stageZip = Join-Path $stageParent ('release-' + $Version + '.zip')
try {
    New-Item -ItemType Directory -Force -Path (Join-Path $stageRoot 'backend'),(Join-Path $stageRoot 'web'),(Join-Path $stageRoot 'config/linux'),(Join-Path $stageRoot 'mysql'),(Join-Path $stageRoot 'evidence') | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $stageRoot 'backend')
    Get-ChildItem -LiteralPath $web -Force | Copy-Item -Destination (Join-Path $stageRoot 'web') -Recurse
    foreach ($name in @('counselor-erp.service','erp.env.example','nginx.conf.example','baota-site-location.conf.example')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot ('linux/' + $name)) -Destination (Join-Path $stageRoot 'config/linux')
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mysql/001_schema_mysql8.sql') -Destination (Join-Path $stageRoot 'mysql')
    Copy-Item -LiteralPath $notes -Destination (Join-Path $stageRoot 'RELEASE.md')
    [IO.File]::WriteAllText((Join-Path $stageRoot 'VERSION'),($Version -replace '^v','') + "`n",(New-Object Text.UTF8Encoding($false)))
    $releaseManifest = [ordered]@{schemaVersion=1;version=($Version -replace '^v','');releaseId=$Version;jarSha256=$jarSha;frontendSha256=$webSha;acceptanceFinishedAt=$acceptance.finishedAt}
    [IO.File]::WriteAllText((Join-Path $stageRoot 'release-manifest.json'),($releaseManifest | ConvertTo-Json -Depth 4),(New-Object Text.UTF8Encoding($false)))
    $changelogPath = Join-Path $projectRoot 'CHANGELOG.md'
    if (Test-Path -LiteralPath $changelogPath -PathType Leaf) { Copy-Item -LiteralPath $changelogPath -Destination (Join-Path $stageRoot 'CHANGELOG.md') }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination (Join-Path $stageRoot 'DEPLOY.md')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/部署教程.md') -Destination (Join-Path $stageRoot 'DEPLOY-GUIDE.md')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/宝塔面板部署指南.md') -Destination (Join-Path $stageRoot 'DEPLOY-BAOTA.md')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/备份恢复与运行手册.md') -Destination (Join-Path $stageRoot 'BACKUP-RESTORE.md')
    [IO.File]::WriteAllText((Join-Path $stageRoot 'evidence/acceptance-summary.json'),($evidence | ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
    if ((Get-Sha256 (Join-Path $stageRoot 'backend/counselor-erp-1.0.0.jar')) -ne $jarSha -or (Get-WebDigest (Join-Path $stageRoot 'web')) -ne $webSha) {
        throw 'Build output changed while packaging. Run acceptance again.'
    }
    $lines = Get-ChildItem -LiteralPath $stageRoot -File -Recurse | Sort-Object FullName | ForEach-Object {
        (Get-Sha256 $_.FullName) + '  ' + $_.FullName.Substring($stageRoot.Length + 1).Replace('\','/')
    }
    [IO.File]::WriteAllText((Join-Path $stageRoot 'SHA256SUMS'),(($lines -join "`n") + "`n"),(New-Object Text.UTF8Encoding($false)))
    Compress-Archive -LiteralPath $stageRoot -DestinationPath $stageZip
    Move-Item -LiteralPath $stageRoot -Destination $releaseRoot
    Move-Item -LiteralPath $stageZip -Destination $zipPath
    $zipSha = Get-Sha256 $zipPath
    ($zipSha + '  ' + [IO.Path]::GetFileName($zipPath)) | Set-Content -LiteralPath ($zipPath + '.sha256') -Encoding ASCII
    # Empty after the two moves; never delete this directory recursively.
    Remove-Item -LiteralPath $stageParent
    Write-Output $zipPath
    Write-Output ('SHA256: ' + $zipSha)
} catch {
    throw "Packaging failed. Original data and prior releases were preserved. Staging location: $stageParent. $($_.Exception.Message)"
}
