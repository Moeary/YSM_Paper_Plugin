@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "PYTHON_EXE=D:\Programs\Anaconda\python.exe"
if not exist "%PYTHON_EXE%" set "PYTHON_EXE=python"
if "%~1"=="" goto :usage
"%PYTHON_EXE%" "%SCRIPT_DIR%ysm_worker_cache_batch.py" %*
exit /b %ERRORLEVEL%

:usage
echo [PaperYSM] Worker cache batch helper
echo.
echo WARNING:
echo   Direct worker-cache export by filesystem order is unsafe and now blocked
echo   unless you pass --unsafe-order-pair. Prefer scripts\paperysm.bat export-capture
echo   after a real Velocity/Freesia client sync.
echo.
echo Common commands:
echo   scripts\export-worker-cache-batch.bat snapshot --snapshot-name baseline
echo   scripts\export-worker-cache-batch.bat export --group "R18模型整合" --snapshot-name baseline --unsafe-order-pair
echo   scripts\export-worker-cache-batch.bat type3-inspect
echo   scripts\paperysm.bat
echo.
echo Workflow:
echo   1. Copy one model group into worker config\yes_steve_model\custom\GROUP.
echo   2. Start the full Velocity/Freesia stack and trigger a real client sync.
echo   3. Run scripts\paperysm.bat export-capture, then test Paper with /ysm sync.
exit /b 0
