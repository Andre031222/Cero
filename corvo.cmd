@echo off
rem corvo — órdenes de Corvo en Windows. El trabajo está en corvo.ps1; esto solo lo llama,
rem para que sirva igual desde cmd.exe, desde PowerShell y desde un doble clic.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0corvo.ps1" %*
exit /b %ERRORLEVEL%
