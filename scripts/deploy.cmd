@echo off
chcp 65001 >nul
setlocal
set DISTRO=Ubuntu
set PROJECT_DIR=/mnt/e/zhaoo_workspace/idea_workspace/demo

echo Deploying microservices (first run generates .env and builds both services)...
wsl.exe -d %DISTRO% -- bash -lc "cd '%PROJECT_DIR%' && bash scripts/deploy.sh"
if errorlevel 1 (
    echo.
    echo Deploy failed. See output above.
    pause
    exit /b 1
)

echo.
echo Deploy complete.
echo App:     http://localhost:8081
echo Swagger: http://localhost:8081/swagger-ui.html
echo Demo:    demo / 123456
pause
