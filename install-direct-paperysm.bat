@echo off
setlocal

set "ROOT=%~dp0"
set "JAR=%ROOT%build\libs\paper-ysm-0.1.0-SNAPSHOT.jar"
set "PLUGIN_DIR=%ROOT%test-server\direct-paper\plugins"
if not exist "%PLUGIN_DIR%" set "PLUGIN_DIR=%ROOT%test-server\plugins"
set "REMAPPED_DIR=%PLUGIN_DIR%\.paper-remapped"

if not exist "%JAR%" (
  echo Built plugin jar not found:
  echo   %JAR%
  echo Run the Gradle build first.
  exit /b 1
)

echo Installing PaperYSM into direct Paper test-server plugins...
copy /Y "%JAR%" "%PLUGIN_DIR%\paper-ysm-0.1.0-SNAPSHOT.jar" >nul
if errorlevel 1 exit /b 1

if exist "%REMAPPED_DIR%" (
  echo Removing old Paper remap cache...
  for %%F in (
    "%REMAPPED_DIR%\paper-ysm-0.1.0-SNAPSHOT.jar"
    "%REMAPPED_DIR%\paper-ysm-0.1.0-SNAPSHOT.jar.disabled-for-freesiaii"
    "%REMAPPED_DIR%\index.json"
  ) do (
    if exist "%%~F" (
      del /Q "%%~F"
      if errorlevel 1 (
        echo Failed to remove Paper remap cache file:
        echo   %%~F
        echo Close the Paper server and run this installer again.
        exit /b 1
      )
    )
  )
)

echo Done. Start or restart the direct Paper test server after this.
