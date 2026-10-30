@echo off
rem Double-click to stop the stack (data volume is kept).
setlocal
set DISTRO=Ubuntu
set PROJECT_DIR=/mnt/e/zhaoo_workspace/idea_workspace/demo

echo Stopping services...
wsl.exe -d %DISTRO% -- bash -lc "cd '%PROJECT_DIR%' && bash scripts/stop.sh"
if errorlevel 1 (
    echo.
    echo Stop failed. See output above.
    pause
    exit /b 1
)
pause
