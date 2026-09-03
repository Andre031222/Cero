@echo off
rem cero — órdenes de Cero en Windows. El trabajo está en cero.ps1; esto solo lo llama,
rem para que sirva igual desde cmd.exe, desde PowerShell y desde un doble clic.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0cero.ps1" %*
exit /b %ERRORLEVEL%
