#requires -Version 5.1
<#
    一键启动整套服务（双击 scripts\start.cmd 即可）。

    做的事：唤醒 WSL -> 等 Docker 就绪 -> docker compose up -d -> 等健康检查 -> 打开浏览器。
    默认不重新构建镜像（几秒完成）；需要重建时传 -Build。

    参数：
        -Build            先 docker compose build 再启动（首次或改代码后用）
        -TimeoutSeconds   每一步的最长等待秒数，默认 240
#>

[CmdletBinding()]
param(
    [switch]$Build,
    [int]$TimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$distro = if ($env:NA_WSL_DISTRO) { $env:NA_WSL_DISTRO } else { 'Ubuntu' }
$appUrl = 'http://localhost:8081'

function Write-Step($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Write-Ok($message) { Write-Host "    $message" -ForegroundColor Green }
function Fail($message) {
    Write-Host ""
    Write-Host "!! $message" -ForegroundColor Red
    exit 1
}

# 在 WSL 里执行一条命令，返回退出码。
# docker compose 会把进度写到 stderr，直接透传会让 PowerShell 显示成红色错误，
# 所以统一捕获后按普通文本打印。
function Invoke-Wsl {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [switch]$Quiet
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & wsl.exe -d $distro -e bash -lc $Command 2>&1
        $code = $LASTEXITCODE
        if (-not $Quiet) {
            foreach ($line in $output) {
                Write-Host "    $line"
            }
        }
        return $code
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

Write-Host ""
Write-Host "  订单系统启动器" -ForegroundColor White
Write-Host "  ----------------------------------------" -ForegroundColor DarkGray
Write-Host ""

# ---------------------------------------------------------------- 1. WSL
Write-Step "检查 WSL 发行版 '$distro'"
if ((Invoke-Wsl -Command 'true' -Quiet) -ne 0) {
    Fail "无法进入 WSL 发行版 '$distro'。请确认已安装：wsl --list --verbose"
}
Write-Ok "WSL 可用"

$wslProject = ''
try {
    $wslProject = (& wsl.exe -d $distro -e wslpath -a -u $projectDir 2>$null | Out-String).Trim()
}
catch { }
if (-not $wslProject -or -not $wslProject.StartsWith('/')) {
    if ($projectDir -match '^([A-Za-z]):\\(.*)$') {
        $wslProject = '/mnt/' + $Matches[1].ToLower() + '/' + ($Matches[2] -replace '\\', '/')
    }
    else {
        Fail "无法把项目路径转换成 WSL 路径：$projectDir"
    }
}
Write-Ok "项目目录 $wslProject"

# ---------------------------------------------------------------- 2. Docker
Write-Step "等待 Docker 守护进程就绪（首次唤醒 WSL 约 10-60 秒）"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$dockerReady = $false
while ((Get-Date) -lt $deadline) {
    if ((Invoke-Wsl -Command 'docker info' -Quiet) -eq 0) {
        $dockerReady = $true
        break
    }
    Start-Sleep -Seconds 2
}
if (-not $dockerReady) {
    Fail "Docker 在 ${TimeoutSeconds} 秒内没有就绪。请在 WSL 里执行 'sudo systemctl status docker' 查看原因。"
}
Write-Ok "Docker 已就绪"

# ---------------------------------------------------------------- 3. compose
$composeCommand = "cd '$wslProject' && docker compose up -d"
if ($Build) {
    Write-Step "构建镜像并启动（首次或改动代码后较慢，请耐心等待）"
    $composeCommand = "cd '$wslProject' && docker compose up -d --build"
}
else {
    Write-Step "启动容器"
}

if ((Invoke-Wsl -Command $composeCommand) -ne 0) {
    Fail "docker compose up 失败。查看日志：wsl -d $distro -e bash -lc `"cd '$wslProject' && docker compose logs --tail=100`""
}
Write-Ok "容器已创建"

# ---------------------------------------------------------------- 4. 健康检查
Write-Step "等待服务健康（最多 ${TimeoutSeconds} 秒）"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$healthy = $false
$lastCode = ''
while ((Get-Date) -lt $deadline) {
    $lastCode = (& curl.exe -s -m 5 -o NUL -w '%{http_code}' "$appUrl/actuator/health" 2>$null | Out-String).Trim()
    if ($lastCode -eq '200') {
        $healthy = $true
        break
    }
    Start-Sleep -Seconds 2
}

if (-not $healthy) {
    Write-Host ""
    Invoke-Wsl -Command "cd '$wslProject' && docker compose ps" | Out-Null
    Fail "服务在 ${TimeoutSeconds} 秒内没有通过健康检查（最后一次 HTTP $lastCode）。"
}
Write-Ok "健康检查通过"

# ---------------------------------------------------------------- 5. 收尾
Write-Host ""
Invoke-Wsl -Command "cd '$wslProject' && docker compose ps" | Out-Null

Write-Host ""
Write-Host "  启动完成" -ForegroundColor Green
Write-Host "  应用    $appUrl" -ForegroundColor White
Write-Host "  Swagger $appUrl/swagger-ui.html" -ForegroundColor White
Write-Host "  账号    demo / 123456" -ForegroundColor White
Write-Host ""

Start-Process $appUrl
exit 0
