@echo off
setlocal
title Paper-YSM Direct Test Server

set "ROOT=%~dp0.."

echo Starting direct Paper-YSM test server on 127.0.0.1:30001...
echo Join directly: 127.0.0.1:30001
echo.
start "Paper-YSM Direct :30001" /D "%ROOT%\test-server\direct-paper" cmd /k "call StartServer.bat"

echo Startup requested.
echo.
pause
