@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0package-paper-deploy.ps1" %*
exit /b %ERRORLEVEL%
