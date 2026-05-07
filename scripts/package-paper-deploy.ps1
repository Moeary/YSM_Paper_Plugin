param(
    [string]$OutDir = "build\paperysm-deploy",
    [string]$Fixture = "freesia-from-velocity",
    [string]$Jar = "build\libs\paper-ysm-0.1.0-SNAPSHOT.jar",
    [string]$Config = "test-server\direct-paper\plugins\PaperYSM\config.yml",
    [string]$ModelsDir = "test-server\freesia-worker\config\yes_steve_model\custom",
    [switch]$IncludeNativeFixture,
    [switch]$IncludeModels,
    [switch]$IncludePlayerModels,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

function Copy-Tree($Source, $Destination) {
    if (!(Test-Path -LiteralPath $Source)) {
        throw "Source not found: $Source"
    }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $Destination -Recurse -Force
    }
}

$outPath = Join-Path $Root $OutDir
if ((Test-Path -LiteralPath $outPath) -and !$Force) {
    throw "Output already exists: $outPath. Pass -Force to replace files in it."
}
if ((Test-Path -LiteralPath $outPath) -and $Force) {
    Remove-Item -LiteralPath $outPath -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $outPath | Out-Null

$pluginDir = Join-Path $outPath "plugins"
$dataDir = Join-Path $pluginDir "PaperYSM"
$fixtureSrc = Join-Path $Root "test-server\direct-paper\plugins\PaperYSM\cache\$Fixture"
$legacyFixtureSrc = Join-Path $Root "test-server\direct-paper\plugins\PaperYSM\captures\native-cache\$Fixture"
$fixtureDst = Join-Path $dataDir "cache\$Fixture"

if (!(Test-Path -LiteralPath $Jar)) {
    throw "Plugin jar not found: $Jar. Run gradle build first."
}
if (!(Test-Path -LiteralPath $Config)) {
    throw "Config not found: $Config"
}
if ($IncludeNativeFixture -and !(Test-Path -LiteralPath $fixtureSrc) -and (Test-Path -LiteralPath $legacyFixtureSrc)) {
    $fixtureSrc = $legacyFixtureSrc
}
if ($IncludeNativeFixture -and !(Test-Path -LiteralPath $fixtureSrc)) {
    throw "Native cache fixture not found: $fixtureSrc"
}

New-Item -ItemType Directory -Force -Path $pluginDir | Out-Null
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
Copy-Item -LiteralPath $Jar -Destination (Join-Path $pluginDir (Split-Path $Jar -Leaf)) -Force

$configText = Get-Content -LiteralPath $Config -Raw
$configText = $configText -replace '(?m)^models-dir:.*$', 'models-dir: models'
$configText = $configText -replace '(?m)^debug:.*$', 'debug: false'
$configText = $configText -replace '(?m)^(\s*)model-scan-details:.*$', '$1model-scan-details: false'
$configText = $configText -replace '(?m)^(\s*)packet-details:.*$', '$1packet-details: false'
$configText = $configText -replace '(?m)^(\s*)client-raw-packets:.*$', '$1client-raw-packets: false'
$configText = $configText -replace '(?m)^(\s*)auto-native-cache-on-handshake:.*$', '$1auto-native-cache-on-handshake: false'
$configText = $configText -replace '(?m)^(\s*)auto-generated-cache-on-handshake:.*$', '$1auto-generated-cache-on-handshake: true'
$configText = $configText -replace '(?m)^(\s*)auto-generated-cache-model:.*$', '$1auto-generated-cache-model: all'
$configText = $configText -replace '(?m)^(\s*)auto-generated-cache-max-models:.*$', '$1auto-generated-cache-max-models: 32'
$configText = $configText -replace '(?m)^(\s*)auto-generated-cache-type5-packets-per-tick:.*$', '$1auto-generated-cache-type5-packets-per-tick: 2'
$configText = $configText -replace '(?m)^(\s*)auto-generated-cache-prewarm-on-startup:.*$', '$1auto-generated-cache-prewarm-on-startup: false'
$configText = $configText -replace '(?m)^(\s*)auto-generated-cache-sync-online-after-prewarm:.*$', '$1auto-generated-cache-sync-online-after-prewarm: false'
$configText = $configText -replace '(?m)^(\s*)auto-generated-cache-layout:.*$', '$1auto-generated-cache-layout: openysm'
$configText = $configText -replace '(?m)^(\s*)auto-generated-cache-payload:.*$', '$1auto-generated-cache-payload: server-cache'
$configText = $configText -replace '(?m)^(\s*)auto-native-cache-capture:.*$', ('$1auto-native-cache-capture: ' + $Fixture)
Set-Content -LiteralPath (Join-Path $dataDir "config.yml") -Value $configText -Encoding UTF8

if ($IncludeNativeFixture) {
    New-Item -ItemType Directory -Force -Path $fixtureDst | Out-Null
    foreach ($fileName in @("type3-body.bin", "type1-padding.txt", "type3-padding.txt", "cache-map.tsv", "export-report.tsv")) {
        $sourceFile = Join-Path $fixtureSrc $fileName
        if (Test-Path -LiteralPath $sourceFile) {
            Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $fixtureDst $fileName) -Force
        }
    }
    $serverCacheSrc = Join-Path $fixtureSrc "server-cache"
    if (Test-Path -LiteralPath $serverCacheSrc) {
        Copy-Tree $serverCacheSrc (Join-Path $fixtureDst "server-cache")
    } else {
        throw "Fixture is missing server-cache directory: $serverCacheSrc"
    }
}

if ($IncludeModels) {
    $modelsSrc = Join-Path $Root $ModelsDir
    $modelsDst = Join-Path $dataDir "models"
    Copy-Tree $modelsSrc $modelsDst
}

if ($IncludePlayerModels) {
    $stateFile = Join-Path $Root "test-server\direct-paper\plugins\PaperYSM\player-models.yml"
    if (Test-Path -LiteralPath $stateFile) {
        Copy-Item -LiteralPath $stateFile -Destination (Join-Path $dataDir "player-models.yml") -Force
    }
}

Write-Host "[PaperYSM] Deploy bundle written to $outPath"
Write-Host "[PaperYSM] Copy the bundle's plugins directory into the production Paper server root."
Write-Host "[PaperYSM] Generated cache: openysm/server-cache"
if ($IncludeNativeFixture) {
    Write-Host "[PaperYSM] Native fixture included: $Fixture"
}
if (!$IncludeModels) {
    Write-Host "[PaperYSM] Models were not included. Copy .ysm references to plugins\PaperYSM\models for wheel animation mapping."
}
