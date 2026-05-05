@echo off
setlocal
set "CAPTURE_DIR=%~1"
set "OUT_DIR=%~2"
set "C2S_DIR=%~3"
if "%CAPTURE_DIR%"=="" (
  if exist "test-server\velocity-proxy\plugins\ysm-sniffer-captures\index.tsv" (
    set "CAPTURE_DIR=test-server\velocity-proxy\plugins\ysm-sniffer-captures"
  ) else if exist "test-server\velocity-proxy\logs\latest.log" (
    set "CAPTURE_DIR=test-server\velocity-proxy\logs\latest.log"
  ) else (
    set "CAPTURE_DIR=test-server\velocity-proxy\plugins\freesia-debug-capture"
  )
)
if "%OUT_DIR%"=="" set "OUT_DIR=test-server\direct-paper\plugins\PaperYSM\captures\native-cache\freesia-from-velocity"
if "%C2S_DIR%"=="" if /I "%CAPTURE_DIR%"=="test-server\velocity-proxy\plugins\freesia-debug-capture" if exist "test-server\velocity-proxy\plugins\ysm-sniffer-captures\index.tsv" set "C2S_DIR=test-server\velocity-proxy\plugins\ysm-sniffer-captures"
if "%C2S_DIR%"=="" if /I "%CAPTURE_DIR%"=="test-server\velocity-proxy\logs\latest.log" if exist "test-server\velocity-proxy\plugins\ysm-sniffer-captures\index.tsv" set "C2S_DIR=test-server\velocity-proxy\plugins\ysm-sniffer-captures"

if not exist "%CAPTURE_DIR%" (
  echo [PaperYSM] Capture input not found: %CAPTURE_DIR%
  echo [PaperYSM] Start the Velocity/Freesia worker stack with ysm-sniffer enabled, join once, then rerun this script.
  exit /b 2
)
if not "%C2S_DIR%"=="" if not exist "%C2S_DIR%" (
  echo [PaperYSM] C2S sniffer capture directory not found: %C2S_DIR%
  echo [PaperYSM] Make sure ysm-sniffer is enabled under test-server\velocity-proxy\plugins.
  exit /b 2
)

set "GRADLE_EXE=gradle"
if exist "C:\Users\minec\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" (
  set "GRADLE_EXE=C:\Users\minec\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat"
) else if exist "C:\Users\minec\.gradle\wrapper\dists\gradle-8.12.1-bin\eumc4uhoysa37zql93vfjkxy0\gradle-8.12.1\bin\gradle.bat" (
  set "GRADLE_EXE=C:\Users\minec\.gradle\wrapper\dists\gradle-8.12.1-bin\eumc4uhoysa37zql93vfjkxy0\gradle-8.12.1\bin\gradle.bat"
)

echo [PaperYSM] S2C capture: %CAPTURE_DIR%
if not "%C2S_DIR%"=="" echo [PaperYSM] C2S capture: %C2S_DIR%
echo [PaperYSM] Output fixture: %OUT_DIR%
for %%I in ("%OUT_DIR%") do set "SOURCE_NAME=%%~nxI"

if "%C2S_DIR%"=="" (
  call "%GRADLE_EXE%" exportFreesiaNativeFixture -PfreesiaCaptureDir="%CAPTURE_DIR%" -PfreesiaFixtureOut="%OUT_DIR%"
) else (
  call "%GRADLE_EXE%" exportFreesiaNativeFixture -PfreesiaCaptureDir="%CAPTURE_DIR%" -PfreesiaFixtureOut="%OUT_DIR%" -PfreesiaC2sDir="%C2S_DIR%"
)
if errorlevel 1 exit /b %ERRORLEVEL%

powershell.exe -NoProfile -Command "$map = Join-Path '%OUT_DIR%' 'cache-map.tsv'; if (Test-Path -LiteralPath $map) { $count = [Math]::Max(0, (Get-Content -LiteralPath $map | Measure-Object).Count - 1); Write-Host ('[PaperYSM] Exported cache entries: ' + $count) }"
echo [PaperYSM] Paper test commands:
echo   /ysm source default %SOURCE_NAME%
echo   /ysm sync
exit /b 0
