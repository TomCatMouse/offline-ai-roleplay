@echo off
rem 默认包（MuMu 版）一键打包（细节见 build-default.ps1）
rem 优先用 pwsh 7（中文参数无编码问题），没有就退回 Windows PowerShell 5.1
where pwsh >nul 2>nul
if %errorlevel%==0 (
  pwsh -NoProfile -File "%~dp0build-default.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-default.ps1" %*
)
pause
