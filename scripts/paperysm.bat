@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "PYTHON_EXE=D:\Programs\Anaconda\python.exe"
if not exist "%PYTHON_EXE%" set "PYTHON_EXE=python"
"%PYTHON_EXE%" "%SCRIPT_DIR%paperysm_cli.py" %*
exit /b %ERRORLEVEL%
