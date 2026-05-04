@echo off
setlocal
title FreesiaII Test Stack

set "ROOT=%~dp0"
set "BACKEND_DIR=%ROOT%test-server\paper-backend"
set "VELOCITY_DIR=%ROOT%test-server\velocity-proxy"
set "WORKER_DIR=%ROOT%test-server\freesia-worker"
if not exist "%BACKEND_DIR%\StartServer.bat" set "BACKEND_DIR=%ROOT%test-server-backend"
if not exist "%VELOCITY_DIR%\StartServer.bat" set "VELOCITY_DIR=%ROOT%test-server-velocity"
if not exist "%WORKER_DIR%\start.bat" set "WORKER_DIR=%ROOT%test-server-worker"

echo Starting Paper backend on 127.0.0.1:30001...
start "Paper Backend :30001" /D "%BACKEND_DIR%" cmd /k "call StartServer.bat"

timeout /t 6 /nobreak >nul

echo Starting Velocity/FreesiaII proxy on 127.0.0.1:30000...
start "Velocity FreesiaII :30000" /D "%VELOCITY_DIR%" cmd /k "call StartServer.bat"

timeout /t 5 /nobreak >nul

echo Starting FreesiaII Worker on 127.0.0.1:19199...
start "FreesiaII Worker :19199" /D "%WORKER_DIR%" cmd /k "call start.bat"

echo.
echo Stack startup requested.
echo Join through Velocity: 127.0.0.1:30000
echo FreesiaII debug log: %VELOCITY_DIR%\logs\latest.log
echo Paper backend: %BACKEND_DIR%
echo Direct PaperYSM testing is isolated from this stack.
echo.
pause
