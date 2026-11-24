@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local.ps1" %*
set "launchExit=%errorlevel%"
if not "%launchExit%"=="0" pause
exit /b %launchExit%
