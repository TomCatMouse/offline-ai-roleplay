@echo off
rem 真机包（Flyme 版）一键打包 + 自动安装到真机（细节见 build-phys.ps1）
rem 「打包」默认指的是 MuMu 版（build-default.bat）；真机包只在明确要求时打
rem 优先用 pwsh 7（中文参数无编码问题），没有就退回 Windows PowerShell 5.1
where pwsh >nul 2>nul
if %errorlevel%==0 (
  pwsh -NoProfile -File "%~dp0build-phys.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-phys.ps1" %*
)
pause
