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
echo Common commands:
echo   scripts\export-worker-cache-batch.bat snapshot --snapshot-name baseline
echo   scripts\export-worker-cache-batch.bat export --group "R18模型整合" --snapshot-name baseline
echo   scripts\export-worker-cache-batch.bat type3-inspect
echo.
echo Workflow:
echo   1. Clear worker config\yes_steve_model\custom, start worker once, then run snapshot.
echo   2. Copy one model group into worker config\yes_steve_model\custom\GROUP, start worker once.
echo   3. Run export for that GROUP, then test Paper with /ysm sync.
exit /b 0
