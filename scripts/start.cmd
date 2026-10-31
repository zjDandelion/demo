@echo off
rem Double-click to start the whole stack.
rem   start.cmd            启动后即返回，服务随 WSL 空闲休眠
rem   start.cmd --keep     保持窗口，服务一直可访问（关闭窗口即停止）
rem   start.cmd --build    先重建镜像再启动
rem   start.cmd --keep-build  保活 + 重建
setlocal
set ARGS=
if /I "%~1"=="--keep" set ARGS=-KeepAlive
if /I "%~1"=="--build" set ARGS=-Build
if /I "%~1"=="--keep-build" set ARGS=-KeepAlive -Build

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %ARGS%
set EXITCODE=%errorlevel%
echo.
if not "%EXITCODE%"=="0" (
    echo Start failed with exit code %EXITCODE%.
) else (
    echo Done.
)
pause
exit /b %EXITCODE%
