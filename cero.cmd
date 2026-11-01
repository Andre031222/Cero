@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0cero.ps1" %*
exit /b %ERRORLEVEL%
