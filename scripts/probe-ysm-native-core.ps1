param(
    [string]$DllPath = "references\decompiled\ysm-2.6.5-fabric+mc1.21.1-release.jar.src\META-INF\native\ysm-core.dll"
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resolvedDll = Resolve-Path -LiteralPath (Join-Path $root $DllPath) -ErrorAction SilentlyContinue
if ($null -eq $resolvedDll) {
    $resolvedDll = Resolve-Path -LiteralPath $DllPath
}

Write-Host "YSM native core probe"
Write-Host "  DLL: $($resolvedDll.Path)"

$dumpbin = Get-Command dumpbin.exe -ErrorAction SilentlyContinue
if ($dumpbin) {
    Write-Host ""
    Write-Host "== exports =="
    & $dumpbin.Source /exports $resolvedDll.Path

    Write-Host ""
    Write-Host "== section summary =="
    & $dumpbin.Source /headers $resolvedDll.Path |
        Select-String -Pattern "SECTION HEADER|YSMS|\.text|\.rdata|\.data|size of image|entry point"
} else {
    Write-Host ""
    Write-Host "dumpbin.exe not found on PATH; skipping exports/headers."
}

$strings = Get-Command strings.exe -ErrorAction SilentlyContinue
if ($strings) {
    Write-Host ""
    Write-Host "== selected strings =="
    & $strings.Source -n 6 $resolvedDll.Path |
        Select-String -Pattern "JNI|Java_|Register|YSM|yes|model|cache|Packet|ServerCache|ClientCache"
} else {
    Write-Host ""
    Write-Host "strings.exe not found on PATH; skipping string scan."
}

Write-Host ""
Write-Host "== direct System.load probe =="
$probeSource = Join-Path $root "src\test\java\com\ysm\paper\nativebridge\dll\YsmNativeDllProbeMain.java"
java $probeSource $resolvedDll.Path
