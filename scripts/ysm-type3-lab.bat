@echo off
setlocal EnableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
set "PYTHON_EXE=D:\Programs\Anaconda\python.exe"
if not exist "%PYTHON_EXE%" set "PYTHON_EXE=python"

if "%~1"=="" (
  echo Usage: scripts\ysm-type3-lab.bat ^<inspect^|slice^|rebuild^> [options]
  exit /b 1
)

set "ACTION=%~1"
set "REST="
:collect_args
shift
if "%~1"=="" goto run_type3
set REST=!REST! "%~1"
goto collect_args

:run_type3
"%PYTHON_EXE%" "%SCRIPT_DIR%ysm_worker_cache_batch.py" "type3-%ACTION%" !REST!
exit /b %ERRORLEVEL%
