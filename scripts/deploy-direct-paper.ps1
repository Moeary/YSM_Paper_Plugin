param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$Jar = "build\libs\paper-ysm-0.1.0-SNAPSHOT.jar",
    [string]$PluginDir = "test-server\direct-paper\plugins"
)

$ErrorActionPreference = "Stop"

$jarPath = Join-Path $Root $Jar
$pluginDirPath = Join-Path $Root $PluginDir
$destPath = Join-Path $pluginDirPath "paper-ysm-0.1.0-SNAPSHOT.jar"

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Plugin jar not found: $jarPath. Run 'pixi run build' first."
}

New-Item -ItemType Directory -Force -Path $pluginDirPath | Out-Null
Copy-Item -LiteralPath $jarPath -Destination $destPath -Force
Write-Host "Deployed PaperYSM jar to $destPath"
