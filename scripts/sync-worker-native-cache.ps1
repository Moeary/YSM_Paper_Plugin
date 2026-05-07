param(
    [string]$WorkerCacheDir = (Join-Path $PSScriptRoot "..\test-server\freesia-worker\config\yes_steve_model\cache\server"),
    [string]$PaperFixtureDir = (Join-Path $PSScriptRoot "..\test-server\direct-paper\plugins\PaperYSM\cache\freesia-from-velocity"),
    [string]$Group = ("ysm" + [char]0x81ea + [char]0x5e26 + [char]0x6a21 + [char]0x578b),
    [switch]$GroupFromModelName,
    [switch]$ReorganizeExisting,
    [switch]$CopyUnmapped,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Resolve-ExistingDirectory {
    param([string]$PathValue, [string]$Name)
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction SilentlyContinue
    if ($null -eq $resolved) {
        throw "$Name not found: $PathValue"
    }
    return $resolved.Path
}

function Convert-ToSafeName {
    param([string]$Name, [string]$Fallback)
    $safe = $Name -replace '[<>:"/\\|?*\x00-\x1F]', '_'
    $safe = $safe.Trim().TrimEnd(".")
    if ([string]::IsNullOrWhiteSpace($safe)) {
        $safe = $Fallback
    }
    if ($safe.Length -gt 120) {
        $safe = $safe.Substring(0, 120)
    }
    return $safe
}

function Get-Sha256Hex {
    param([string]$PathValue)
    return (Get-FileHash -LiteralPath $PathValue -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Join-FixtureRelativePath {
    param([string[]]$Parts)
    return (($Parts | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "/")
}

$workerRoot = Resolve-ExistingDirectory $WorkerCacheDir "Worker cache directory"
$fixtureRoot = Resolve-ExistingDirectory $PaperFixtureDir "Paper native-cache fixture"
$mapFile = Join-Path $fixtureRoot "cache-map.tsv"
if (-not (Test-Path -LiteralPath $mapFile)) {
    throw "cache-map.tsv not found: $mapFile"
}

$workerFiles = Get-ChildItem -LiteralPath $workerRoot -File -Recurse
if ($workerFiles.Count -eq 0) {
    throw "No worker cache files found under: $workerRoot"
}

$sourceBySha = @{}
foreach ($file in ($workerFiles | Sort-Object LastWriteTimeUtc -Descending)) {
    $sha = Get-Sha256Hex $file.FullName
    if (-not $sourceBySha.ContainsKey($sha)) {
        $sourceBySha[$sha] = $file
    }
}

$rows = New-Object System.Collections.Generic.List[object]
$lines = [System.IO.File]::ReadAllLines($mapFile, [System.Text.Encoding]::UTF8)
foreach ($line in $lines) {
    $trimmed = $line.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#") -or $trimmed.StartsWith("tokenHex`t")) {
        continue
    }
    $parts = $line -split "`t", 4
    if ($parts.Length -lt 2) {
        throw "Bad cache-map.tsv line: $line"
    }
    $name = if ($parts.Length -ge 3 -and -not [string]::IsNullOrWhiteSpace($parts[2])) { $parts[2] } else { $parts[0] }
    $bytes = if ($parts.Length -ge 4 -and -not [string]::IsNullOrWhiteSpace($parts[3])) { [int64]$parts[3] } else { 0 }
    $row = [pscustomobject]@{
        tokenHex = $parts[0].ToLowerInvariant()
        file = $parts[1]
        name = $name
        bytes = $bytes
        currentHash = $null
        currentPath = $null
        sourceFile = $null
        action = "unchanged"
    }
    $currentRelative = $row.file -replace '/', [System.IO.Path]::DirectorySeparatorChar
    $currentPath = Join-Path $fixtureRoot $currentRelative
    if (Test-Path -LiteralPath $currentPath) {
        $row.currentPath = (Resolve-Path -LiteralPath $currentPath).Path
        $row.currentHash = Get-Sha256Hex $row.currentPath
        if ($sourceBySha.ContainsKey($row.currentHash)) {
            $row.sourceFile = $sourceBySha[$row.currentHash]
            $row.action = "worker-match"
        } elseif ($ReorganizeExisting) {
            $row.action = "reorganize-existing"
        }
    } else {
        Write-Warning "Mapped cache file is missing and will be left unchanged: $($row.file)"
    }
    $rows.Add($row)
}

$matchedHashes = New-Object System.Collections.Generic.HashSet[string]
$changed = $false
foreach ($row in $rows) {
    if ($row.action -eq "unchanged") {
        continue
    }
    if ($row.currentHash) {
        [void]$matchedHashes.Add($row.currentHash)
    }

    $targetGroup = $Group
    if ($GroupFromModelName -and $row.name.Contains("/")) {
        $targetGroup = ($row.name -split "/", 2)[0]
    }
    $safeGroup = Convert-ToSafeName $targetGroup "native-cache"
    $safeName = Convert-ToSafeName $row.name $row.tokenHex
    $destRel = Join-FixtureRelativePath @(
        "server-cache",
        $safeGroup,
        ($safeName + "--" + $row.tokenHex + ".bin")
    )
    $destAbs = Join-Path $fixtureRoot ($destRel -replace '/', [System.IO.Path]::DirectorySeparatorChar)
    $copyFrom = if ($row.sourceFile -ne $null) { $row.sourceFile.FullName } else { $row.currentPath }
    $copyFromFull = [System.IO.Path]::GetFullPath($copyFrom)
    $destFull = [System.IO.Path]::GetFullPath($destAbs)
    $sameFile = $copyFromFull.Equals($destFull, [System.StringComparison]::OrdinalIgnoreCase)
    $oldFile = $row.file
    $oldBytes = $row.bytes

    if ($DryRun) {
        Write-Host ("DRY-RUN {0}: {1} -> {2}" -f $row.action, $copyFrom, $destRel)
    } elseif (-not $sameFile) {
        $destDir = Split-Path -Parent $destAbs
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null
        Copy-Item -LiteralPath $copyFrom -Destination $destAbs -Force
    }
    $row.file = $destRel
    $row.bytes = (Get-Item -LiteralPath $copyFrom).Length
    if ($oldFile -ne $row.file -or $oldBytes -ne $row.bytes) {
        $changed = $true
    }
}

if ($CopyUnmapped) {
    $unmappedGroup = Convert-ToSafeName "_unmapped-worker-cache" "_unmapped-worker-cache"
    foreach ($entry in $sourceBySha.GetEnumerator()) {
        if ($matchedHashes.Contains($entry.Key)) {
            continue
        }
        $source = $entry.Value
        $name = Convert-ToSafeName ($source.Name + ".bin") $entry.Key
        $destRel = Join-FixtureRelativePath @("server-cache", $unmappedGroup, $name)
        $destAbs = Join-Path $fixtureRoot ($destRel -replace '/', [System.IO.Path]::DirectorySeparatorChar)
        if ($DryRun) {
            Write-Host ("DRY-RUN unmapped-copy: {0} -> {1}" -f $source.FullName, $destRel)
        } else {
            $destDir = Split-Path -Parent $destAbs
            New-Item -ItemType Directory -Force -Path $destDir | Out-Null
            Copy-Item -LiteralPath $source.FullName -Destination $destAbs -Force
        }
    }
}

if ($changed) {
    $output = New-Object System.Collections.Generic.List[string]
    $output.Add("tokenHex`tfile`tname`tbytes")
    foreach ($row in $rows) {
        $output.Add(($row.tokenHex, $row.file, $row.name, $row.bytes) -join "`t")
    }
    if ($DryRun) {
        Write-Host "DRY-RUN cache-map.tsv would be updated."
    } else {
        $backup = $mapFile + ".bak-" + (Get-Date -Format "yyyyMMdd-HHmmss")
        Copy-Item -LiteralPath $mapFile -Destination $backup
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllLines($mapFile, $output, $utf8NoBom)
        Write-Host "Updated cache-map.tsv; backup: $backup"
    }
} else {
    Write-Host "No mapped cache entries changed."
}

$workerMatchCount = @($rows | Where-Object { $_.action -eq "worker-match" }).Count
$reorganizedCount = @($rows | Where-Object { $_.action -eq "reorganize-existing" }).Count
$unchangedCount = @($rows | Where-Object { $_.action -eq "unchanged" }).Count
Write-Host ("Summary: rows={0}, worker-matches={1}, reorganized-existing={2}, unchanged={3}, worker-cache-files={4}" -f `
        $rows.Count, $workerMatchCount, $reorganizedCount, $unchangedCount, $workerFiles.Count)
if ($unchangedCount -gt 0) {
    Write-Host "Unchanged rows either had no matching worker SHA or missing mapped files. They were not remapped to avoid corrupt token/file pairs."
}
