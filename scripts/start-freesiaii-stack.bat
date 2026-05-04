@echo off
setlocal
title FreesiaII Comparison Stack

set "ROOT=%~dp0.."

echo Starting Paper backend on 127.0.0.1:30001...
start "Paper Backend :30001" /D "%ROOT%\test-server\paper-backend" cmd /k "call StartServer.bat"

timeout /t 6 /nobreak >nul

echo Starting Velocity/FreesiaII proxy on 127.0.0.1:30000...
start "Velocity FreesiaII :30000" /D "%ROOT%\test-server\velocity-proxy" cmd /k "call StartServer.bat"

timeout /t 5 /nobreak >nul

echo Starting FreesiaII Worker on 127.0.0.1:19199...
start "FreesiaII Worker :19199" /D "%ROOT%\test-server\freesia-worker" cmd /k "call start.bat"

echo.
echo Stack startup requested.
echo Join through Velocity: 127.0.0.1:30000
echo Paper backend is test-server\paper-backend.
echo Velocity logs are under test-server\velocity-proxy\logs.
echo.
pause
