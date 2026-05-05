@echo off
setlocal
cd /d "%~dp0.."
set "GRADLE_EXE=gradle"
if exist "C:\Users\minec\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" (
  set "GRADLE_EXE=C:\Users\minec\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat"
)
call "%GRADLE_EXE%" ysmSnifferJar
if errorlevel 1 exit /b %ERRORLEVEL%
copy /Y "build\libs\ysm-sniffer-0.1.1.jar" "test-server\velocity-proxy\plugins\ysm-sniffer-0.1.1.jar"
echo [PaperYSM] Installed ysm-sniffer-0.1.1.jar into test-server\velocity-proxy\plugins.
echo [PaperYSM] Restart Velocity to load the new sniffer config.
exit /b 0
