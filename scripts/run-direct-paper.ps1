param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$ServerDir = "test-server\direct-paper",
    [string]$JavaExe = "java"
)

$ErrorActionPreference = "Stop"

$serverPath = Join-Path $Root $ServerDir
$paperJar = Join-Path $serverPath "paper.jar"
$authlibJar = Join-Path $serverPath "authlib-injector.jar"
$authlibUrl = "https://skin.mualliance.ltd/api/union/yggdrasil"

if (-not (Test-Path -LiteralPath $paperJar)) {
    throw "Paper jar not found: $paperJar"
}

Push-Location $serverPath
try {
    Write-Host "Starting direct PaperYSM test server on 127.0.0.1:30001"
    if (Test-Path -LiteralPath $authlibJar) {
        & $JavaExe "-javaagent:$authlibJar=$authlibUrl" -jar "paper.jar"
    } else {
        Write-Host "authlib-injector.jar not found; starting without authlib-injector."
        & $JavaExe -jar "paper.jar"
    }
} finally {
    Pop-Location
}
