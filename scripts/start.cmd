@echo off
rem Double-click to start the whole stack.
rem Add --build to rebuild images first:  start.cmd --build
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
set EXITCODE=%errorlevel%
echo.
if not "%EXITCODE%"=="0" (
    echo Start failed with exit code %EXITCODE%.
) else (
    echo You can close this window.
)
pause
exit /b %EXITCODE%
