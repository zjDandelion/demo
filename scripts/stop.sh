#!/usr/bin/env bash
# 停掉整套服务（保留数据卷）。双击 scripts\stop.cmd 调用。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

docker info >/dev/null 2>&1 || {
    echo "Docker 未运行，无需停止。"
    exit 0
}

docker compose stop
docker compose ps
echo "已停止（数据卷 na-pg-data 保留）。重新启动：scripts/start.cmd"
