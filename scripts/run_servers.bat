@echo off
chcp 936 >nul
setlocal

rem ============================================================
rem   PC �������������ű�
rem   App ͨ�� http://<PC��IP>:8080 ���ӣ�ģ������ 10.0.2.2:8080��
rem   �÷�: run_servers.bat [ģ���ļ���] [�˿�]
rem ============================================================

set "ROOT=%~dp0.."
set "SERVER=%ROOT%\models\x86_64\llama-b11558-win-x64\llama-server.exe"
set "MODELDIR=%ROOT%\models\gguf"

rem ---- Ĭ��ģ��: Qwen3.5-4B Q6_K (�ķ����; ����Լ 4.1 tok/s) ----
set "MODELNAME=%~1"
if "%MODELNAME%"=="" set "MODELNAME=Qwen3.5-4B-Q6_K.gguf"
set "MODEL=%MODELDIR%\%MODELNAME%"

set "PORT=%~2"
if "%PORT%"=="" set "PORT=8080"

set "THREADS=6"
set "CTX=8192"

echo ============================================================
echo    AI ��ɫ���� - PC ���������
echo ============================================================
echo    ģ��   : %MODELNAME%
echo    �˿�   : %PORT%
echo    ������ : %CTX%    �߳�: %THREADS%
echo ============================================================
echo.

if not exist "%SERVER%" goto nnnoserver
if not exist "%MODEL%" goto nnnomodel
goto nnstart

:nnnoserver
echo [����] �Ҳ��� llama-server.exe :
echo        %SERVER%
echo.
pause
exit /b 1

:nnnomodel
echo [����] ָ��ģ�Ͳ�����, �Զ���ѡһ�����õ� gguf ...
set "PICKED="
for %%F in ("%MODELDIR%\*.gguf") do set "PICKED=%%F"
if not defined PICKED goto nnnogguf
set "MODEL=%PICKED%"
echo        ��ѡ��: %MODEL%
echo.
goto nnstart

:nnnogguf
echo [����] %MODELDIR% ��û���κ� .gguf ģ���ļ�
echo.
pause
exit /b 1

:nnstart
echo �������... �뱣�ֱ����ڿ���, �رմ��ڼ�ֹͣ����
echo ������־: listening on http://0.0.0.0:%PORT%
echo.

"%SERVER%" -m "%MODEL%" --host 0.0.0.0 --port %PORT% --threads %THREADS% --ctx-size %CTX% --reasoning off --repeat-last-n 512 --repeat-penalty 1.1 --presence-penalty 0.2 --frequency-penalty 0.1 --dry-multiplier 0.5 --dry-base 1.75 --dry-allowed-length 3 --dry-penalty-last-n 512

echo.
echo �������˳���
pause