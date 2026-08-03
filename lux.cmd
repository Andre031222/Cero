@echo off
rem lux — órdenes de LuxCore en Windows. El trabajo está en lux.ps1; esto solo lo llama,
rem para que sirva igual desde cmd.exe, desde PowerShell y desde un doble clic.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0lux.ps1" %*
exit /b %ERRORLEVEL%
