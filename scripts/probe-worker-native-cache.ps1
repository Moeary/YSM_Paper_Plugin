param(
    [string]$WorkerRoot = "test-server\freesia-worker",
    [string]$FixtureRoot = "test-server\direct-paper\plugins\PaperYSM\captures\native-cache\freesia-latest",
    [string]$ModelName = "拉菲Ⅱ/拉菲Ⅱ_v1.2.ysm"
)

$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return (Resolve-Path -LiteralPath $Path).Path
    }

    return (Resolve-Path -LiteralPath (Join-Path $repoRoot $Path)).Path
}

function Get-CacheEntryInfo {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$File,
        [string]$Kind
    )

    [pscustomobject]@{
        Kind = $Kind
        Name = $File.Name
        Length = $File.Length
        Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $File.FullName).Hash
        Path = $File.FullName
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$workerRootPath = Resolve-RepoPath $WorkerRoot
$workerCacheDir = Join-Path $workerRootPath "config\yes_steve_model\cache\server"
$workerIndexPath = Join-Path $workerRootPath "config\yes_steve_model\cache\server_index"
$workerCustomDir = Join-Path $workerRootPath "config\yes_steve_model\custom"

Write-Host "YSM worker native cache probe"
Write-Host "  worker: $workerRootPath"
Write-Host "  server cache: $workerCacheDir"

if (!(Test-Path -LiteralPath $workerCacheDir)) {
    throw "Worker server cache directory is missing: $workerCacheDir"
}

$workerFiles = Get-ChildItem -LiteralPath $workerCacheDir -File | Sort-Object Length, Name
$workerEntries = $workerFiles | ForEach-Object { Get-CacheEntryInfo -File $_ -Kind "worker" }
$workerTotalBytes = ($workerEntries | Measure-Object -Property Length -Sum).Sum

Write-Host ""
Write-Host "== worker cache summary =="
Write-Host "  files: $($workerEntries.Count)"
Write-Host "  bytes: $workerTotalBytes"
if (Test-Path -LiteralPath $workerIndexPath) {
    $index = Get-Item -LiteralPath $workerIndexPath
    Write-Host "  server_index: $($index.Length) bytes (native binary index)"
}

if (Test-Path -LiteralPath $workerCustomDir) {
    Write-Host ""
    Write-Host "== worker custom models =="
    Get-ChildItem -LiteralPath $workerCustomDir -Recurse -File -Filter "*.ysm" |
        Sort-Object FullName |
        ForEach-Object {
            $relative = $_.FullName.Substring($workerCustomDir.Length + 1)
            Write-Host ("  {0} ({1} bytes)" -f $relative, $_.Length)
        }
}

Write-Host ""
Write-Host "== largest worker cache files =="
$workerEntries |
    Sort-Object Length -Descending |
    Select-Object -First 10 Name, Length, Sha256 |
    Format-Table -AutoSize

if ([string]::IsNullOrWhiteSpace($FixtureRoot)) {
    exit 0
}

$fixtureRootPath = Resolve-RepoPath $FixtureRoot
$cacheMapPath = Join-Path $fixtureRootPath "cache-map.tsv"
$fixtureServerCacheDir = Join-Path $fixtureRootPath "server-cache"

if (!(Test-Path -LiteralPath $cacheMapPath) -or !(Test-Path -LiteralPath $fixtureServerCacheDir)) {
    Write-Host ""
    Write-Host "Fixture cache map/server-cache not found; skipping fixture comparison."
    Write-Host "  fixture: $fixtureRootPath"
    exit 0
}

$cacheMap = Import-Csv -Delimiter "`t" -Encoding UTF8 -LiteralPath $cacheMapPath
$fixtureRows = foreach ($row in $cacheMap) {
    $fixtureFile = Join-Path $fixtureRootPath $row.file
    if (!(Test-Path -LiteralPath $fixtureFile)) {
        continue
    }

    $file = Get-Item -LiteralPath $fixtureFile
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash
    $shaMatch = $workerEntries | Where-Object { $_.Sha256 -eq $hash }
    $sizeMatch = $workerEntries | Where-Object { $_.Length -eq $file.Length }

    [pscustomobject]@{
        Model = $row.name
        FixtureToken = $row.tokenHex
        FixtureBytes = $file.Length
        WorkerMatch = if ($shaMatch) { ($shaMatch.Name -join ",") } elseif ($sizeMatch) { ($sizeMatch.Name -join ",") } else { "" }
        Match = if ($shaMatch) { "sha256" } elseif ($sizeMatch) { "size-only" } else { "none" }
        Sha256 = $hash
    }
}

Write-Host ""
Write-Host "== fixture comparison =="
$fixtureRows |
    Sort-Object @{ Expression = { if ($_.Match -eq "sha256") { 0 } elseif ($_.Match -eq "size-only") { 1 } else { 2 } } }, Model |
    Format-Table Model, FixtureBytes, Match, WorkerMatch -AutoSize

if (![string]::IsNullOrWhiteSpace($ModelName)) {
    $target = $fixtureRows | Where-Object { $_.Model -eq $ModelName } | Select-Object -First 1
    Write-Host ""
    Write-Host "== target model =="
    if ($target) {
        $target | Format-List Model, FixtureToken, FixtureBytes, Match, WorkerMatch, Sha256
        if ($target.Match -eq "sha256") {
            Write-Host "  result: worker already contains the exact native-generated server-cache bytes for this model."
        } elseif ($target.Match -eq "size-only") {
            Write-Host "  result: worker has a same-size file, but the bytes differ; do not treat it as an oracle match."
        } else {
            Write-Host "  result: no matching worker cache file was found for this fixture row."
        }
    } else {
        Write-Host "  target model was not found in fixture cache-map.tsv: $ModelName"
    }
}
