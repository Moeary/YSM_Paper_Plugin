@echo off
setlocal
title PaperYSM Direct Test Server

set "ROOT=%~dp0"
set "DIRECT_DIR=%ROOT%test-server\direct-paper"
if not exist "%DIRECT_DIR%\StartServer.bat" set "DIRECT_DIR=%ROOT%test-server"

echo Starting direct PaperYSM test server on 127.0.0.1:30001...
echo Join directly: 127.0.0.1:30001
echo.
start "PaperYSM Direct :30001" /D "%DIRECT_DIR%" cmd /k "call StartServer.bat"

echo Startup requested.
echo.
pause
