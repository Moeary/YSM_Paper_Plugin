@echo off
setlocal
cd /d "%~dp0.."
call scripts\export-freesia-native-fixture.bat %*
if errorlevel 1 exit /b %ERRORLEVEL%
echo [PaperYSM] Done. Paper source name: freesia-from-velocity
echo [PaperYSM] In the Paper test server, run:
echo   /ysm source default freesia-from-velocity
echo   /ysm sync
exit /b 0
